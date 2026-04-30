package com.autoideator.llm;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.model.AgentResponse;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Interface for LLM backends.
 */
public interface LlmInterface extends AutoCloseable {

    /**
     * Send a prompt to the LLM and get a response.
     *
     * @param systemPrompt The system prompt (role instructions)
     * @param userPrompt The user prompt (actual query)
     * @return A future containing the agent response
     */
    CompletableFuture<AgentResponse> sendPrompt(String systemPrompt, String userPrompt);

    /**
     * Send a prompt with conversation history.
     *
     * @param systemPrompt The system prompt
     * @param messages List of previous messages in the conversation
     * @param userPrompt The current user prompt
     * @return A future containing the agent response
     */
    CompletableFuture<AgentResponse> sendPromptWithHistory(
        String systemPrompt,
        Iterable<Message> messages,
        String userPrompt
    );

    /**
     * Send a prompt to the LLM with a streaming callback.
     * The callback receives output chunks as they are produced, enabling
     * real-time streaming to the dashboard before the final response is ready.
     *
     * @param systemPrompt The system prompt (role instructions)
     * @param userPrompt The user prompt (actual query)
     * @param onChunk Callback invoked with each output chunk (may be called from any thread)
     * @return A future containing the agent response
     */
    default CompletableFuture<AgentResponse> sendPrompt(
            String systemPrompt, String userPrompt, Consumer<String> onChunk) {
        return sendPrompt(systemPrompt, userPrompt);
    }

    /**
     * Get the name of this LLM backend.
     */
    String getBackendName();

    /**
     * Check if this backend is available and properly configured.
     */
    boolean isAvailable();

    /**
     * Represents a message in the conversation history.
     */
    record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    /**
     * Factory method to create the appropriate LLM interface.
     */
    static LlmInterface create(AutoIdeatorConfig config) {
        String backend = config.llm().backend().toLowerCase();

        return switch (backend) {
            case "claude-cli" -> new ClaudeCliClient(config);
            case "opencode-cli" -> new OpenCodeCliClient(config);
            case "openrouter" -> new OpenRouterClient(config);
            case "custom-claude-cli" -> new CustomClaudeCliClient(config);
            case "mock" -> new MockLlmClient(config);
            default -> throw new IllegalArgumentException("Unknown LLM backend: " + backend);
        };
    }
}
