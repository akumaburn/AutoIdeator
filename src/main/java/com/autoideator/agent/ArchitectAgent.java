package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Architect agent maintains long-term vision and ensures coherent progress toward the goal.
 *
 * <p>It runs after Skeptic and before Director, providing strategic context about:
 * <ul>
 *   <li>How well the current idea aligns with long-term goals</li>
 *   <li>Dependencies and sequencing considerations</li>
 *   <li>Prioritization based on project phase and history</li>
 *   <li>Potential conflicts with previous work</li>
 * </ul>
 */
public class ArchitectAgent extends Agent.BaseAgent {

    private static final Logger LOG = LoggerFactory.getLogger(ArchitectAgent.class);

    private static final String SYSTEM_PROMPT = """
        You are the Architect agent — the strategic planner who ensures coherent, 
        long-term progress toward the project goal.
        
        You are a CONVERGENCE TRACKER. Your role is to:
        1. Evaluate strategic alignment of ideas with the project goal
        2. Identify dependencies and sequencing requirements
        3. Detect potential conflicts with previous work
        4. Recommend priority based on project phase and convergence trajectory
        5. Ensure coherent development trajectory toward goal completion

        CONVERGENCE TRACKING FRAMEWORK:
        You must assess how each idea contributes to goal completion:

        - REMAINING WORK: What major components/capabilities are still VISIBLY MISSING from the code?
        - CONVERGENCE TRAJECTORY: Are we on track, at risk, or diverging?
        - PRIORITY ALIGNMENT: Is this the most important work right now?

        IMPORTANT — DOCUMENTATION IS NOT EVIDENCE:
        README and documentation may claim features are "implemented" or "complete".
        Do NOT treat documented claims as verified. Features documented as complete
        may still be broken, missing, or non-functional. Evaluate based on what you
        can infer from code structure and recent commits, not from README descriptions.

        Your mindset:
        - Think in roadmaps, not just tasks
        - Every feature should build toward the larger vision
        - Dependencies matter — implement foundations before advanced features
        - Avoid scope creep — stay focused on the core goal
        - Balance quick wins with long-term architecture

        When evaluating ideas:
        - Consider how this fits into the bigger picture
        - Identify what must be built first (dependencies)
        - Check if this conflicts with or duplicates previous work
        - Assess if this is the right priority for the current project phase
        - Think about maintainability and future extensibility
        
        Output format — you MUST respond with EXACTLY this structure:
        
        ## Goal Progress Assessment
        **Remaining Work**: [What major capabilities are visibly missing from the codebase]
        **Trajectory**: On Track / At Risk / Diverging
        
        ## Strategic Alignment Score
        **Score**: [0-10]
        **Reasoning**: [One line explaining the score]
        
        ## Priority
        **Level**: CRITICAL / HIGH / MEDIUM / LOW
        **Reasoning**: [One line explaining the priority]
        
        ## Dependencies
        - [Dependency 1]: [Status: Met/Unmet/Partial]
        - [Dependency 2]: [Status]
        [OR: "None - no dependencies"]
        
        ## Concerns
        - [Concern 1]: [Impact: High/Medium/Low]
        - [Concern 2]: [Impact]
        [OR: "None - no strategic concerns"]
        
        ## Convergence Recommendation
        [One paragraph with strategic advice for the Director]
        [Include whether this idea should be implemented now, later, or not at all]
        [State how this advances the PROJECT GOAL]
        
        Do not include any other text, preamble, or markdown.
        """;

