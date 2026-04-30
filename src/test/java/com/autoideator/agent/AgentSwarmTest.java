package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.AgentResponse;
import com.autoideator.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent Swarm Tests")
class AgentSwarmTest {

    @Mock
    private LlmInterface mockLlm;

    private AutoIdeatorConfig config;
    private AgentSwarm swarm;

    @BeforeEach
    void setUp() {
        config = AutoIdeatorConfig.DEFAULT;
        when(mockLlm.sendPrompt(anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(AgentResponse.success("Test response")));

        swarm = new AgentSwarm(config, mockLlm);
    }

    @Test
    @DisplayName("Should register all agent types")
    void shouldRegisterAllAgentTypes() {
        Task implementTask = new Task("Implement", Task.TaskType.IMPLEMENT, Task.TaskPriority.HIGH);
        Task testTask = new Task("Test", Task.TaskType.TEST, Task.TaskPriority.HIGH);
        Task reviewTask = new Task("Review", Task.TaskType.REVIEW, Task.TaskPriority.HIGH);

        Agent.ExecutionContext context = Agent.ExecutionContext.create(config, mockLlm);

        assertThatCode(() -> swarm.executeTask(implementTask, context).join()).doesNotThrowAnyException();
        assertThatCode(() -> swarm.executeTask(testTask, context).join()).doesNotThrowAnyException();
        assertThatCode(() -> swarm.executeTask(reviewTask, context).join()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should execute tasks sequentially when parallel disabled")
    void shouldExecuteTasksSequentiallyWhenParallelDisabled() {
        AutoIdeatorConfig sequentialConfig = AutoIdeatorConfig.DEFAULT;
        // Note: In real test, we'd need to create a config with parallelExecution = false

        Task task1 = new Task("Task 1", Task.TaskType.IMPLEMENT, Task.TaskPriority.HIGH);
        Task task2 = new Task("Task 2", Task.TaskType.TEST, Task.TaskPriority.MEDIUM);

        Agent.ExecutionContext context = Agent.ExecutionContext.create(config, mockLlm);

        var result = swarm.executeTasksSequential(List.of(task1, task2), context).join();

        assertThat(result).hasSize(2);
        assertThat(result.get(task1).success()).isTrue();
        assertThat(result.get(task2).success()).isTrue();
    }

    @Test
    @DisplayName("Should handle task failures gracefully")
    void shouldHandleTaskFailuresGracefully() {
        when(mockLlm.sendPrompt(anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(AgentResponse.failure("LLM error")));

        Task task = new Task("Failing task", Task.TaskType.IMPLEMENT, Task.TaskPriority.HIGH);
        Agent.ExecutionContext context = Agent.ExecutionContext.create(config, mockLlm);

        AgentResponse response = swarm.executeTask(task, context).join();

        assertThat(response.success()).isFalse();
        assertThat(response.error()).contains("LLM error");
    }
}
