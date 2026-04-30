package com.autoideator.orchestrator;

import com.autoideator.agent.Agent;
import com.autoideator.agent.AgentSwarm;
import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Executes plans by coordinating agent tasks.
 */
public class ExecutionEngine {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionEngine.class);

    private final AutoIdeatorConfig config;
    private final AgentSwarm agentSwarm;
    private final LlmInterface llm;

    public ExecutionEngine(AutoIdeatorConfig config, AgentSwarm agentSwarm, LlmInterface llm) {
        this.config = config;
        this.agentSwarm = agentSwarm;
        this.llm = llm;
    }

    /**
     * Execute a plan and return the results.
     */
    public CompletableFuture<ExecutionResult> execute(Plan plan, Idea idea) {
        return CompletableFuture.supplyAsync(() -> {
            LOG.info("Executing plan with {} tasks", plan.tasks().size());

            Instant startTime = Instant.now();
            List<Result.TaskResult> taskResults = new ArrayList<>();
            Plan currentPlan = plan.withStatus(Plan.PlanStatus.EXECUTING);

            // Build execution context
            Agent.ExecutionContext context = Agent.ExecutionContext.create(config, llm)
                .withProjectContext("Working directory: " + idea.workingDirectory());

            try {
                // Execute tasks respecting dependencies
                Map<Task, AgentResponse> results = agentSwarm
                    .executeTasksWithDependencies(currentPlan.tasks(), context)
                    .join();

                // Process results
                for (Map.Entry<Task, AgentResponse> entry : results.entrySet()) {
                    Task task = entry.getKey();
                    AgentResponse response = entry.getValue();

                    Result.TaskResult tr = new Result.TaskResult(
                        task.id(),
                        task.description(),
                        response.success(),
                        response.content(),
                        response.error(),
                        Duration.ofMillis(response.durationMs())
                    );
                    taskResults.add(tr);

                    // Update task status
                    int taskIndex = findTaskIndex(currentPlan, task);
                    if (taskIndex >= 0) {
                        Task updatedTask = response.success()
                            ? task.withResult(response.content())
                            : task.withError(response.error());
                        currentPlan = currentPlan.updateTask(taskIndex, updatedTask);
                    }
                }

                // Determine final status
                boolean allSuccessful = results.values().stream().allMatch(AgentResponse::success);
                currentPlan = currentPlan.withStatus(allSuccessful ? Plan.PlanStatus.COMPLETED : Plan.PlanStatus.FAILED);

                Duration totalDuration = Duration.between(startTime, Instant.now());
                LOG.info("Execution completed in {} with {} successful / {} total tasks",
                    totalDuration,
                    taskResults.stream().filter(Result.TaskResult::success).count(),
                    taskResults.size()
                );

                return new ExecutionResult(currentPlan, taskResults, totalDuration);

            } catch (Exception e) {
                LOG.error("Execution failed", e);
                currentPlan = currentPlan.withStatus(Plan.PlanStatus.FAILED);
                Duration totalDuration = Duration.between(startTime, Instant.now());
                return new ExecutionResult(currentPlan, taskResults, totalDuration);
            }
        });
    }

    private int findTaskIndex(Plan plan, Task task) {
        for (int i = 0; i < plan.tasks().size(); i++) {
            if (plan.tasks().get(i).id().equals(task.id())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Result of plan execution.
     */
    public record ExecutionResult(
        Plan plan,
        List<Result.TaskResult> taskResults,
        Duration duration
    ) {
        public long getSuccessfulCount() {
            return taskResults.stream().filter(Result.TaskResult::success).count();
        }

        public long getFailedCount() {
            return taskResults.stream().filter(r -> !r.success()).count();
        }
    }
}