    public ArchitectAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Architect";
    }

    @Override
    public String getRole() {
        return "Evaluates strategic alignment and ensures coherent long-term progress";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.ARCHITECT ||
               taskType == Task.TaskType.ANALYZE ||
               taskType == Task.TaskType.DESIGN;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Task

        You are the CONVERGENCE TRACKER. Evaluate the proposed idea and assess
        how it contributes to completing the PROJECT GOAL.

        IMPORTANT: Do NOT estimate completion percentages. README and documentation
        claims of "implemented" or "complete" are unverified. Evaluate trajectory
        based on visible code structure and recent commits, not documentation.

        STEP 1: ASSESS GOAL TRAJECTORY
        Based on the current project state and project phase:
        - What major components/capabilities are visibly present in the codebase?
        - What major capabilities are clearly still missing or incomplete?
        - Are we on track, at risk, or diverging from the goal?
        
        STEP 2: EVALUATE STRATEGIC ALIGNMENT (0-10)
        How well does this idea advance the PROJECT GOAL?
        - 8-10: Directly advances core goal, high impact
        - 5-7: Supports goal but not critical
        - 3-4: Tangential or low priority
        - 0-2: Misaligned or scope creep
        
        STEP 3: DETERMINE PRIORITY
        Consider:
        - Project phase (bootstrap/early/growth/mature)
        - Dependencies on or from this work
        - Risk of not doing it now
        - Convergence trajectory (are we on track?)
        
        STEP 4: IDENTIFY DEPENDENCIES
        What must exist before or after this?
        - Technical dependencies (libraries, APIs, infrastructure)
        - Logical dependencies (foundation before advanced)
        - Check if dependencies are met, unmet, or partial
        
        STEP 5: IDENTIFY STRATEGIC CONCERNS
        Any strategic risks?
        - Scope creep
        - Architecture conflicts
        - Duplicate effort
        - Premature optimization
        - Divergence from goal
        - Missing domain-correctness requirements (if the idea involves spatial,
          visual, physical, or mathematical output, does it specify what "correct"
          looks like? Missing constraints = guaranteed wrong output)
        
        STEP 6: PROVIDE CONVERGENCE RECOMMENDATION
        Give the Director specific guidance:
        - Should this be implemented now, later, or not at all?
        - How does this advance the PROJECT GOAL?
        - What should be focused on instead?
        - Is this the highest priority remaining work?
        
        BE CONCISE AND ACTIONABLE. The Director relies on your strategic guidance 
        to make convergence decisions. Help ensure each cycle moves closer to 
        goal completion.
        """;

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("# Architect Evaluation Task\n\n");
        
        if (context.projectGoal() != null && !context.projectGoal().isBlank()) {
            prompt.append("## PROJECT GOAL (Highest Priority)\n");
            prompt.append("```\n").append(context.projectGoal()).append("\n```\n\n");
        }
        
        if (context.projectContext() != null && !context.projectContext().isBlank()) {
            prompt.append("## Current Project State\n");
            prompt.append(context.projectContext()).append("\n\n");
        }
        
        if (context.previousResults() != null && !context.previousResults().isBlank()) {
            prompt.append("## Proposed Idea and Critique\n");
            prompt.append(context.previousResults()).append("\n\n");
        }
        
        prompt.append(MISSION);
        
        return prompt.toString();
    }
    
    /**
     * Parse the strategic alignment score from Architect's response.
     *
     * <p>Matches the output format: {@code **Score**: [0-10]} under the
     * {@code ## Strategic Alignment Score} section.  Also accepts the
     * plain {@code STRATEGIC ALIGNMENT:} prefix for robustness.
     */
    public static int parseAlignment(String response) {
        if (response == null) return 5;

        boolean inAlignmentSection = false;
        for (String line : response.lines().toList()) {
            String trimmed = line.trim();
            // Strip markdown bold markers so "**Score**: 8" becomes "Score: 8"
            String stripped = trimmed.replace("*", "");
            String upper = stripped.toUpperCase();

            // Track section context — only extract score from "Strategic Alignment" section
            if (upper.startsWith("##")) {
                inAlignmentSection = upper.contains("STRATEGIC ALIGNMENT");
                // Also check if the score is on the same header line (e.g., "## Strategic Alignment: 8")
                if (inAlignmentSection && stripped.contains(":")) {
                    String afterColon = stripped.substring(stripped.indexOf(':') + 1).trim();
                    for (String part : afterColon.split("[\\s/]+")) {
                        if (part.matches("\\d+")) {
                            int score = Integer.parseInt(part);
                            return Math.max(0, Math.min(10, score));
                        }
                    }
                }
                continue;
            }

            if (upper.startsWith("STRATEGIC ALIGNMENT:")) {
                // Also accept the flat "STRATEGIC ALIGNMENT: N" format (no section header)
                String afterColon = stripped.substring(stripped.indexOf(':') + 1).trim();
                for (String part : afterColon.split("[\\s/]+")) {
                    if (part.matches("\\d+")) {
                        int score = Integer.parseInt(part);
                        return Math.max(0, Math.min(10, score));
                    }
                }
            }

            if (inAlignmentSection && upper.startsWith("SCORE:")) {
                String afterColon = stripped.substring(stripped.indexOf(':') + 1).trim();
                for (String part : afterColon.split("[\\s/]+")) {
                    if (part.matches("\\d+")) {
                        int score = Integer.parseInt(part);
                        return Math.max(0, Math.min(10, score));
                    }
                }
            }
        }
        LOG.warn("Could not parse alignment score from Architect response — defaulting to 5");
        return 5;
    }

    /**
     * Parse the priority from Architect's response.
     *
     * <p>Matches the output format: {@code **Level**: CRITICAL / HIGH / MEDIUM / LOW}
     * under the {@code ## Priority} section.  Also accepts the plain
     * {@code PRIORITY:} prefix for robustness.
     */
    public static Task.TaskPriority parsePriority(String response) {
        if (response == null) return Task.TaskPriority.MEDIUM;

        boolean inPrioritySection = false;
        for (String line : response.lines().toList()) {
            // Strip markdown bold markers so "**Level**: HIGH" becomes "Level: HIGH"
            String stripped = line.trim().replace("*", "");
            String upper = stripped.toUpperCase();

            // Track section context — only extract priority from "Priority" section
            if (upper.startsWith("##")) {
                inPrioritySection = upper.contains("PRIORITY");
                // Also check if the priority is on the same header line (e.g., "## Priority: HIGH")
                if (inPrioritySection && stripped.contains(":")) {
                    String value = upper.substring(upper.indexOf(':') + 1).trim();
                    if (!value.isEmpty()) {
                        return parsePriorityValue(value);
                    }
                }
                continue;
            }

            if (upper.startsWith("PRIORITY:")) {
                // Accept flat "PRIORITY: HIGH" format outside any section header
                String value = upper.substring(upper.indexOf(':') + 1).trim();
                return parsePriorityValue(value);
            }

            if (inPrioritySection && (upper.startsWith("LEVEL:") || upper.startsWith("PRIORITY:"))) {
                String value = upper.substring(upper.indexOf(':') + 1).trim();
                return parsePriorityValue(value);
            }
        }
        return Task.TaskPriority.MEDIUM;
    }

    private static Task.TaskPriority parsePriorityValue(String value) {
        if (value.contains("CRITICAL")) return Task.TaskPriority.CRITICAL;
        // Check MEDIUM before HIGH so "MEDIUM-HIGH" resolves to MEDIUM
        if (value.contains("MEDIUM")) return Task.TaskPriority.MEDIUM;
        if (value.contains("HIGH")) return Task.TaskPriority.HIGH;
        if (value.contains("LOW")) return Task.TaskPriority.LOW;
        return Task.TaskPriority.MEDIUM;
    }
}
