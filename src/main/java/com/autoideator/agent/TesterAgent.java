package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * Agent specialized in testing.
 */
public class TesterAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are an expert test engineer. Your role is to:
        1. Design comprehensive test strategies
        2. Write unit, integration, and end-to-end tests
        3. Identify edge cases and boundary conditions
        4. Ensure adequate test coverage
        5. Create maintainable test suites

        When testing:
        - Follow the testing pyramid (more unit tests, fewer E2E)
        - Use appropriate testing patterns (AAA, given-when-then)
        - Test positive and negative cases
        - Include boundary conditions
        - Mock external dependencies
        - Make tests independent and repeatable

        Output tests with:
        - Clear test names describing the scenario
        - Arrange-Act-Assert structure
        - Appropriate assertions
        - Necessary setup and teardown
        """;

    public TesterAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Tester";
    }

    @Override
    public String getRole() {
        return "Creates and runs tests";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.TEST;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("# Testing Task: ").append(task.description()).append("\n\n");

        if (context.previousResults() != null && !context.previousResults().isBlank()) {
            prompt.append("## Code to Test\n");
            prompt.append(context.previousResults()).append("\n\n");
        }

        if (context.projectContext() != null && !context.projectContext().isBlank()) {
            prompt.append("## Project Context\n");
            prompt.append(context.projectContext()).append("\n\n");
        }

        int coverageTarget = config.agents().tester().enabled() ? 80 : 0;
        prompt.append("""
            ## Testing Requirements

            Target coverage: %d%%

            Please create tests for:

            1. **Unit Tests**
               - Test individual functions/methods
               - Cover edge cases
               - Test error conditions

            2. **Integration Tests**
               - Test component interactions
               - Test with real dependencies where appropriate

            3. **Test Cases to Include**
               - Happy path scenarios
               - Error handling
               - Boundary conditions
               - Invalid inputs
               - Concurrent access (if applicable)

            Provide complete, runnable test code.
            """.formatted(coverageTarget));

        return prompt.toString();
    }
}
