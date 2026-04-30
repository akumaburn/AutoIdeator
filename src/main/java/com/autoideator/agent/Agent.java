package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.AgentResponse;
import com.autoideator.model.Task;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Base interface for all agents in the swarm.
 */
public interface Agent {

    /**
     * Get the agent's name/identifier.
     */
    String getName();

    /**
     * Get the agent's role description.
     */
    String getRole();

    /**
     * Execute a task.
     *
     * @param task The task to execute
     * @param context The execution context
     * @return A future containing the agent response
     */
    CompletableFuture<AgentResponse> execute(Task task, ExecutionContext context);

    /**
     * Execute a task with a streaming callback.
     * The callback receives output chunks in real-time as they are produced by the LLM.
     *
     * @param task The task to execute
     * @param context The execution context
     * @param onChunk Callback invoked with each output chunk
     * @return A future containing the agent response
     */
    default CompletableFuture<AgentResponse> execute(Task task, ExecutionContext context, Consumer<String> onChunk) {
        return execute(task, context);
    }

    /**
     * Check if this agent can handle the given task type.
     *
     * @param taskType The type of task
     * @return true if this agent can handle it
     */
    boolean canHandle(Task.TaskType taskType);

    /**
     * Get the system prompt for this agent.
     */
    String getSystemPrompt();

    /**
     * Build the user prompt for a specific task.
     */
    String buildUserPrompt(Task task, ExecutionContext context);

    /**
     * Execution context passed to agents.
     */
    record ExecutionContext(
        AutoIdeatorConfig config,
        LlmInterface llm,
        String projectGoal,
        String projectContext,
        String previousResults,
        int retryAttempt
    ) {
        public ExecutionContext {
            java.util.Objects.requireNonNull(config, "config must not be null");
            java.util.Objects.requireNonNull(llm, "llm must not be null");
            projectGoal = projectGoal != null ? projectGoal : "";
            projectContext = projectContext != null ? projectContext : "";
            previousResults = previousResults != null ? previousResults : "";
        }

        public static ExecutionContext create(AutoIdeatorConfig config, LlmInterface llm) {
            return new ExecutionContext(config, llm, "", "", "", 0);
        }

        public ExecutionContext withProjectGoal(String goal) {
            return new ExecutionContext(config, llm, goal, projectContext, previousResults, retryAttempt);
        }

        public ExecutionContext withProjectContext(String context) {
            return new ExecutionContext(config, llm, projectGoal, context, previousResults, retryAttempt);
        }

        public ExecutionContext withPreviousResults(String results) {
            return new ExecutionContext(config, llm, projectGoal, projectContext, results, retryAttempt);
        }

        public ExecutionContext withRetryAttempt(int attempt) {
            return new ExecutionContext(config, llm, projectGoal, projectContext, previousResults, attempt);
        }

        public ExecutionContext withConfigAndLlm(AutoIdeatorConfig newConfig, LlmInterface newLlm) {
            return new ExecutionContext(newConfig, newLlm, projectGoal, projectContext, previousResults, retryAttempt);
        }
    }

    /**
     * Base implementation with common functionality.
     */
    abstract class BaseAgent implements Agent {
        protected final AutoIdeatorConfig config;
        protected final LlmInterface llm;

        /**
         * Maximum combined prompt length in characters (system + user).
         * Exceeding this causes "Prompt is too long" errors from LLM backends.
         * Conservative limit — leaves room for framing overhead and response tokens.
         */
        static final int MAX_PROMPT_CHARS = 170_000;

        protected BaseAgent(AutoIdeatorConfig config, LlmInterface llm) {
            this.config = config;
            this.llm = llm;
        }

        @Override
        public CompletableFuture<AgentResponse> execute(Task task, ExecutionContext context) {
            return execute(task, context, null);
        }

