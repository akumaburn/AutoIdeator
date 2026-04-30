package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.IdeaScore;
import com.autoideator.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Scorer agent evaluates idea quality and reshapes low-scoring ideas.
 *
 * <p>It scores ideas on:
 * <ul>
 *   <li>Goal alignment (0-10): How well does this advance the project goal?</li>
 *   <li>Novelty (0-10): Is this a new idea or a repeat?</li>
 *   <li>Feasibility (0-10): Can this be implemented given current project state?</li>
 * </ul>
 *
 * <p>Ideas below the threshold are reshaped into goal-aligned alternatives
 * rather than rejected, so cycles are never wasted.
 */
public class ScorerAgent extends Agent.BaseAgent {

    private static final Logger LOG = LoggerFactory.getLogger(ScorerAgent.class);

    private static final String SYSTEM_PROMPT = """
        You are the Scorer agent — a quality evaluator and redirector who ensures every
        cycle produces goal-aligned, implementable work.

        You are a CONVERGENCE GUIDE. Your role is to:
        1. Evaluate how well ideas align with the PROJECT GOAL
        2. Assess novelty (is this a repeat of recent work?)
        3. Judge feasibility given the current project state
        4. Provide clear reasoning for your scores
        5. PREVENT DIVERGENCE from the PROJECT GOAL
        6. When ideas fall short, RESHAPE them into goal-aligned alternatives

        CONVERGENCE RESPONSIBILITY:
        - Goal alignment is the MOST IMPORTANT score
        - Ideas with low goal alignment must be RESHAPED, not simply rejected
        - Prevent scope creep and feature bloat
        - Ensure each idea advances the project toward goal completion

        DOCUMENTATION IS NOT GROUND TRUTH:
        The README and project docs may claim features are "implemented" or "working".
        Do NOT treat these claims as verified. A feature documented as "complete" may
        be broken, missing, or non-functional. Do NOT penalize ideas that re-address
        "already documented" features — if there is reason to suspect a feature is
        broken or incomplete, ideas targeting it may have HIGH goal alignment.

        Scoring criteria:

        GOAL ALIGNMENT (0-10) - MOST IMPORTANT:
        - 9-10: Directly and critically advances the core goal
        - 7-8: Strongly supports the goal
        - 5-6: Moderately supports the goal
        - 3-4: Weakly related to the goal
        - 0-2: Tangential or misaligned with goal

        NOVELTY (0-10):
        - 9-10: Completely new, never attempted
        - 7-8: New approach to existing problem
        - 5-6: Incremental improvement on recent work
        - 3-4: Similar to recent attempts
        - 0-2: Near-duplicate of recent work

        FEASIBILITY (0-10):
        - 9-10: Straightforward, clear implementation path
        - 7-8: Achievable with moderate effort
        - 5-6: Possible but has challenges
        - 3-4: Difficult, requires significant work
        - 0-2: Infeasible or blocked
        NOTE: Penalize ideas that involve spatial, visual, physical, or mathematical
        output but lack specific domain-correctness constraints (value ranges,
        coordinate systems, visual criteria). Without these, the Coder will
        produce output that compiles but is semantically wrong.

        Output format — you MUST respond with EXACTLY this format:

        GOAL ALIGNMENT: [0-10]
        [One line explaining the score]
        [One line explaining HOW this advances the goal]

        NOVELTY: [0-10]
        [One line explaining the score]

        FEASIBILITY: [0-10]
        [One line explaining the score]

        OVERALL SCORE: [0-10]
        [One line with the weighted average]

        CONVERGENCE CHECK: [PASS/FAIL]
        [One line explaining if this advances the project toward the goal]

        VERDICT: [PROCEED / RESHAPE]
        [One line explaining why]

        RESHAPED IDEAS:
        [If VERDICT is RESHAPE, provide 3-5 numbered, goal-aligned replacement ideas
         in the same format as the original ideas (numbered list). These MUST directly
         advance the PROJECT GOAL and address the weaknesses you identified.
         If VERDICT is PROCEED, write "N/A - ideas are good as-is"]

        Do not include any other text, preamble, or markdown.

        IMPORTANT: Ideas with goal alignment < 6/10 or overall < 5/10 should be RESHAPED.
        NEVER simply reject ideas. Always provide a productive path forward.
        The goal is convergence, not gatekeeping.
        """;

