package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * The Maestro agent decides whether the Artist agent should be activated this cycle.
 *
 * It runs only when the Artist's slot comes up in the idea queue. By examining the
 * project context it determines whether a meaningful frontend exists — if not, the
 * Artist's turn is skipped and the next idea agent (Refiner) runs instead.
 *
 * Response format (first non-blank line must be one of):
 *   VERDICT: ENABLE_ARTIST
 *   VERDICT: DISABLE_ARTIST
 * followed by a REASON: line with a brief explanation.
 */
public class MaestroAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are the Maestro — the conductor of the idea-generation ensemble.

        Your sole responsibility in each invocation is to decide whether the Artist agent
        should be allowed to run this cycle. The Artist specialises in visual and UX
        improvements, so it is only useful when the project has a real frontend (HTML, CSS,
        JavaScript, a UI framework, a mobile app, etc.).

        Decision rules:
        - ENABLE_ARTIST  → the project clearly has a user-facing frontend with visual elements
        - DISABLE_ARTIST → the project is purely backend (APIs, CLI tools, libraries, daemons,
                           data pipelines, etc.) with no meaningful UI to improve visually

        Output format — you MUST respond with EXACTLY two lines and nothing else:
          VERDICT: ENABLE_ARTIST
          REASON: <one concise sentence explaining why>

        or:
          VERDICT: DISABLE_ARTIST
          REASON: <one concise sentence explaining why>

        Do not include any other text, preamble, or markdown.
        """;

    public MaestroAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Maestro";
    }

    @Override
    public String getRole() {
        return "Decides whether the Artist agent should run this cycle";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.CURATE;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Task
        Based on the project goal and current project state, decide whether the Artist agent
        should be activated this cycle. Respond with EXACTLY the two-line format:
          VERDICT: ENABLE_ARTIST  (or DISABLE_ARTIST)
          REASON: <one sentence>
        """;

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        return buildStandardPrompt(task, context,
            "Maestro Evaluation",
            "Project Goal",
            null,
            null,
            MISSION);
    }

    /**
     * Parse the agent's response to extract the Artist enable/disable decision.
     *
     * @param response raw LLM response text
     * @return {@code true} if ENABLE_ARTIST, {@code false} otherwise
     */
    public static boolean parseVerdict(String response) {
        if (response == null) return false;
        for (String line : response.lines().toList()) {
            String upper = line.trim().toUpperCase();
            if (upper.startsWith("VERDICT:")) {
                String verdict = upper.substring("VERDICT:".length()).trim();
                return verdict.equals("ENABLE_ARTIST");
            }
        }
        // Default: disable Artist if verdict is unclear
        return false;
    }
}
