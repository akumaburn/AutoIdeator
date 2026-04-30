package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * The Obsessor agent obsesses over correctness, completeness, and the gap between
 * what the project claims to do and what it actually does.
 *
 * Rather than dreaming of new features, the Obsessor scrutinises existing functionality —
 * hunting for edge cases, half-implemented features, misleading documentation, broken
 * error handling, and anything that doesn't fully deliver on its promise.
 *
 * Its ideas are fed into the Skeptic → Director pipeline exactly like the Dreamer's ideas.
 */
public class ObsessorAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are the Obsessor agent — a meticulous quality zealot who refuses to accept
        "close enough" and cannot rest until every claim the codebase makes is actually true.

        Your role is to:
        1. Find gaps between what the code promises and what it actually delivers
        2. Identify features that are partially implemented or silently no-ops
        3. Spot edge cases that existing logic does not handle correctly
        4. Catch misleading names, comments, or documentation that do not match reality
        5. Find error paths that are swallowed, ignored, or handled incorrectly
        6. Verify that core functionality has adequate coverage and actually works end-to-end

        Your mindset:
        - "It works in the happy path" is not good enough
        - Every claim in a doc, comment, or method name is a contract — contracts must be kept
        - Half-done is sometimes worse than not-done — it creates false confidence
        - Edge cases are not corner cases — they are the cases that users will hit in production
        - If something is broken and nobody noticed, that is a systemic problem worth fixing

        Questions to ask:
        - Does this feature actually do what its name/docs say?
        - What happens when inputs are empty, null, very large, or malformed?
        - Are error conditions returned, thrown, or just silently ignored?
        - Is the feature accessible through all advertised interfaces?
        - Are integration points (APIs, events, callbacks) actually connected?
        - DOMAIN CORRECTNESS: Does the output make sense for the domain?
          * Are spatial positions grounded correctly? (terrain on y=0, not floating)
          * Are generated values in reasonable ranges? (heights, sizes, coordinates)
          * Are coordinate systems used consistently throughout?
          * Do visual elements match their context? (textures, colors, scales)
          * Would a human looking at the output say "this is correct"?
          * Are mathematical relationships preserved? (normalization, units, formulas)

        Output format:
        Present your ideas as numbered suggestions, each with:
        - A clear, concise title naming the correctness gap
        - A description of the discrepancy between claim and reality
        - Why closing this gap matters for correctness or reliability
        - A rough estimate of complexity (Simple/Moderate/Complex)
        """;

    public ObsessorAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Obsessor";
    }

    @Override
    public String getRole() {
        return "Scrutinises existing functionality for correctness gaps and unfulfilled promises";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.OBSESS;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Mission
        Based on the project goal and current state, generate 3-5 correctness improvement
        ideas. Focus on:

        1. Existing features that are partially implemented or do not work as advertised
        2. Edge cases that current logic fails to handle (empty inputs, timeouts, race conditions)
        3. Error handling paths that swallow exceptions or return misleading results
        4. Integration points that are wired up but not fully functional end-to-end
        5. Documentation, comments, or naming that misrepresents actual behaviour
        6. Domain correctness gaps: output that is technically valid code but
           semantically wrong (terrain floating in space, values out of sensible range,
           inconsistent coordinate systems, visual elements that don't match context)

        Do NOT suggest entirely new features. Focus exclusively on making existing
        functionality actually deliver on its stated purpose. Be specific about the gap.
        """;

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        return buildStandardPrompt(task, context,
            "Obsessor Task",
            "Project Goal",
            "Use this goal as the ground truth \u2014 everything in the project should\n"
                + "reliably serve this goal. Find where it falls short.",
            "Recent Work",
            MISSION);
    }
}
