package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The Overseer agent sits outside the normal cycle and represents the user's voice.
 *
 * Normally it does nothing — the cycle runs Dreamer as usual.
 * When the user submits a suggestion via the dashboard, the Overseer holds it and
 * takes Dreamer's slot in the very next cycle, formalizing the suggestion into the
 * same numbered-ideas format that Dreamer produces so it flows into Skeptic → Director
 * unchanged. After contributing once it returns to idle.
 */
public class OverseerAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are the Overseer — a bridge between the human user and the AI agent pipeline.

        Your role is to take a raw user suggestion (which may be casual, incomplete, or high-level)
        and formalize it into a precise set of actionable improvement ideas that the Skeptic and
        Director agents can immediately act upon.

        The user's suggestion takes absolute priority over any autonomous ideas. Your job is to
        faithfully represent the user's intent while making it concrete enough for implementation.

        Output format — you MUST match Dreamer's output format exactly:
        Present ideas as numbered suggestions (3-5 maximum), each with:
        - A clear, concise title
        - A brief description of the idea (how it advances the user's suggestion)
        - Why it would benefit the project
        - A rough estimate of complexity (Simple/Moderate/Complex)

        Important constraints:
        - Stay faithful to the user's suggestion — do not invent unrelated ideas
        - If the suggestion naturally covers only 1-2 distinct improvements, produce only those;
          do not pad with unrelated ideas
        - Always prefix your output with: "## Overseer (User Suggestion)"
        """;

    /** Holds the pending user suggestion. Null means Overseer is idle. Shared with DashboardServer. */
    private final AtomicReference<String> pendingSuggestion;

    public OverseerAgent(AutoIdeatorConfig config, LlmInterface llm, AtomicReference<String> pendingSuggestion) {
        super(config, llm);
        this.pendingSuggestion = pendingSuggestion;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Queue a user suggestion for the next cycle.
     *
     * @param suggestion raw text from the user
     * @return false if a suggestion is already pending (caller should 409)
     */
    private static final int MAX_SUGGESTION_LENGTH = 50_000;

    public boolean submitSuggestion(String suggestion) {
        if (suggestion == null || suggestion.isBlank()) {
            throw new IllegalArgumentException("Suggestion must not be null or blank");
        }
        if (suggestion.length() > MAX_SUGGESTION_LENGTH) {
            throw new IllegalArgumentException("Suggestion exceeds maximum length of " + MAX_SUGGESTION_LENGTH + " characters");
        }
        return pendingSuggestion.compareAndSet(null, suggestion);
    }

    /** Returns true when a suggestion is waiting to be consumed. */
    public boolean hasPendingSuggestion() {
        return pendingSuggestion.get() != null;
    }

    /**
     * Atomically returns the pending suggestion and clears the slot.
     * Returns null if there is nothing pending.
     */
    public String consumeSuggestion() {
        return pendingSuggestion.getAndSet(null);
    }

    /** Returns the pending suggestion text without consuming it (for status display). */
    public String peekSuggestion() {
        return pendingSuggestion.get();
    }

    // ── Agent interface ───────────────────────────────────────────────────────

    @Override
    public String getName() {
        return "Overseer";
    }

    @Override
    public String getRole() {
        return "Formalizes user suggestions into actionable improvement ideas";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.OVERSEE;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("# Overseer Task: Formalize User Suggestion\n\n");

        if (context.projectGoal() != null && !context.projectGoal().isBlank()) {
            prompt.append("## Project Goal\n```\n")
                  .append(context.projectGoal())
                  .append("\n```\n\n");
        }

        if (context.projectContext() != null && !context.projectContext().isBlank()) {
            prompt.append("## Current Project State\n")
                  .append(context.projectContext())
                  .append("\n\n");
        }

        // The suggestion is embedded in previousResults by the orchestrator
        if (context.previousResults() != null && !context.previousResults().isBlank()) {
            prompt.append(context.previousResults()).append("\n\n");
        }

        prompt.append("""
            ## Your Task
            Formalize the user's suggestion above into 1–5 numbered improvement ideas using
            the exact Dreamer output format. The ideas must be directly derived from the
            suggestion — do not introduce unrelated features. Each idea must be specific,
            actionable, and clearly tied to the user's intent.
            """);

        return prompt.toString();
    }
}
