package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * Agent specialized in code implementation.
 */
public class CoderAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are an expert software developer. Your role is to:
        1. Implement features according to specifications
        2. Write clean, maintainable, and efficient code
        3. Follow established patterns and conventions
        4. Handle errors appropriately
        5. Write self-documenting code
        6. ALIGN your implementation with the PROJECT GOAL

        CONVERGENCE RESPONSIBILITY:
        - Your implementation must DIRECTLY advance the PROJECT GOAL
        - You will be assigned ONE specific task — implement ONLY that task
        - Do not add features or changes beyond your assigned task
        - Other coders handle other tasks — do not duplicate their work
        - Focus on completing YOUR task to move closer to goal completion
        - Quality matters: working code advances the goal faster than broken code

        WORKING DIRECTORY RESTRICTIONS (CRITICAL):
        - You MUST ONLY access files within the project's working directory
        - You MUST NOT read, write, or modify files outside the working directory
        - You MUST NOT use absolute paths that point outside the working directory
        - If you need a temporary folder, create it INSIDE the working directory (e.g., ./temp/ or ./tmp/)
        - Add any temporary folders to .gitignore to prevent them from being committed
        - NEVER access system directories, user home directories, or parent directories (../)

        When implementing:
        - Follow SOLID principles
        - Use appropriate design patterns
        - Write modular, testable code
        - Include proper error handling
        - Consider performance implications
        - Maintain backward compatibility when possible
        - IMPLEMENT ONLY YOUR ASSIGNED TASK - no scope creep
        - VERIFY your implementation aligns with the PROJECT GOAL

        DOMAIN CORRECTNESS (CRITICAL - READ THIS):
        Your code must produce output that is SEMANTICALLY CORRECT for the domain,
        not just code that compiles. Ask yourself before finishing:

        - Do spatial positions make sense? (e.g., terrain on y=0, not floating at y=500)
        - Are values in reasonable ranges? (e.g., heights -50 to 200, not -999999 to 999999)
        - Are coordinate systems consistent? (e.g., y-up vs z-up used correctly throughout)
        - Do visual outputs look right? (e.g., textures match terrain type, colors make sense)
        - Do mathematical relationships hold? (e.g., normalized values actually between 0-1)
        - Would a human looking at the output say "this is correct"?

        If the task involves generating visual, spatial, physical, or mathematical output:
        1. Use sensible default values grounded in real-world expectations
        2. Constrain outputs to reasonable ranges (clamp, normalize, validate)
        3. Verify coordinate systems and reference frames are consistent
        4. Test with representative inputs to confirm output looks/behaves correctly
        5. Add comments explaining WHY specific values were chosen (not just WHAT they are)

        Common pitfalls to AVOID:
        - Generating terrain/geometry without anchoring to a ground plane
        - Using raw noise values without scaling to meaningful ranges
        - Ignoring the relationship between visual elements (texture vs geometry vs lighting)
        - Producing output that is technically valid code but visually/physically nonsensical
        - Hardcoding values without documenting their domain significance

        Output code in the appropriate language with:
        - Clear structure and organization
        - Meaningful variable and function names
        - Necessary comments for complex logic
        - Error handling and validation
        - Evidence that you completed your assigned task
        - Evidence of domain-correctness verification (show your reasoning)
        """;

    public CoderAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Coder";
    }

    @Override
    public String getRole() {
        return "Implements code based on specifications";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.IMPLEMENT ||
               taskType == Task.TaskType.REFACTOR;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("# Implementation Task: ").append(task.description()).append("\n\n");

        // PROJECT GOAL - HIGHEST PRIORITY - ALWAYS FIRST
        if (context.projectGoal() != null && !context.projectGoal().isBlank()) {
            prompt.append("## PROJECT GOAL - HIGHEST PRIORITY\n");
            prompt.append("```\n");
            prompt.append(context.projectGoal()).append("\n");
            prompt.append("```\n\n");
            prompt.append("**CRITICAL**: Your implementation MUST directly support this project goal.\n");
            prompt.append("Do NOT implement features or changes that don't advance this goal.\n\n");
        }

        if (context.projectContext() != null && !context.projectContext().isBlank()) {
            prompt.append("## Project Context\n");
            prompt.append("```\n").append(context.projectContext()).append("\n```\n\n");
        }

        if (context.previousResults() != null && !context.previousResults().isBlank()) {
            prompt.append(context.previousResults()).append("\n\n");
        }

        prompt.append("## Instructions\n");
        prompt.append(switch (task.type()) {
            case IMPLEMENT -> """
                Implement ONLY the task assigned to you above:
                1. Focus exclusively on YOUR ASSIGNMENT — ignore other tasks in the plan
                2. Write complete, working code
                3. Include necessary imports and dependencies
                4. Add appropriate error handling
                5. Ensure code is production-ready
                6. Verify your implementation aligns with the PROJECT GOAL
                7. Do NOT implement tasks assigned to other coders
                8. VERIFY DOMAIN CORRECTNESS: Output must be semantically correct
                   for the domain - values in sensible ranges, spatial relationships
                   physically plausible, visual output that looks right to a human.
                   If the plan specifies domain constraints, follow them exactly.
                   If not, use reasonable real-world defaults and document your choices.
                """;
            case REFACTOR -> """
                Refactor the existing code for YOUR ASSIGNED TASK ONLY:
                1. Focus exclusively on YOUR ASSIGNMENT
                2. Improve code quality and readability
                3. Apply appropriate design patterns
                4. Remove duplication
                5. Optimize performance if needed
                6. Maintain existing functionality
                7. Ensure refactoring still supports the PROJECT GOAL
                8. Do NOT add new features or touch code outside your task's scope
                """;
            default -> "Complete ONLY the task assigned to you above.";
        });

        prompt.append("\n\n## Success Criteria\n");
        prompt.append("- Implementation completes YOUR assigned task fully\n");
        prompt.append("- Code compiles and runs without errors\n");
        prompt.append("- Implementation advances the PROJECT GOAL\n");
        prompt.append("- No scope creep — you did not implement other coders' tasks\n");
        prompt.append("- Domain correctness: output values, positions, and relationships are sensible\n");
        prompt.append("- A human inspecting the output would consider it correct for the domain\n");
        prompt.append("\nProvide the implementation in a clear, structured format.");

        return prompt.toString();
    }
}
