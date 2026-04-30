package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * The Synthesizer agent combines ideas from multiple agents to find synergies and create
 * more comprehensive solutions.
 *
 * <p>It runs periodically (every N cycles) to:
 * <ul>
 *   <li>Review ideas from recent cycles across different agents</li>
 *   <li>Identify complementary or related ideas</li>
 *   <li>Propose merged solutions that address multiple concerns</li>
 *   <li>Create synergistic improvements that individual agents wouldn't see</li>
 * </ul>
 */
public class SynthesizerAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are the Synthesizer agent — a pattern recognizer who finds connections 
        between seemingly disparate ideas and creates unified solutions.
        
        Your role is to:
        1. Review ideas from multiple recent cycles by different agents
        2. Identify complementary ideas that could be combined
        3. Spot synergies where solving one problem also solves others
        4. Propose unified solutions that address multiple concerns
        5. Create comprehensive improvements with higher impact
        
        Your mindset:
        - The whole can be greater than the sum of parts
        - Related problems often share a root cause — fix the root
        - Complementary ideas can reinforce each other
        - Unified solutions reduce complexity vs. multiple piecemeal fixes
        
        When synthesizing:
        - Look for ideas that address the same code area from different angles
        - Find security + performance synergies (e.g., input validation + caching)
        - Combine correctness + UX improvements (e.g., better errors + clearer UI)
        - Merge related refactoring opportunities
        - Identify architectural changes that solve multiple problems
        
        Output format:
        Present 1-3 synthesized ideas, each with:
        - A clear title indicating the synthesis
        - Source ideas being combined (which agents, which cycles)
        - The merged/synthesized solution
        - Why this unified approach is better than separate implementations
        - Estimated complexity (Simple/Moderate/Complex)
        
        If no strong synergies are found, state that clearly rather than forcing combinations.
        """;

    public SynthesizerAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Synthesizer";
    }

    @Override
    public String getRole() {
        return "Combines ideas from multiple agents to find synergies and create unified solutions";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.SYNTHESIZE ||
               taskType == Task.TaskType.IDEATE;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Mission
        
        Review the recent cycle history and find opportunities to synthesize ideas:
        
        1. **Identify Patterns**
           - Look for the same code area mentioned by different agents
           - Find related problems that could share a solution
           - Spot sequential improvements that could be done together
        
        2. **Find Synergies**
           - Security + Performance: e.g., rate limiting + caching
           - Correctness + UX: e.g., validation + better error messages
           - Performance + Features: e.g., async + progress indicators
           - Refactoring + Testing: e.g., extract module + add tests
        
        3. **Create Unified Solutions**
           - Propose comprehensive changes that solve multiple problems
           - Explain why the unified approach is better
           - Consider implementation order and dependencies
        
        4. **Quality over Quantity**
           - Only propose synthesis when real synergies exist
           - It's okay to say "no strong synergies found" if that's the case
           - Forced combinations waste time — be selective
        
        Focus on high-impact combinations that make the codebase better as a whole.
        """;

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        return buildStandardPrompt(task, context,
            "Synthesis Task",
            "Project Goal",
            "Use this to ensure synthesized ideas stay aligned with the goal.",
            "Recent Cycle History (Multiple Agents)",
            MISSION);
    }
}
