package com.autoideator.orchestrator;

import com.autoideator.agent.Agent;
import com.autoideator.agent.AgentSwarm;
import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.git.GitOperations;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.*;
import com.autoideator.selfimprovement.SelfImprovementEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main orchestration engine that coordinates the entire AutoIdeator workflow.
 */
public class Orchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(Orchestrator.class);

    private final AutoIdeatorConfig config;
    private final LlmInterface llm;
    private final AgentSwarm agentSwarm;
    private final PlanRefiner planRefiner;
    private final ExecutionEngine executionEngine;
    private final SelfImprovementEngine selfImprovementEngine;
    private final GitOperations gitOperations;
    private final ExecutorService executor;
    private final AtomicBoolean running;

    public Orchestrator(AutoIdeatorConfig config) {
        this.config = config;
        this.llm = LlmInterface.create(config);
        AgentSwarm swarm = null;
        try {
            swarm = new AgentSwarm(config, llm);
            this.agentSwarm = swarm;
            this.planRefiner = new PlanRefiner(config, llm);
            this.executionEngine = new ExecutionEngine(config, agentSwarm, llm);
            this.selfImprovementEngine = new SelfImprovementEngine(config, llm, agentSwarm);
            this.gitOperations = new GitOperations(config);
            Thread.UncaughtExceptionHandler handler = (thread, throwable) -> 
                LOG.error("Uncaught exception in Orchestrator virtual thread '{}': {}", 
                    thread.getName(), throwable.getMessage(), throwable);
            this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                    .uncaughtExceptionHandler(handler)
                    .factory());
            this.running = new AtomicBoolean(false);
        } catch (Exception e) {
            if (swarm != null) {
                try { swarm.shutdown(); } catch (Exception suppressed) { e.addSuppressed(suppressed); }
            }
            try { llm.close(); } catch (Exception suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
    }

    /**
     * Main orchestration entry point.
     * Takes an idea and runs the full development cycle.
     */
    public CompletableFuture<Result> orchestrate(Idea idea) {
        if (!running.compareAndSet(false, true)) {
            LOG.warn("Orchestration already running — ignoring duplicate start request");
            return CompletableFuture.completedFuture(
                Result.failure("Orchestration is already running", null, List.of(), Duration.ZERO));
        }
        Instant startTime = Instant.now();
        List<Result.TaskResult> taskResults = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger totalRefinementCycles = new AtomicInteger(0);
        AtomicInteger totalImprovements = new AtomicInteger(0);

        LOG.info("Starting orchestration for idea: {}", idea.description());

        return CompletableFuture.supplyAsync(() -> {
            // Uses dedicated executor instead of ForkJoinPool.commonPool() to avoid
            // blocking common pool threads with long-running LLM calls.
            try {
                // Phase 1: Create initial plan
                LOG.info("Phase 1: Creating initial plan...");
                Plan plan = createInitialPlan(idea);
                taskResults.add(createTaskResult("planning", "Initial plan creation", true, plan.toString(), null));

                // Phase 2: Refine the plan
                LOG.info("Phase 2: Refining plan ({} cycles)...", config.orchestration().planRefinementCycles());
                for (int i = 0; i < config.orchestration().planRefinementCycles() && running.get(); i++) {
                    LOG.info("Refinement cycle {}/{}", i + 1, config.orchestration().planRefinementCycles());
                    PlanRefiner.RefinementResult result = planRefiner.refine(plan, idea).join();
                    plan = result.plan();
                    totalRefinementCycles.incrementAndGet();
                    LOG.debug("Refinement {}: {}", i + 1, result.summary());
                }
                taskResults.add(createTaskResult("refinement", "Plan refinement", true,
                    "Completed " + totalRefinementCycles.get() + " refinement cycles", null));

                // Phase 3: Execute the plan
                if (!config.dryRun()) {
                    LOG.info("Phase 3: Executing plan...");
                    ExecutionEngine.ExecutionResult execResult = executionEngine.execute(plan, idea).join();
                    taskResults.addAll(execResult.taskResults());
                    plan = execResult.plan();

                    // Commit after execution
                    if (config.git().autoCommit()) {
                        commitChanges("feat: Implement " + truncate(idea.description(), 50));
                    }

                    // Phase 4: Self-improvement cycle (runs infinitely until stopped)
                    if (config.selfImprovement().enabled() && running.get()) {
                        LOG.info("Phase 4: Starting self-improvement cycle...");
                        while (running.get()) {
                            try {
                                SelfImprovementEngine.ImprovementResult improvementResult =
                                    selfImprovementEngine.findAndImplementImprovements(idea.workingDirectory()).join();

                                if (improvementResult.improvements().isEmpty()) {
                                    LOG.info("No improvements found. Waiting before next scan...");
                                    Thread.sleep(config.selfImprovement().scanInterval().toMillis());
                                } else {
                                    totalImprovements.addAndGet(improvementResult.improvements().size());
                                    LOG.info("Implemented {} improvements", improvementResult.improvements().size());

                                    // Commit after improvements
                                    if (config.git().autoCommit()) {
                                        commitChanges("improve: " + improvementResult.summary());
                                    }

                                    Thread.sleep(config.selfImprovement().scanInterval().toMillis());
                                }
                            } catch (InterruptedException e) {
                                LOG.info("Self-improvement cycle interrupted");
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                } else {
                    LOG.info("Dry run mode - skipping execution");
                }

                Duration totalDuration = Duration.between(startTime, Instant.now());
                LOG.info("Orchestration completed in {}", totalDuration);

                return Result.success(plan, taskResults, totalRefinementCycles.get(), totalImprovements.get(), totalDuration);

            } catch (Exception e) {
                // Restore interrupt flag if the root cause was an InterruptedException
                // (e.g. wrapped in CompletionException from a .join() call)
                Throwable cause = e;
                while (cause != null) {
                    if (cause instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    cause = cause.getCause();
                }
                LOG.error("Orchestration failed", e);
                Duration totalDuration = Duration.between(startTime, Instant.now());
                return Result.failure(e.getMessage(), null, taskResults, totalDuration);
            } finally {
                running.set(false);
            }
        }, executor);
    }

    /**
     * Stop the orchestration.
     */
    public void stop() {
        LOG.info("Stopping orchestration...");
        running.set(false);
    }

    /**
     * Check if orchestration is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    private Plan createInitialPlan(Idea idea) {
        // Use planner agent to create initial plan
        Task planningTask = new Task(
            "Create execution plan for: " + idea.description(),
            Task.TaskType.ANALYZE,
            Task.TaskPriority.CRITICAL
        );

        Agent.ExecutionContext context = Agent.ExecutionContext.create(config, llm)
            .withProjectContext("Working directory: " + idea.workingDirectory());

        AgentResponse response = agentSwarm.executeTask(planningTask, context).join();

        if (!response.success()) {
            throw new RuntimeException("Failed to create initial plan: " + response.error());
        }

        // Parse the response into a plan with tasks
        return parsePlanFromResponse(response.content(), idea.description());
    }

    private Plan parsePlanFromResponse(String response, String description) {
        Plan plan = new Plan(description);

        // Simple parsing - in production, use more robust parsing
        String[] lines = response.split("\n");
        Task.TaskPriority currentPriority = Task.TaskPriority.MEDIUM;
        Task.TaskType currentType = Task.TaskType.IMPLEMENT;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Extract task description (numbered items or single-char bullets)
            if (line.matches("^\\d+\\.\\s+.+") || line.matches("^[-*]\\s+.+")) {
                // Only detect priority/type on actual task lines to avoid
                // contamination from prose lines containing keywords like "CRITICAL"
                String upperLine = line.toUpperCase();

                // Detect priority — use word boundaries to avoid matching
                // substrings like "HIGHLIGHT" for "HIGH" or "FOLLOW" for "LOW"
                if (upperLine.matches(".*\\bCRITICAL\\b.*")) {
                    currentPriority = Task.TaskPriority.CRITICAL;
                } else if (upperLine.matches(".*\\bHIGH\\b.*")) {
                    currentPriority = Task.TaskPriority.HIGH;
                } else if (upperLine.matches(".*\\bLOW\\b.*")) {
                    currentPriority = Task.TaskPriority.LOW;
                } else {
                    currentPriority = Task.TaskPriority.MEDIUM;
                }

                // Detect task type — use word boundaries to avoid matching
                // substrings like "LATEST" for "TEST" or "CONTEST" for "TEST"
                if (upperLine.matches(".*\\bTEST(S|ING)?\\b.*")) {
                    currentType = Task.TaskType.TEST;
                } else if (upperLine.matches(".*\\bREVIEW\\b.*")) {
                    currentType = Task.TaskType.REVIEW;
                } else if (upperLine.matches(".*\\bDOCUMENT(ATION)?\\b.*")) {
                    currentType = Task.TaskType.DOCUMENT;
                } else if (upperLine.matches(".*\\b(DESIGN|ANALYZE)\\b.*")) {
                    currentType = Task.TaskType.DESIGN;
                } else {
                    currentType = Task.TaskType.IMPLEMENT;
                }

                String taskDesc = line.replaceFirst("^\\d+\\.\\s+", "");
                taskDesc = taskDesc.replaceFirst("^[-*]\\s+", "");
                if (taskDesc.length() > 10) {  // Filter out too-short lines
                    Task task = new Task(taskDesc, currentType, currentPriority);
                    plan = plan.addTask(task);
                }
            }
        }

        // Ensure we have at least some tasks
        if (plan.tasks().isEmpty()) {
            LOG.warn("No tasks parsed from response, adding default tasks");
            plan = plan.addTask(new Task("Analyze requirements", Task.TaskType.ANALYZE, Task.TaskPriority.CRITICAL));
            plan = plan.addTask(new Task("Design solution architecture", Task.TaskType.DESIGN, Task.TaskPriority.HIGH));
            plan = plan.addTask(new Task("Implement core functionality", Task.TaskType.IMPLEMENT, Task.TaskPriority.HIGH));
            plan = plan.addTask(new Task("Write tests", Task.TaskType.TEST, Task.TaskPriority.MEDIUM));
            plan = plan.addTask(new Task("Review implementation", Task.TaskType.REVIEW, Task.TaskPriority.MEDIUM));
        }

        return plan;
    }

    private void commitChanges(String message) {
        try {
            gitOperations.commitAll(message);
            LOG.info("Committed changes: {}", message);
        } catch (Exception e) {
            LOG.warn("Failed to commit changes: {}", e.getMessage());
        }
    }

    private Result.TaskResult createTaskResult(
        String taskId,
        String description,
        boolean success,
        String output,
        String error
    ) {
        return new Result.TaskResult(taskId, description, success, output, error, Duration.ZERO);
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return "";
        if (s.length() <= maxLength) return s;
        if (maxLength <= 3) return s.substring(0, Math.min(s.length(), maxLength));
        return s.substring(0, maxLength - 3) + "...";
    }

    /**
     * Cleanup resources.
     */
    public void shutdown() {
        stop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        agentSwarm.shutdown();
        try {
            llm.close();
        } catch (Exception e) {
            LOG.debug("Error closing LLM interface", e);
        }
    }
}