        @Override
        public CompletableFuture<AgentResponse> execute(Task task, ExecutionContext context, Consumer<String> onChunk) {
            String systemPrompt = getSystemPrompt();
            String userPrompt = buildUserPrompt(task, context);

            // Append retry seed nonce to perturb the prompt and break deterministic failure loops.
            // Each retry is a fresh subprocess with no memory of previous attempts.
            if (context.retryAttempt() > 0) {
                userPrompt = userPrompt + "\n\n(SEED:" + context.retryAttempt() + ")";
            }

            // Enforce prompt length limit to prevent "Prompt is too long" errors.
            // Truncate the user prompt (preserving the system prompt which is smaller
            // and critical for agent behaviour).
            int totalLength = systemPrompt.length() + userPrompt.length();
            if (totalLength > MAX_PROMPT_CHARS) {
                int allowedUserChars = MAX_PROMPT_CHARS - systemPrompt.length();
                if (allowedUserChars < 1000) {
                    // System prompt alone is near the limit — keep a minimum user prompt
                    allowedUserChars = 1000;
                }
                String marker = "\n\n[PROMPT TRUNCATED — original length: " + userPrompt.length()
                    + " chars, reduced to " + allowedUserChars + " chars to fit context window]";
                int truncateAt = Math.max(0, Math.min(userPrompt.length(), allowedUserChars - marker.length()));
                userPrompt = userPrompt.substring(0, truncateAt) + marker;
            }

            // Use the LLM from the execution context so config changes
            // (e.g., switching backend while paused) take effect on resume.
            LlmInterface effectiveLlm = context.llm();
            CompletableFuture<AgentResponse> future = (onChunk != null)
                ? effectiveLlm.sendPrompt(systemPrompt, userPrompt, onChunk)
                : effectiveLlm.sendPrompt(systemPrompt, userPrompt);

            return future
                .thenApply(response -> {
                    if (!response.success()) {
                        // Preserve partial content from the CLI subprocess for debugging
                        return AgentResponse.failureWithOutput(
                            "Agent " + getName() + " failed: " + response.error(),
                            response.content());
                    }
                    return response;
                })
                .exceptionally(ex -> {
                    Throwable cause = (ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null)
                        ? ex.getCause() : ex;
                    String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
                    return AgentResponse.failure("Agent " + getName() + " threw exception: " + message);
                });
        }

        /**
         * Builds a standard user prompt with common context sections.
         *
         * <p>Most agents share the same structure: title, optional project goal,
         * project context, optional previous results, then a mission-specific section.
         * This method handles the boilerplate; each agent supplies only its unique parts.
         *
         * @param task                   the current task
         * @param context                the execution context
         * @param taskTitle              e.g. "Dream Task", "Critique Task"
         * @param goalHeader             header for the goal section (null to skip entirely),
         *                               e.g. "Project Goal" or "PROJECT GOAL - HIGHEST PRIORITY"
         * @param goalNote               note appended after goal block (null for none),
         *                               e.g. "**CRITICAL**: All ideas MUST align..."
         * @param previousResultsHeader  header for previous results (null to skip),
         *                               e.g. "Recent Work", "Ideas to Critique"
         * @param missionText            the agent-specific mission section (including its own header)
         * @return the assembled prompt string
         */
        protected String buildStandardPrompt(Task task, ExecutionContext context,
                String taskTitle, String goalHeader, String goalNote,
                String previousResultsHeader, String missionText) {
            StringBuilder prompt = new StringBuilder();

            prompt.append("# ").append(taskTitle).append(": ").append(task.description()).append("\n\n");

            if (goalHeader != null && !context.projectGoal().isBlank()) {
                prompt.append("## ").append(goalHeader).append("\n```\n")
                      .append(context.projectGoal()).append("\n```\n\n");
                if (goalNote != null) {
                    prompt.append(goalNote).append("\n\n");
                }
            }

            if (!context.projectContext().isBlank()) {
                prompt.append("## Current Project State\n")
                      .append(context.projectContext()).append("\n\n");
            }

            if (previousResultsHeader != null && !context.previousResults().isBlank()) {
                prompt.append("## ").append(previousResultsHeader).append("\n")
                      .append(context.previousResults()).append("\n\n");
            }

            prompt.append(missionText);

            return prompt.toString();
        }
    }
}