    public ScorerAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Scorer";
    }

    @Override
    public String getRole() {
        return "Evaluates idea quality and reshapes low-scoring ideas";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.SCORE ||
               taskType == Task.TaskType.ANALYZE;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Task

        You are the CONVERGENCE GUIDE. Evaluate the proposed idea and either approve it
        or reshape it into something that better advances the project goal.

        STEP 1: EVALUATE GOAL ALIGNMENT (MOST IMPORTANT)
        - Read the PROJECT GOAL carefully
        - Ask: "If we implement this, are we closer to the goal?"
        - Ask: "Does this directly advance the core objective?"
        - Be strict: tangential features get lower scores
        - Score 0-10 based on how well it advances the goal
        - IMPORTANT: Do NOT assume a feature is "already done" because the README says so.
          Documented completion is unverified. Ideas targeting broken/incomplete features
          still earn high goal alignment if the feature is core to the project goal.

        STEP 2: EVALUATE NOVELTY
        - Check the recent work history
        - If similar work was done recently, score lower
        - New approaches to old problems score medium-high
        - Completely new ideas score highest
        - Score 0-10 based on novelty

        STEP 3: EVALUATE FEASIBILITY
        - Consider project state, dependencies, complexity
        - Simple changes score high
        - Complex architectural changes score lower
        - Score 0-10 based on feasibility

        STEP 4: CALCULATE OVERALL SCORE
        - Weight: Goal Alignment (50%), Feasibility (30%), Novelty (20%)
        - Calculate: (Goal * 5 + Feasibility * 3 + Novelty * 2) / 10

        STEP 5: CONVERGENCE CHECK
        - Does this advance the project toward the goal?
        - Is this the right priority right now?
        - Would this cause scope creep or feature bloat?
        - PASS if it advances the goal, FAIL if it diverges

        STEP 6: MAKE A VERDICT
        - PROCEED: Goal alignment >= 6 and overall >= 5 — ideas are good
        - RESHAPE: Goal alignment < 6 or overall < 5 or convergence FAIL — rewrite them

        STEP 7: PROVIDE RESHAPED IDEAS (if RESHAPE)
        - Write 3-5 numbered replacement ideas that DIRECTLY advance the PROJECT GOAL
        - Use the same numbered-list format as the original ideas
        - Each idea should be specific, actionable, and feasible
        - Address the weaknesses you identified in your scoring
        - Preserve any good elements from the original ideas
        - If PROCEED, write "N/A - ideas are good as-is"

        **THRESHOLD**: Ideas need at least 6/10 goal alignment and 5/10 overall to PROCEED.

        BE OBJECTIVE AND CONSISTENT. Your scores determine how ideas get implemented.
        The goal is CONVERGENCE — ensuring each cycle moves closer to goal completion.
        RESHAPE ideas that don't clearly advance the PROJECT GOAL into ones that do.
        """;

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("# Idea Scoring Task\n\n");

        if (context.projectGoal() != null && !context.projectGoal().isBlank()) {
            prompt.append("## PROJECT GOAL\n");
            prompt.append("```\n").append(context.projectGoal()).append("\n```\n\n");
            prompt.append("**CRITICAL**: Goal alignment is the most important score.\n\n");
        }

        if (context.projectContext() != null && !context.projectContext().isBlank()) {
            prompt.append("## Current Project State\n");
            prompt.append(context.projectContext()).append("\n\n");
        }

        if (context.previousResults() != null && !context.previousResults().isBlank()) {
            prompt.append("## Proposed Idea\n");
            prompt.append(context.previousResults()).append("\n\n");
        }

        prompt.append(MISSION);

        return prompt.toString();
    }

    /**
     * Parse the IdeaScore from Scorer's response using default thresholds.
     */
    public static IdeaScore parseScore(String response) {
        return parseScore(response, IdeaScore.MIN_GOAL_ALIGNMENT, IdeaScore.MIN_OVERALL_SCORE);
    }

    /**
     * Parse the IdeaScore from Scorer's response with configurable thresholds.
     *
     * <p>Extracts scores, verdict, and (when RESHAPE) the reshaped ideas block.
     *
     * @param response          raw LLM response text
     * @param minGoalAlignment  minimum goal alignment to proceed (from config)
     * @param minOverallScore   minimum overall score to proceed (from config)
     */
    public static IdeaScore parseScore(String response, int minGoalAlignment, int minOverallScore) {
        if (response == null) {
            return IdeaScore.lowScore("No response from Scorer");
        }

        int goalAlignment = 5;
        int novelty = 5;
        int feasibility = 5;
        StringBuilder reasoning = new StringBuilder();
        Boolean verdictIsReshape = null;
        boolean convergenceFail = false;
        boolean inReshapedSection = false;

        for (String line : response.lines().toList()) {
            String trimmed = line.trim();
            String upper = trimmed.toUpperCase();

            // Stop accumulating reasoning once we enter the RESHAPED IDEAS section
            if (upper.startsWith("RESHAPED IDEAS:") || upper.startsWith("RESHAPED")) {
                inReshapedSection = true;
            }

            if (upper.startsWith("GOAL ALIGNMENT:")) {
                int parsed = extractScore(trimmed);
                if (parsed >= 0) goalAlignment = parsed;
            } else if (upper.startsWith("NOVELTY:")) {
                int parsed = extractScore(trimmed);
                if (parsed >= 0) novelty = parsed;
            } else if (upper.startsWith("FEASIBILITY:")) {
                int parsed = extractScore(trimmed);
                if (parsed >= 0) feasibility = parsed;
            } else if (upper.startsWith("VERDICT:")) {
                String verdictValue = upper.substring(upper.indexOf(':') + 1).trim();
                verdictIsReshape = verdictValue.startsWith("RESHAPE");
            } else if (upper.startsWith("CONVERGENCE CHECK:")) {
                String convergenceValue = upper.substring(upper.indexOf(':') + 1).trim();
                convergenceFail = convergenceValue.startsWith("FAIL");
            } else if (upper.startsWith("OVERALL SCORE:")) {
                // Extract but we'll calculate our own
            } else if (!inReshapedSection && !trimmed.isEmpty() &&
                       !upper.startsWith("GOAL") &&
                       !upper.startsWith("NOVELTY") &&
                       !upper.startsWith("FEASIBILITY") &&
                       !upper.startsWith("OVERALL") &&
                       !upper.startsWith("VERDICT") &&
                       !upper.startsWith("RECOMMENDATION") &&
                       !upper.startsWith("CONVERGENCE") &&
                       !upper.startsWith("N/A")) {
                if (!reasoning.isEmpty()) reasoning.append(" ");
                reasoning.append(trimmed);
            }
        }

        // Weighted average: 50% goal alignment, 30% feasibility, 20% novelty (matches MISSION prompt)
        int overall = Math.round((goalAlignment * 5 + feasibility * 3 + novelty * 2) / 10.0f);
        boolean belowThreshold = goalAlignment < minGoalAlignment || overall < minOverallScore;
        // Honour explicit verdict from the LLM:
        //   RESHAPE → force belowThreshold = true
        //   PROCEED → trust it unless convergence fails
        if (verdictIsReshape != null && verdictIsReshape) {
            belowThreshold = true;
        } else if (verdictIsReshape != null && !verdictIsReshape) {
            // LLM explicitly said PROCEED — trust it unless convergence fails
            belowThreshold = convergenceFail;
        } else if (convergenceFail) {
            belowThreshold = true;
        }

        String reshapedIdeas = extractReshapedIdeas(response);

        return new IdeaScore(goalAlignment, novelty, feasibility, overall,
            reasoning.toString(), belowThreshold, reshapedIdeas);
    }

    /**
     * Extract the RESHAPED IDEAS block from the Scorer's response.
     *
     * @return the reshaped ideas text, or null if not present or marked N/A
     */
    static String extractReshapedIdeas(String response) {
        if (response == null) {
            return null;
        }

        int startIdx = -1;
        var lines = response.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().toUpperCase().startsWith("RESHAPED IDEAS:")) {
                startIdx = i;
                break;
            }
        }

        if (startIdx < 0) {
            return null;
        }

        // Check if the content after the header is just "N/A"
        String headerLine = lines.get(startIdx).trim();
        String afterColon = headerLine.substring(headerLine.indexOf(':') + 1).trim();
        if (afterColon.toUpperCase().startsWith("N/A")) {
            return null;
        }

        // Collect everything after the "RESHAPED IDEAS:" header
        StringBuilder ideas = new StringBuilder();
        if (!afterColon.isEmpty()) {
            ideas.append(afterColon);
        }
        for (int i = startIdx + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmedRaw = raw.trim();
            if (trimmedRaw.equalsIgnoreCase("N/A") || trimmedRaw.equalsIgnoreCase("N/A.")) {
                break;
            }
            if (!ideas.isEmpty()) ideas.append("\n");
            ideas.append(raw);
        }

        String result = ideas.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static int extractScore(String line) {
        try {
            String[] parts = line.split(":");
            if (parts.length >= 2) {
                String scorePart = parts[1].trim();
                for (String word : scorePart.split("[\\s/]+")) {
                    if (word.matches("10(\\.\\d+)?|\\d+(\\.\\d+)?")) {
                        int score = Math.round(Float.parseFloat(word));
                        return Math.max(0, Math.min(10, score));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse score from line: {}", line, e);
        }
        LOG.debug("No numeric score found in line: {}", line);
        return -1;
    }
}
