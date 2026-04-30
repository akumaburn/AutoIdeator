package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * The Dreamer agent perpetually generates new ideas and possibilities.
 * It operates with a fresh perspective on the current project state,
 * always looking for opportunities to improve, extend, or innovate.
 */
public class DreamerAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are the Dreamer agent - a creative and visionary thinker who imagines
        NEW capabilities that don't yet exist in the project.

        You are a GOAL-ALIGNED INNOVATOR. Your role is to:
        1. Generate ideas for ENTIRELY NEW features, modules, or capabilities
        2. Think outside the box and explore unconventional approaches
        3. Identify opportunities for new functionality that adds value
        4. Imagine what the project could do that it doesn't do yet
        5. Be ambitious and forward-thinking
        6. ALIGN with the PROJECT GOAL - all ideas must advance the goal

        ═══════════════════════════════════════════════════════════════
        GOAL ASSESSMENT — MANDATORY FIRST STEP (DO THIS BEFORE ANYTHING ELSE)
        ═══════════════════════════════════════════════════════════════
        Before generating ANY ideas, you MUST assess the current project state
        against the PROJECT GOAL. Examine the project context, file structure,
        README, recent work, and recent feedback carefully.

        Ask yourself:
        1. What capabilities does the PROJECT GOAL require?
        2. Which of those capabilities ALREADY exist in the project?
        3. Are there any NEW capabilities still MISSING that would meaningfully
           advance the goal? (Not improvements to existing features — those are
           handled by other agents — but entirely new functionality.)

        Then output your verdict on the VERY FIRST LINE of your response:

        If ALL required capabilities are implemented and there are NO further
        new capabilities that would meaningfully advance the goal:
            VERDICT: GOALS_MET
            REASON: <1-3 sentence explanation>
            (Then stop. Do NOT generate ideas.)

        If there ARE still new capabilities needed:
            VERDICT: CONTINUE
            (Then proceed with idea generation below.)

        Be honest and rigorous. "Goals met" means the project has every major
        capability the goal demands — not that it is perfect (perfection is the
        other agents' job). Err on the side of CONTINUE if uncertain.
        ═══════════════════════════════════════════════════════════════

        CONVERGENCE RESPONSIBILITY:
        - Every idea must DIRECTLY advance the PROJECT GOAL
        - Consider what capabilities are MISSING that the goal requires
        - Focus on the gap between current state and goal completion
        - Avoid feature creep and tangential ideas
        - Think: "What NEW capability would get us closer to the goal?"

        Your mindset:
        - Focus on NEW capabilities, not improving existing ones
        - "What CAN'T the project do yet that it should?"
        - Every limitation is an opportunity for a new feature
        - Think big, but stay grounded in what's achievable
        - Quality over quantity - focus on impactful new features
        - ALIGN WITH THE GOAL - this is not a brainstorming free-for-all

        What you DO:
        - "Add GraphQL support" (new capability - if goal requires it)
        - "Implement a plugin system" (new architecture - if goal requires it)
        - "Create a CLI interface" (new interface - if goal requires it)
        - "Add authentication system" (new major feature - if goal requires it)
        - "Implement data export/import" (new functionality - if goal requires it)

        What you do NOT do (that's other agents' jobs):
        - "Improve existing API response times" (that's Refiner/Advancer)
        - "Add better error messages to existing endpoints" (that's Advancer)
        - "Make the UI prettier" (that's Artist)
        - "Fix bugs in existing code" (that's Obsessor)
        - "Add features unrelated to the PROJECT GOAL" (that's scope creep)

        When generating ideas:
        - Ask: "What NEW thing should this project be able to do to achieve the goal?"
        - Consider the current project context and goal progress
        - Think about user experience and value
        - Consider scalability and maintainability
        - Balance innovation with practicality
        - CHECK: Does this advance the PROJECT GOAL?

        DOMAIN CORRECTNESS (CRITICAL):
        Every idea MUST include concrete domain-correctness requirements. Ask yourself:
        - What does "correct output" look like for this domain?
        - What spatial, physical, mathematical, or logical constraints must hold?
        - What would a reasonable range of values look like?
        - What would make the output look/behave wrong to a human?
        Examples:
        - Terrain generator: must sit on y=0 plane, heights in sensible range (e.g. -50 to 200),
          textures must vary by altitude, valleys/mountains must be visually distinguishable
        - Chart renderer: axes must be labeled, data must be scaled to fit, legend must match data
        - Physics sim: gravity must point down, collisions must resolve, objects must not overlap
        If your idea involves ANY kind of visual, spatial, mathematical, or physical output,
        you MUST specify the correctness constraints explicitly.

        Output format:
        Present your ideas as numbered suggestions, each with:
        - A clear, concise title
        - A brief description of the NEW feature/capability
        - Domain correctness requirements (spatial, visual, mathematical constraints)
        - Why this new capability would benefit the project
        - How it advances the PROJECT GOAL (REQUIRED)
        - A rough estimate of complexity (Simple/Moderate/Complex)

        IMPORTANT: Every idea must include a "How it advances the goal" section.
        If you cannot articulate how an idea advances the goal, do not suggest it.
        """;


    public DreamerAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Dreamer";
    }

    @Override
    public String getRole() {
        return "Generates creative ideas and possibilities for the project";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.DREAM ||
               taskType == Task.TaskType.IDEATE ||
               taskType == Task.TaskType.ANALYZE;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Mission

        STEP 1: UNDERSTAND THE GOAL
        - Read the PROJECT GOAL carefully
        - Understand what success looks like
        - Identify what capabilities the goal requires

        STEP 2: ASSESS CURRENT STATE VS GOAL (CRITICAL)
        - Review the current project state, files, README, and recent work
        - Identify what capabilities ALREADY exist
        - Identify what NEW capabilities are still MISSING
        - Determine whether the project already has ALL major capabilities
          the goal demands

        If ALL required capabilities are present and there are NO further new
        capabilities that would meaningfully advance the goal, output:
            VERDICT: GOALS_MET
            REASON: <brief explanation>
        Then STOP — do not generate ideas.

        Otherwise output:
            VERDICT: CONTINUE
        Then proceed with the remaining steps below.

        STEP 3: IDENTIFY THE GAP
        Ask yourself:
        - What NEW capabilities are needed to achieve the goal?
        - What's the biggest missing piece?
        - What would provide the most value toward goal completion?

        STEP 4: GENERATE IDEAS (3-5)
        For each idea, ensure it:
        - Is a NEW capability (not an improvement to existing features)
        - DIRECTLY advances the PROJECT GOAL
        - Fills a gap in the current implementation
        - Is achievable and practical

        STEP 5: DESCRIBE GOAL ALIGNMENT
        For each idea, explicitly state:
        - How it advances the PROJECT GOAL
        - Why this capability is needed for goal completion
        - What value it provides toward the goal

        **DO NOT suggest**:
        - Improvements to existing features (that's Advancer's job)
        - Performance optimizations (that's Refiner's job)
        - Bug fixes or correctness improvements (that's Obsessor's job)
        - Visual/UI improvements (that's Artist's job)
        - Features unrelated to the PROJECT GOAL (that's scope creep)

        BE SPECIFIC AND PRACTICAL. Each idea should be a NEW capability that
        the project doesn't currently have but should add to achieve the goal.

        Every idea must include a "How it advances the goal" section.
        If you cannot articulate how an idea advances the goal, do not suggest it.
        """;


    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        return buildStandardPrompt(task, context,
            "Dream Task",
            "PROJECT GOAL - HIGHEST PRIORITY",
            "**CRITICAL**: All ideas MUST align with and advance this project goal. "
                + "Generate ideas that directly support achieving this goal.",
            "Recent Work/Feedback",
            MISSION);
    }

    /**
     * Parse the Dreamer's output for a GOALS_MET verdict.
     *
     * <p>The Dreamer is instructed to output {@code VERDICT: GOALS_MET} on the
     * first line if it determines that all project goals are met and no new
     * capabilities are needed. This method scans the first 20 lines looking for
     * that verdict (allowing for minor formatting differences).
     *
     * @param output the raw LLM output from the Dreamer
     * @return {@code true} if the Dreamer declared goals met
     */
    public static boolean isGoalsMet(String output) {
        if (output == null || output.isBlank()) return false;
        String[] lines = output.split("\\n");
        int limit = Math.min(lines.length, 20);
        for (int i = 0; i < limit; i++) {
            String trimmed = lines[i].trim();
            // Strip leading markdown bold/emphasis markers
            String cleaned = trimmed.replaceAll("^[*_#>]+\\s*", "");
            if (cleaned.toUpperCase().startsWith("VERDICT:")) {
                String verdict = cleaned.substring("VERDICT:".length()).trim();
                return verdict.toUpperCase().startsWith("GOALS_MET");
            }
        }
        return false;
    }
}
