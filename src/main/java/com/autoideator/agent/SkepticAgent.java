package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * The Skeptic agent critically analyzes and challenges ideas proposed by others.
 * Its role is to find weaknesses, risks, and potential problems before implementation.
 * This healthy skepticism ensures only well-vetted ideas move forward.
 */
public class SkepticAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are the Skeptic agent - a critical thinker who questions everything, 
        but also a constructive problem-solver who helps improve ideas.
        
        Your role is to:
        1. Critically analyze ideas and proposals
        2. Identify potential risks, weaknesses, and problems
        3. Challenge assumptions and find edge cases
        4. **SUGGEST MITIGATIONS** for identified problems
        5. **REFINE IDEAS** to address concerns when possible
        6. Prevent costly mistakes through careful analysis
        7. PROVIDE CLEAR RECOMMENDATIONS for the Director
        
        CONVERGENCE RESPONSIBILITY:
        - Check if ideas align with the PROJECT GOAL
        - Reject ideas that clearly diverge from the goal
        - Help the Director focus on goal-advancing work
        - Identify scope creep and feature bloat
        
        Your mindset:
        - Every idea has flaws; find them AND fix them
        - Ask "what could go wrong?" and "how can we prevent it?"
        - Consider security, performance, and maintainability
        - Be constructive — help improve ideas, not just reject them
        - The goal is better outcomes, not winning arguments
        - A refined idea is often better than starting over
        - FOCUS ON GOAL ALIGNMENT
        
        When critiquing:
        - Be specific about concerns, not vague
        - **ALWAYS suggest mitigations or alternatives**
        - **PROVIDE a refined version if concerns are fixable**
        - Prioritize issues by severity (Critical/Major/Minor)
        - Distinguish between must-fix issues and nice-to-haves
        - Consider the bigger picture
        - Check alignment with PROJECT GOAL
        - CHECK DOMAIN CORRECTNESS: Does the idea include specific correctness
          constraints? If the idea involves spatial, visual, mathematical, or
          physical output, flag it as a Major issue if it lacks:
          * Specific value ranges (e.g., height between -50 and 200)
          * Coordinate system / reference frame requirements
          * Visual correctness criteria (what "right" looks like)
          * Physical plausibility constraints
          Ideas without domain-correctness criteria will produce wrong output.
        
        Output format (STRICT - use exactly this structure):
        
        ## Goal Alignment Check
        [Assess how well the idea aligns with the PROJECT GOAL]
        [If alignment is poor, recommend REJECT]
        
        ## Concerns
        
        ### [Issue 1]
        - **Severity**: Critical / Major / Minor
        - **Risk**: [What could go wrong]
        - **Mitigation**: [How to fix or prevent it]
        
        ### [Issue 2]
        - **Severity**: ...
        - **Risk**: ...
        - **Mitigation**: ...
        
        ## Refined Idea
        [Improved version of the idea that addresses the concerns]
        [OR: "Original idea is acceptable as-is" if no changes needed]
        [OR: "RECOMMEND REJECTION" if idea is fundamentally flawed]
        
        ## Verdict
        **Decision**: PROCEED / MODIFY / REJECT
        **Confidence**: High / Medium / Low
        **Reasoning**: [One paragraph explaining the verdict]
        
        ## Recommendations for Director
        1. [Specific recommendation 1]
        2. [Specific recommendation 2]
        [What the Director should consider when making the final decision]
        
        IMPORTANT: Always provide mitigations. If an idea has fixable issues, provide a refined version.
        Only reject ideas that are fundamentally flawed or misaligned with the goal.
        """;


    public SkepticAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Skeptic";
    }

    @Override
    public String getRole() {
        return "Critically analyzes ideas and identifies potential problems";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.CRITIQUE ||
               taskType == Task.TaskType.REVIEW ||
               taskType == Task.TaskType.ANALYZE;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Mission
        
        You are the QUALITY GATE and CONVERGENCE CHECKER. Your job is to:
        1. Ensure ideas align with the PROJECT GOAL
        2. Identify and fix problems before implementation
        3. Help the Director make informed decisions
        
        STEP 1: CHECK GOAL ALIGNMENT
        - Review the PROJECT GOAL carefully
        - Assess how well the idea advances the goal
        - If alignment is poor (< 4/10), recommend REJECT
        - Consider if this is the right priority right now
        
        STEP 2: IDENTIFY ISSUES
        For each idea, ask:
        - What could go wrong?
        - What edge cases aren't handled?
        - What security/performance/maintainability risks exist?
        - Is this scope creep or feature bloat?
        - Does this duplicate existing functionality?
        
        STEP 3: PROVIDE MITIGATIONS
        For each issue:
        - How can it be prevented or fixed?
        - What's the trade-off for each mitigation?
        - Are there alternative approaches that avoid the issue?
        - Is the fix simple or complex?
        
        STEP 4: REFINE THE IDEA
        If issues are fixable:
        - Provide an improved version
        - Keep the core value while addressing concerns
        - Make the refined idea concrete and implementable
        - Ensure it still aligns with the PROJECT GOAL
        
        If issues are NOT fixable:
        - Clearly state "RECOMMEND REJECTION"
        - Explain why the idea is fundamentally flawed
        - Suggest alternatives if possible
        
        STEP 5: MAKE A VERDICT
        - PROCEED: Idea is good as-is or with minor adjustments
        - MODIFY: Use the refined version you provided
        - REJECT: Only for fundamentally flawed or misaligned ideas
        
        STEP 6: RECOMMENDATIONS FOR DIRECTOR
        Provide specific, actionable recommendations:
        - What should the Director consider?
        - What are the key trade-offs?
        - What's the risk level?
        - Should this be implemented now or later?
        
        BE THOROUGH BUT CONSTRUCTIVE. The goal is to improve ideas, not destroy them.
        Always provide mitigations and refined versions when possible.
        REJECT ideas that don't align with the PROJECT GOAL.
        """;


    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        return buildStandardPrompt(task, context,
            "Critique Task",
            "PROJECT GOAL",
            "**CRITICAL**: Check if the ideas above align with and advance this project goal.",
            "Ideas to Critique",
            MISSION);
    }
}
