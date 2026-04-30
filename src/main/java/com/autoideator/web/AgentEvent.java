package com.autoideator.web;

import java.time.Instant;

/**
 * Event emitted by agents during execution.
 * Used for real-time updates to the dashboard.
 */
public record AgentEvent(
    String eventId,
    String agentName,
    EventType type,
    String phase,
    String message,
    String details,
    Instant timestamp,
    long durationMs,
    TokenUsage tokenUsage
) {
    public enum EventType {
        STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        WAITING,
        THINKING,
        TOOL_USE,
        RETRY,
        PAUSED
    }

    public record TokenUsage(
        long promptTokens,
        long completionTokens,
        long totalTokens
    ) {
        public static TokenUsage empty() {
            return new TokenUsage(0, 0, 0);
        }

        public TokenUsage add(TokenUsage other) {
            return new TokenUsage(
                this.promptTokens + other.promptTokens,
                this.completionTokens + other.completionTokens,
                this.totalTokens + other.totalTokens
            );
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String eventId = java.util.UUID.randomUUID().toString().substring(0, 8);
        private String agentName;
        private EventType type = EventType.STARTED;
        private String phase;
        private String message;
        private String details;
        private Instant timestamp = Instant.now();
        private long durationMs = 0;
        private TokenUsage tokenUsage = TokenUsage.empty();

        public Builder agentName(String name) {
            this.agentName = name;
            return this;
        }

        public Builder type(EventType type) {
            this.type = type;
            return this;
        }

        public Builder phase(String phase) {
            this.phase = phase;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder details(String details) {
            this.details = details;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = tokenUsage;
            return this;
        }

        public AgentEvent build() {
            return new AgentEvent(
                eventId, agentName, type, phase, message, details,
                timestamp, durationMs, tokenUsage
            );
        }
    }
}
