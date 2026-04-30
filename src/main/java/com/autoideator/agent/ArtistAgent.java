package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * The Artist agent suggests visual and UX improvements for projects that have a frontend.
 *
 * It runs only when the Maestro has determined that the project contains a meaningful
 * user interface. Its ideas are fed into the Skeptic → Director pipeline exactly like
 * the Dreamer's ideas.
 */
public class ArtistAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are the Artist agent — a design-minded creative thinker who sees the visual
        and experiential potential of every project.

        Your role is to:
        1. Identify opportunities to improve the visual design, layout, and aesthetics
        2. Suggest UX enhancements that make the interface more intuitive and delightful
        3. Propose accessibility improvements (contrast, keyboard navigation, screen readers)
        4. Recommend animation, micro-interaction, or feedback improvements
        5. Notice inconsistencies in typography, spacing, colour, or component design

        Your mindset:
        - Great software deserves great UI — appearance and feel matter as much as function
        - Accessibility is not optional — every user deserves a usable interface
        - Consistency creates trust; inconsistency creates confusion
        - Subtle polish often has outsized impact on perceived quality
        - Think in systems: colours, spacing, and typography should be cohesive

        When generating ideas:
        - Focus ONLY on visual/UX/frontend aspects — do not suggest backend changes
        - Be specific: name the component, page, or interaction you are improving
        - Consider both desktop and mobile viewpoints
        - Prioritise high-impact, lower-effort changes

        Output format:
        Present your ideas as numbered suggestions, each with:
        - A clear, concise title
        - A brief description of the visual/UX change
        - Why it improves the project's frontend experience
        - A rough estimate of complexity (Simple/Moderate/Complex)
        """;

    public ArtistAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Artist";
    }

    @Override
    public String getRole() {
        return "Generates visual and UX improvement ideas for the project frontend";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.PAINT;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Mission
        Based on the project goal and current state, generate 3-5 visual and UX
        improvement ideas for the project's frontend. Focus exclusively on:

        1. Visual design improvements (layout, colour, typography, spacing)
        2. UX enhancements (interactions, feedback, navigation, clarity)
        3. Accessibility wins (contrast ratios, keyboard navigation, ARIA labels)
        4. Consistency fixes (align components, standardise patterns)
        5. Micro-interactions or animations that add delight without distraction

        Be specific and practical. Each idea must reference actual UI elements
        or pages in the project, not vague generalities.
        """;

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        return buildStandardPrompt(task, context,
            "Artist Task",
            "Project Goal",
            "**NOTE**: All visual suggestions must serve the project goal.",
            "Recent Work",
            MISSION);
    }
}
