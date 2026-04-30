package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * Agent specialized in planning and designing execution strategies.
 */
public class PlannerAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are an expert software architect and planner. Your role is to:
        1. Analyze requirements and constraints
        2. Design clear, actionable execution plans
        3. Break down complex tasks into manageable steps
        4. Identify dependencies and potential risks
        5. Ensure plans are complete and unambiguous

        When creating plans:
        - Each step should be atomic and testable
        - Clearly specify dependencies between steps
        - Include acceptance criteria
        - Consider edge cases and error handling
        - Follow best practices and design patterns

        Output your plans in a structured format with:
        - Task descriptions
        - Priority levels (CRITICAL, HIGH, MEDIUM, LOW)
        - Dependencies
        - Estimated complexity
        """;

    public PlannerAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Planner";
    }

    @Override
    public String getRole() {
        return "Creates and refines execution plans";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.ANALYZE ||
               taskType == Task.TaskType.DESIGN;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("# Task: ").append(task.description()).append("\n\n");
        prompt.append("## Type: ").append(task.type()).append("\n");
        prompt.append("## Priority: ").append(task.priority()).append("\n\n");

        if (context.projectContext() != null && !context.projectContext().isBlank()) {
            prompt.append("## Project Context\n");
            prompt.append(context.projectContext()).append("\n\n");
        }

        if (context.previousResults() != null && !context.previousResults().isBlank()) {
            prompt.append("## Previous Results\n");
            prompt.append(context.previousResults()).append("\n\n");
        }

        prompt.append("## Instructions\n");
        prompt.append(switch (task.type()) {
            case ANALYZE -> """
                Analyze the requirements and provide:
                1. Key components and their responsibilities
                2. Technical constraints and considerations
                3. Potential risks and mitigation strategies
                4. Recommended approach
                """;
            case DESIGN -> """
                Design the solution and provide:
                1. Architecture overview
                2. Component interactions
                3. Data flow diagrams (in text form)
                4. Interface definitions
                5. Error handling strategy
                """;
            case DOCUMENT -> """
                Create documentation including:
                1. Overview and purpose
                2. Usage examples
                3. API documentation
                4. Configuration options
                """;
            default -> "Complete the task as specified.";
        });

        return prompt.toString();
    }
}
