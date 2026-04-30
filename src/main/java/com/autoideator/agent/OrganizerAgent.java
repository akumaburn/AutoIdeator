package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * The Organizer agent ensures no source file exceeds a configurable token budget
 * (default: ~150 000 tokens ≈ 500 000 characters).  When oversized files are
 * detected the agent refactors them into smaller, cohesive modules so that every
 * file in the project can comfortably fit within an LLM context window.
 *
 * <p>Runs every other cycle, after Goal Verification and before Cleanup.  The
 * orchestrator pre-scans the project for oversized files and only invokes this
 * agent when at least one file exceeds the threshold.
 */
public class OrganizerAgent extends Agent.BaseAgent {

    /**
     * Approximate character threshold corresponding to ~150 000 tokens.
     * Code typically tokenises at roughly 3–4 characters per token;
     * 500 000 chars is a conservative estimate for 150K tokens.
     */
    public static final int MAX_FILE_CHARS = 500_000;

    private static final String SYSTEM_PROMPT = """
        You are the Organizer agent — a senior software architect who specialises in
        keeping individual source files small, readable, and within the limits that
        LLM-based tooling can process in a single pass.

        YOUR GOAL:
        No source file in the project should contain more than roughly 150 000 tokens
        of text (approximately 500 000 characters).  When a file exceeds this threshold
        you must refactor it into smaller, well-named modules without changing the
        project's external behaviour.

        GENERAL APPROACH:
        1. **Analyse** — read the oversized file and understand its internal structure:
           classes, functions, constants, imports.
        2. **Plan** — decide on a decomposition that maximises cohesion within each new
           file and minimises coupling between them.  Common strategies:
           - Extract inner classes / nested types into their own files.
           - Group related methods into helper/utility files.
           - Separate data-model records from business logic.
           - Move large string constants (templates, SQL, prompts) into resource files
             or dedicated constant classes.
        3. **Execute** — create the new files, move code into them, and update all
           import / require / include statements in the rest of the project so that
           nothing breaks.
        4. **Verify** — run the project build (`./gradlew build`, `npm run build`,
           `cargo build`, etc.) to confirm everything compiles and tests pass.

        RULES:
        - NEVER delete functionality — only relocate it.
        - Keep the public API of the module unchanged; callers should not need to be
          rewritten (re-exports / forwarding is acceptable when the language supports it).
        - Prefer meaningful file names that describe contents, not generic names like
          `utils2.java` or `helpers_extra.py`.
        - If the file is already well-structured but simply large, prefer extracting
          the largest independent section rather than an arbitrary split.
        - Each newly created file should be well under the threshold (aim for
          200–400 lines / 10 000–20 000 characters where practical).
        - Update import statements in ALL files that reference moved symbols.
        - After refactoring, run the build/test command for the project.
        - If the build fails, fix compilation errors before finishing.

        WORKING DIRECTORY RESTRICTIONS (CRITICAL):
        - You MUST ONLY access files within the project's working directory.
        - You MUST NOT read, write, or modify files outside the working directory.
        - NEVER access system directories, user home directories, or parent directories.

        OUTPUT FORMAT:
        ## Analysis
        [For each oversized file: path, size, key internal sections]

        ## Refactoring Plan
        [For each file: what will be extracted and where]

        ## Changes Made
        - [new-file-1]: [what it contains]
        - [new-file-2]: [what it contains]
        - [original-file]: [what remains, new size]
        - [updated imports in file-X, file-Y, ...]

        ## Build Verification
        [build/test command run and result]

        ## Summary
        [N files refactored, M new files created, all builds pass]
        """;

    private static final String MISSION = """
        ## Your Mission

        The following source files exceed the maximum size threshold of %d characters
        (~150 000 tokens).  Refactor each one into smaller, cohesive modules so that
        every file in the project fits comfortably within an LLM context window.

        OVERSIZED FILES:
        %s

        STEP 1: READ each oversized file and understand its structure.
        STEP 2: PLAN a decomposition that keeps cohesion high and coupling low.
        STEP 3: CREATE new files and MOVE code into them.
        STEP 4: UPDATE all imports / references across the entire project.
        STEP 5: RUN the project build to verify nothing is broken.
        STEP 6: If the build fails, FIX the errors before finishing.

        Remember: relocate, never delete.  The project must compile and pass tests
        after your changes.
        """;

    public OrganizerAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Organizer";
    }

    @Override
    public String getRole() {
        return "Refactors oversized source files into smaller, cohesive modules";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.ORGANIZE;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public String buildUserPrompt(Task task, ExecutionContext context) {
        // The orchestrator injects the list of oversized files into previousResults
        String missionText = String.format(MISSION, MAX_FILE_CHARS, context.previousResults());

        return buildStandardPrompt(task, context,
            "Organize Task",
            "PROJECT GOAL",
            null,
            null,       // oversized file list is already in missionText
            missionText);
    }
}
