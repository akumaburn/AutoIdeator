package com.autoideator.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Plan Model Tests")
class PlanTest {

    @Test
    @DisplayName("Should create plan with description")
    void shouldCreatePlanWithDescription() {
        Plan plan = new Plan("Test plan");

        assertThat(plan.description()).isEqualTo("Test plan");
        assertThat(plan.version()).isEqualTo(1);
        assertThat(plan.status()).isEqualTo(Plan.PlanStatus.DRAFT);
        assertThat(plan.tasks()).isEmpty();
    }

    @Test
    @DisplayName("Should add task to plan")
    void shouldAddTaskToPlan() {
        Plan plan = new Plan("Test plan");
        Task task = new Task("Test task", Task.TaskType.IMPLEMENT, Task.TaskPriority.HIGH);

        Plan updated = plan.addTask(task);

        assertThat(updated.tasks()).hasSize(1);
        assertThat(updated.tasks().get(0)).isEqualTo(task);
        assertThat(updated.version()).isEqualTo(2);
        // Original plan unchanged (immutability)
        assertThat(plan.tasks()).isEmpty();
    }

    @Test
    @DisplayName("Should update plan status")
    void shouldUpdatePlanStatus() {
        Plan plan = new Plan("Test plan");

        Plan updated = plan.withStatus(Plan.PlanStatus.READY);

        assertThat(updated.status()).isEqualTo(Plan.PlanStatus.READY);
        assertThat(plan.status()).isEqualTo(Plan.PlanStatus.DRAFT);
    }

    @Test
    @DisplayName("Should calculate progress correctly")
    void shouldCalculateProgressCorrectly() {
        Plan plan = new Plan("Test plan");

        Task task1 = new Task("Task 1", Task.TaskType.IMPLEMENT, Task.TaskPriority.HIGH);
        Task task2 = new Task("Task 2", Task.TaskType.IMPLEMENT, Task.TaskPriority.HIGH);

        plan = plan.addTask(task1).addTask(task2);

        assertThat(plan.getProgress()).isEqualTo(0.0);

        // Complete one task
        plan = plan.updateTask(0, task1.withResult("Done"));

        assertThat(plan.getProgress()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("Should identify complete plan")
    void shouldIdentifyCompletePlan() {
        Plan plan = new Plan("Test plan");
        Task task = new Task("Task", Task.TaskType.IMPLEMENT, Task.TaskPriority.HIGH);

        plan = plan.addTask(task);
        assertThat(plan.isComplete()).isFalse();

        plan = plan.updateTask(0, task.withResult("Done"));
        assertThat(plan.isComplete()).isTrue();
    }
}
