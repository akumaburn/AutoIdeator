package com.autoideator.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Represents the result of an orchestration run.
 */
public record Result(
    boolean success,
    String message,
    Plan finalPlan,
    List<TaskResult> taskResults,
    int refinementCycles,
    int improvementsImplemented,
    Duration totalDuration,
    Instant completedAt
) {
    public record TaskResult(
        String taskId,
        String description,
        boolean success,
        String output,
        String error,
        Duration duration
    ) {}

    public static Result success(Plan plan, List<TaskResult> results, int cycles, int improvements, Duration duration) {
        return new Result(true, "Orchestration completed successfully", plan, results, cycles, improvements, duration, Instant.now());
    }

    public static Result failure(String message, Plan plan, List<TaskResult> results, Duration duration) {
        return new Result(false, message, plan, results, 0, 0, duration, Instant.now());
    }

    public long getSuccessfulTaskCount() {
        return taskResults.stream().filter(TaskResult::success).count();
    }

    public long getFailedTaskCount() {
        return taskResults.stream().filter(r -> !r.success()).count();
    }

    @Override
    public String toString() {
        return String.format("Result[success=%s, tasks=%d (ok=%d, fail=%d), cycles=%d, improvements=%d, duration=%s]",
            success, taskResults.size(), getSuccessfulTaskCount(), getFailedTaskCount(),
            refinementCycles, improvementsImplemented, totalDuration);
    }
}
