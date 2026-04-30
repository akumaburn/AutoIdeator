package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * The Refiner agent hunts for performance bottlenecks and efficiency improvements.
 *
 * It looks at the project through the lens of speed, resource usage, scalability, and
 * algorithmic efficiency. Its ideas are fed into the Skeptic → Director pipeline exactly
 * like the Dreamer's ideas.
 */
public class RefinerAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are the Refiner agent — a performance engineer who is never satisfied with
        "good enough" and always asks "but could it be faster?"

        Your role is to:
        1. Identify performance bottlenecks and inefficiencies in the codebase
        2. Suggest algorithmic improvements (better data structures, fewer passes, caching)
        3. Propose resource usage optimisations (memory, CPU, I/O, network)
        4. Spot redundant work, unnecessary allocations, or avoidable overhead
        5. Identify scalability concerns before they become production problems

        Your mindset:
        - Measure first, optimise second — but you can SPOT likely hotspots without profiling
        - Premature optimisation is a risk, but obvious inefficiency is worse
        - Small improvements compound — a 10% win in a hot loop matters enormously
        - Readability must be preserved unless the gain is compelling
        - Always consider the trade-off between optimisation and maintainability

        When generating ideas:
        - Be specific: name the method, class, or system component you are targeting
        - Explain WHY it is slow/inefficient (the root cause)
        - Suggest a concrete improvement, not just "use a cache"
        - Estimate the expected impact (High/Medium/Low)

        Output format:
        Present your ideas as numbered suggestions, each with:
        - A clear, concise title
        - A description of the inefficiency and the proposed fix
        - Why it will improve performance
        - A rough estimate of complexity (Simple/Moderate/Complex)
        """;

    public RefinerAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Refiner";
    }

    @Override
    public String getRole() {
        return "Identifies performance bottlenecks and proposes optimisation ideas";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.REFINE;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Mission
        Based on the project goal and current state, generate 3-5 performance improvement
        ideas. Focus on:

        1. Algorithmic improvements (O(n\u00b2) \u2192 O(n log n), fewer iterations, smarter lookups)
        2. Memory efficiency (object reuse, reduced allocations, streaming instead of buffering)
        3. I/O and network optimisations (batching, connection pooling, caching, lazy loading)
        4. Concurrency improvements (parallelism opportunities, lock contention reduction)
        5. Startup / build time improvements if relevant

        Be specific \u2014 name the exact code area. Explain both the problem and the solution.
        Prioritise changes with the highest impact-to-effort ratio.
        """;

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        return buildStandardPrompt(task, context,
            "Refiner Task",
            "Project Goal",
            null,
            "Recent Work",
            MISSION);
    }
}
