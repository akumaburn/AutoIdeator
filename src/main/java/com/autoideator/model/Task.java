package com.autoideator.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a single task within an execution plan.
 */
public final class Task {
    private final String id;
    private final String description;
    private final TaskType type;
    private final TaskPriority priority;
    private final boolean blocking;
    private final TaskStatus status;
    private final List<String> dependencies;
    private final String result;
    private final String error;
    private final Instant startedAt;
    private final Instant completedAt;
    private final int retryCount;
    private final int maxRetries;

    public enum TaskType {
        ANALYZE("Analyze requirements and constraints"),
        DESIGN("Design architecture and interfaces"),
        IMPLEMENT("Implement code changes"),
        TEST("Write and run tests"),
        REVIEW("Review code for quality"),
        REFACTOR("Refactor existing code"),
        DOCUMENT("Create or update documentation"),
        GIT("Perform git operations"),
        DEPLOY("Deploy or build artifacts"),
        // New task types for multi-agent system
        DREAM("Generate new ideas and possibilities"),
        CRITIQUE("Critically analyze and challenge ideas"),
        DECIDE("Make decisions based on multiple perspectives"),
        IDEATE("Brainstorm and explore concepts"),
        OVERSEE("Formalize a user suggestion into an actionable improvement plan"),
        PAINT("Generate visual and UX improvement ideas for the project frontend"),
        REFINE("Generate performance optimization ideas for the project"),
        HACK("Generate security hardening ideas for the project"),
        OBSESS("Scrutinize existing functionality for correctness gaps and overlooked edge cases"),
        ADVANCE("Deepen and enrich existing features with richer output, better defaults, and more complete implementations"),
        CURATE("Evaluate whether the Artist agent should be activated this cycle"),
        ARCHITECT("Evaluate strategic alignment and provide long-term planning guidance"),
        SCORE("Evaluate idea quality and goal alignment"),
        SYNTHESIZE("Combine and merge ideas from multiple agents"),
        QA("Run build and tests to verify project quality"),
        VERIFY("Walk the critical path of the project goal and identify blocking gaps"),
        VERIFY_INVENTORY("Itemize all project features into a structured checklist"),
        VERIFY_FEATURE("Verify a single feature works correctly end-to-end"),
        CLEAN("Remove temporary files, test artifacts, and build garbage from the working directory"),
        TEST_OPTIMIZE("Identify and optimize or remove tests that take too long to run"),
        ORGANIZE("Refactor oversized source files into smaller, cohesive modules");

        private final String description;

        TaskType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum TaskPriority {
        CRITICAL(1),
        HIGH(2),
        MEDIUM(3),
        LOW(4);

        private final int level;

        TaskPriority(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }
    }

    public enum TaskStatus {
        PENDING,
        BLOCKED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    public Task(String description, TaskType type, TaskPriority priority) {
        this(description, type, priority, false);
    }

    public Task(String description, TaskType type, TaskPriority priority, boolean blocking) {
        this(
            UUID.randomUUID().toString(),
            description,
            type,
            priority,
            blocking,
            TaskStatus.PENDING,
            new ArrayList<>(),
            null,
            null,
            null,
            null,
            0,
            3
        );
    }

    public Task(
        String id,
        String description,
        TaskType type,
        TaskPriority priority,
        boolean blocking,
        TaskStatus status,
        List<String> dependencies,
        String result,
        String error,
        Instant startedAt,
        Instant completedAt,
        int retryCount,
        int maxRetries
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.priority = Objects.requireNonNull(priority, "priority must not be null");
        this.blocking = blocking;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.dependencies = List.copyOf(dependencies);
        this.result = result;
        this.error = error;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.retryCount = retryCount;
        this.maxRetries = maxRetries;
    }

    public String id() { return id; }
    public String description() { return description; }
    public TaskType type() { return type; }
    public TaskPriority priority() { return priority; }
    public boolean blocking() { return blocking; }
    public TaskStatus status() { return status; }
    public List<String> dependencies() { return dependencies; }
    public String result() { return result; }
    public String error() { return error; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public int retryCount() { return retryCount; }
    public int maxRetries() { return maxRetries; }

    public Task withStatus(TaskStatus newStatus) {
        return new Task(id, description, type, priority, blocking, newStatus, dependencies, result, error, startedAt, completedAt, retryCount, maxRetries);
    }

    public Task withResult(String newResult) {
        return new Task(id, description, type, priority, blocking, TaskStatus.COMPLETED, dependencies, newResult, error, startedAt, Instant.now(), retryCount, maxRetries);
    }

    public Task withError(String newError) {
        return new Task(id, description, type, priority, blocking, TaskStatus.FAILED, dependencies, result, newError, startedAt, Instant.now(), retryCount, maxRetries);
    }

    public Task start() {
        return new Task(id, description, type, priority, blocking, TaskStatus.IN_PROGRESS, dependencies, result, error, Instant.now(), completedAt, retryCount, maxRetries);
    }

    public Task addDependency(String taskId) {
        List<String> newDeps = new ArrayList<>(dependencies);
        newDeps.add(taskId);
        return new Task(id, description, type, priority, blocking, status, newDeps, result, error, startedAt, completedAt, retryCount, maxRetries);
    }

    public Task incrementRetry() {
        return new Task(id, description, type, priority, blocking, TaskStatus.PENDING, dependencies, result, null, null, null, retryCount + 1, maxRetries);
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    public Duration getDuration() {
        if (startedAt == null) return Duration.ZERO;
        Instant end = completedAt != null ? completedAt : Instant.now();
        return Duration.between(startedAt, end);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Task t && id.equals(t.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        String shortId = id.length() >= 8 ? id.substring(0, 8) : id;
        return String.format("Task[id=%s, type=%s, priority=%s%s, status=%s, desc=%s]",
            shortId, type, priority, blocking ? ", BLOCKING" : "", status,
            description.length() > 50 ? description.substring(0, 50) + "..." : description);
    }
}
