package com.autoideator.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Task Model Tests")
class TaskTest {

    @Test
    @DisplayName("Should create task with required fields")
    void shouldCreateTaskWithRequiredFields() {
        Task task = new Task("Implement feature", Task.TaskType.IMPLEMENT, Task.TaskPriority.HIGH);

        assertThat(task.description()).isEqualTo("Implement feature");
        assertThat(task.type()).isEqualTo(Task.TaskType.IMPLEMENT);
        assertThat(task.priority()).isEqualTo(Task.TaskPriority.HIGH);
        assertThat(task.status()).isEqualTo(Task.TaskStatus.PENDING);
        assertThat(task.id()).isNotBlank();
    }

    @Test
    @DisplayName("Should transition task states")
    void shouldTransitionTaskStates() {
        Task task = new Task("Test", Task.TaskType.TEST, Task.TaskPriority.MEDIUM);

        assertThat(task.status()).isEqualTo(Task.TaskStatus.PENDING);

        Task started = task.start();
        assertThat(started.status()).isEqualTo(Task.TaskStatus.IN_PROGRESS);
        assertThat(started.startedAt()).isNotNull();

        Task completed = started.withResult("Passed");
        assertThat(completed.status()).isEqualTo(Task.TaskStatus.COMPLETED);
        assertThat(completed.result()).isEqualTo("Passed");
        assertThat(completed.completedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should handle task failure")
    void shouldHandleTaskFailure() {
        Task task = new Task("Test", Task.TaskType.TEST, Task.TaskPriority.MEDIUM);
        task = task.start();

        Task failed = task.withError("Test failed");

        assertThat(failed.status()).isEqualTo(Task.TaskStatus.FAILED);
        assertThat(failed.error()).isEqualTo("Test failed");
    }

    @Test
    @DisplayName("Should manage dependencies")
    void shouldManageDependencies() {
        Task task = new Task("Task", Task.TaskType.IMPLEMENT, Task.TaskPriority.HIGH);
        String dependencyId = "dep-123";

        Task withDep = task.addDependency(dependencyId);

        assertThat(withDep.dependencies()).contains(dependencyId);
        assertThat(task.dependencies()).doesNotContain(dependencyId);
    }

    @Test
    @DisplayName("Should track retry count")
    void shouldTrackRetryCount() {
        Task task = new Task("Task", Task.TaskType.IMPLEMENT, Task.TaskPriority.HIGH);

        assertThat(task.canRetry()).isTrue();

        Task retried = task.incrementRetry().incrementRetry().incrementRetry();
        assertThat(retried.canRetry()).isFalse();
    }
}
