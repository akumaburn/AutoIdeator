package com.autoideator.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents an execution plan with tasks.
 */
public final class Plan {
    private final String id;
    private final String description;
    private final List<Task> tasks;
    private final int version;
    private final PlanStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final String refinementNotes;

    public enum PlanStatus {
        DRAFT,
        REFINING,
        READY,
        EXECUTING,
        COMPLETED,
        FAILED
    }

    public Plan(String description) {
        this(
            UUID.randomUUID().toString(),
            description,
            new ArrayList<>(),
            1,
            PlanStatus.DRAFT,
            Instant.now(),
            Instant.now(),
            ""
        );
    }

    public Plan(
        String id,
        String description,
        List<Task> tasks,
        int version,
        PlanStatus status,
        Instant createdAt,
        Instant updatedAt,
        String refinementNotes
    ) {
        this.id = id;
        this.description = description;
        this.tasks = new ArrayList<>(tasks);
        this.version = version;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.refinementNotes = refinementNotes;
    }

    public String id() { return id; }
    public String description() { return description; }
    public List<Task> tasks() { return Collections.unmodifiableList(tasks); }
    public int version() { return version; }
    public PlanStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public String refinementNotes() { return refinementNotes; }

    public Plan withTasks(List<Task> newTasks) {
        return new Plan(id, description, newTasks, version + 1, status, createdAt, Instant.now(), refinementNotes);
    }

    public Plan withStatus(PlanStatus newStatus) {
        return new Plan(id, description, tasks, version, newStatus, createdAt, Instant.now(), refinementNotes);
    }

    public Plan withRefinementNotes(String notes) {
        return new Plan(id, description, tasks, version + 1, status, createdAt, Instant.now(), notes);
    }

    public Plan addTask(Task task) {
        List<Task> newTasks = new ArrayList<>(tasks);
        newTasks.add(task);
        return new Plan(id, description, newTasks, version + 1, status, createdAt, Instant.now(), refinementNotes);
    }

    public Plan updateTask(int index, Task task) {
        if (index < 0 || index >= tasks.size()) {
            throw new IndexOutOfBoundsException("Task index " + index + " out of bounds for size " + tasks.size());
        }
        List<Task> newTasks = new ArrayList<>(tasks);
        newTasks.set(index, task);
        return new Plan(id, description, newTasks, version + 1, status, createdAt, Instant.now(), refinementNotes);
    }

    public List<Task> getPendingTasks() {
        return tasks.stream()
            .filter(t -> t.status() == Task.TaskStatus.PENDING)
            .toList();
    }

    public List<Task> getCompletedTasks() {
        return tasks.stream()
            .filter(t -> t.status() == Task.TaskStatus.COMPLETED)
            .toList();
    }

    public boolean isComplete() {
        return tasks.stream().allMatch(t -> t.status() == Task.TaskStatus.COMPLETED);
    }

    public double getProgress() {
        if (tasks.isEmpty()) return 0.0;
        long completed = tasks.stream().filter(t -> t.status() == Task.TaskStatus.COMPLETED).count();
        return (double) completed / tasks.size() * 100;
    }

    @Override
    public String toString() {
        return String.format("Plan[id=%s, version=%d, status=%s, tasks=%d, progress=%.1f%%]",
            id, version, status, tasks.size(), getProgress());
    }
}
