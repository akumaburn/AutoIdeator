package com.autoideator.model;

/**
 * Represents a response from an LLM agent.
 */
public record AgentResponse(
    boolean success,
    String content,
    String error,
    int tokenUsage,
    long durationMs
) {
    public static AgentResponse success(String content) {
        return new AgentResponse(true, content, null, 0, 0);
    }

    public static AgentResponse success(String content, int tokens, long durationMs) {
        return new AgentResponse(true, content, null, tokens, durationMs);
    }

    public static AgentResponse failure(String error) {
        return new AgentResponse(false, null, error, 0, 0);
    }

    /**
     * Create a failure response that preserves the partial output captured before failure.
     * This allows downstream consumers (retry storage, dashboard) to inspect what the
     * CLI subprocess was doing when it failed.
     *
     * @param error          the error description
     * @param partialContent captured stdout/stderr from the subprocess (may be null)
     */
    public static AgentResponse failureWithOutput(String error, String partialContent) {
        return new AgentResponse(false, partialContent, error, 0, 0);
    }

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}
