package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.AgentResponse;
import com.autoideator.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Coordinates a swarm of specialized agents.
 * Uses virtual threads for high concurrency.
 *
 * @deprecated Use {@link com.autoideator.orchestrator.DirectorOrchestrator} instead.
 *             This class is part of the legacy "classic" orchestration mode.
 */
@Deprecated(since = "2.0.0")
public class AgentSwarm {

    private static final Logger LOG = LoggerFactory.getLogger(AgentSwarm.class);

    private final Map<Task.TaskType, Agent> agentsByType;
    private final ExecutorService executor;
    private final AutoIdeatorConfig config;

    public AgentSwarm(AutoIdeatorConfig config, LlmInterface llm) {
        this.config = config;
        this.agentsByType = new EnumMap<>(Task.TaskType.class);
        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> 
            LOG.error("Uncaught exception in AgentSwarm virtual thread '{}': {}", 
                thread.getName(), throwable.getMessage(), throwable);
        this.executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual()
                .uncaughtExceptionHandler(handler)
                .factory());

        // Register all agents
        registerAgents(llm);
    }

    private void registerAgents(LlmInterface llm) {
        registerAgent(new PlannerAgent(config, llm));
        registerAgent(new CoderAgent(config, llm));
        registerAgent(new ReviewerAgent(config, llm));
        registerAgent(new TesterAgent(config, llm));
        registerAgent(new GitAgent(config, llm));
    }

    private void registerAgent(Agent agent) {
        for (Task.TaskType type : getSupportedTypes(agent)) {
            agentsByType.put(type, agent);
            LOG.debug("Registered agent {} for task type {}", agent.getName(), type);
        }
    }

    private Set<Task.TaskType> getSupportedTypes(Agent agent) {
        return Arrays.stream(Task.TaskType.values())
            .filter(agent::canHandle)
            .collect(Collectors.toSet());
    }

    /**
     * Execute a single task with the appropriate agent.
     */
    public CompletableFuture<AgentResponse> executeTask(
        Task task,
        Agent.ExecutionContext context
    ) {
        Agent agent = agentsByType.get(task.type());
        if (agent == null) {
            return CompletableFuture.completedFuture(
                AgentResponse.failure("No agent available for task type: " + task.type())
            );
        }

        String shortId = task.id().length() >= 8 ? task.id().substring(0, 8) : task.id();
        LOG.info("Executing task {} with agent {}", shortId, agent.getName());
        return agent.execute(task, context);
    }

    /**
     * Execute multiple tasks in parallel.
     */
    public CompletableFuture<Map<Task, AgentResponse>> executeTasksParallel(
        List<Task> tasks,
        Agent.ExecutionContext context
    ) {
        if (!config.orchestration().parallelExecution()) {
            return executeTasksSequential(tasks, context);
        }

        int maxConcurrency = config.orchestration().maxConcurrentAgents();
        Semaphore semaphore = new Semaphore(maxConcurrency);

        List<CompletableFuture<Map.Entry<Task, AgentResponse>>> futures = tasks.stream()
            .map(task -> CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        AgentResponse response = executeTask(task, context).join();
                        return Map.entry(task, response);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Map.entry(task, AgentResponse.failure("Task interrupted"));
                }
            }, executor))
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
            );
    }

    /**
     * Execute tasks sequentially.
     */
    public CompletableFuture<Map<Task, AgentResponse>> executeTasksSequential(
        List<Task> tasks,
        Agent.ExecutionContext context
    ) {
        // Use ConcurrentHashMap for thread-safety: thenApply callbacks may
        // run on any thread that completes the preceding future.
        Map<Task, AgentResponse> results = new ConcurrentHashMap<>();

        CompletableFuture<Map<Task, AgentResponse>> chain = CompletableFuture.completedFuture(results);
        for (Task task : tasks) {
            chain = chain.thenCompose(acc ->
                executeTask(task, context).thenApply(response -> {
                    acc.put(task, response);
                    return acc;
                })
            );
        }
        return chain;
    }

    /**
     * Execute tasks respecting dependencies.
     */
    public CompletableFuture<Map<Task, AgentResponse>> executeTasksWithDependencies(
        List<Task> tasks,
        Agent.ExecutionContext context
    ) {
        Map<Task, AgentResponse> results = new ConcurrentHashMap<>();
        Set<String> completedTaskIds = ConcurrentHashMap.newKeySet();

        // Build dependency graph
        Map<String, Task> taskMap = tasks.stream()
            .collect(Collectors.toMap(Task::id, t -> t));

        return executeTasksWithDependenciesRecursive(
            tasks, taskMap, results, completedTaskIds, context
        );
    }

    private CompletableFuture<Map<Task, AgentResponse>> executeTasksWithDependenciesRecursive(
        List<Task> remainingTasks,
        Map<String, Task> taskMap,
        Map<Task, AgentResponse> results,
        Set<String> completedTaskIds,
        Agent.ExecutionContext context
    ) {
        // Find tasks that can be executed (all dependencies satisfied)
        List<Task> readyTasks = remainingTasks.stream()
            .filter(t -> t.status() == Task.TaskStatus.PENDING)
            .filter(t -> t.dependencies().isEmpty() ||
                t.dependencies().stream().allMatch(completedTaskIds::contains))
            .toList();

        if (readyTasks.isEmpty()) {
            if (remainingTasks.stream().anyMatch(t -> t.status() == Task.TaskStatus.PENDING)) {
                // Deadlock detected - some tasks are still pending but none are ready
                // (typically because a dependency failed). Report them as blocked.
                LOG.warn("Potential deadlock detected in task dependencies");
                for (Task t : remainingTasks) {
                    if (t.status() == Task.TaskStatus.PENDING && !results.containsKey(t)) {
                        results.put(t, AgentResponse.failure("Blocked: dependency on failed task"));
                    }
                }
            }
            return CompletableFuture.completedFuture(results);
        }

        // Execute ready tasks in parallel
        return executeTasksParallel(readyTasks, context)
            .thenCompose(taskResults -> {
                // Update completed tasks
                taskResults.forEach((task, response) -> {
                    results.put(task, response);
                    if (response.success()) {
                        completedTaskIds.add(task.id());
                    }
                });

                // Recursively execute remaining tasks
                List<Task> newRemaining = remainingTasks.stream()
                    .filter(t -> !readyTasks.contains(t))
                    .toList();

                if (newRemaining.isEmpty()) {
                    return CompletableFuture.completedFuture(results);
                }

                return executeTasksWithDependenciesRecursive(
                    newRemaining, taskMap, results, completedTaskIds, context
                );
            });
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
