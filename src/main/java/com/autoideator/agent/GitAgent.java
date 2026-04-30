package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * Agent specialized in Git operations.
 */
public class GitAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are a Git operations specialist. Your role is to:
        1. Manage version control operations
        2. Create meaningful commit messages
        3. Handle branching and merging
        4. Resolve merge conflicts
        5. Maintain clean Git history

        When working with Git:
        - Use conventional commit messages
        - Create feature branches for new work
        - Keep commits atomic and focused
        - Write descriptive commit messages
        - Avoid committing sensitive data

        Commit message format:
        <type>: <description>

        Types: feat, fix, refactor, docs, test, chore, perf
        """;

    public GitAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Git";
    }

    @Override
    public String getRole() {
        return "Manages Git operations";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.GIT;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("# Git Task: ").append(task.description()).append("\n\n");

        if (context.previousResults() != null && !context.previousResults().isBlank()) {
            prompt.append("## Changes Made\n");
            prompt.append(context.previousResults()).append("\n\n");
        }

        prompt.append("""
            ## Instructions

            Analyze the changes and provide:

            1. **Suggested Commit Message**
               - Follow conventional commit format
               - Be descriptive but concise
               - Reference any related issues

            2. **Branch Strategy** (if applicable)
               - Suggest branch name
               - Explain branching approach

            3. **Additional Git Operations**
               - Any needed git commands
               - Potential conflicts to watch for

            Provide the commit message and any Git commands needed.
            """);

        return prompt.toString();
    }
}
