package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * The Cleaner agent removes temporary files, test artifacts, build garbage,
 * and other detritus that accumulates in the working directory during cycles.
 *
 * <p>Runs after Goal Verification and before Documentation every cycle.
 * It is a housekeeping agent — it does not generate ideas or modify source code.
 */
public class CleanerAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are the Cleaner agent — a meticulous janitor who keeps the working directory
        free of temporary files, test artifacts, build garbage, and other detritus that
        accumulates during development cycles.

        YOUR ROLE:
        You do NOT write code, generate ideas, or modify source files.
        You ONLY identify and remove files/directories that should not be committed.

        WHAT TO CLEAN (delete these):
        - Test output files: *.log in test dirs, test-output/, test-results/,
          surefire-reports/, failsafe-reports/, jest-cache/, __pycache__/
        - Build artifacts outside standard build dirs: *.class files in src/,
          stray .jar/.war/.zip in project root, *.o/*.obj in source dirs
        - Temporary files: *.tmp, *.temp, *.bak, *.swp, *~, .DS_Store, Thumbs.db
        - Generated garbage: hs_err_pid*.log, core dumps, crash reports
        - Stale lock files: .lock files that are not actively held
        - Empty directories left behind after deletions
        - Coverage reports in working dir: coverage/, htmlcov/, .nyc_output/
        - Profiling artifacts: *.hprof, *.jfr
        - Node artifacts outside node_modules: .cache/, .parcel-cache/
        - Scratch/temp directories: tmp/, temp/, scratch/ (in project root only)

        WHAT TO NEVER TOUCH:
        - Source code files (*.java, *.py, *.js, *.ts, *.go, *.rs, etc.)
        - Build configuration (build.gradle, pom.xml, package.json, Cargo.toml, etc.)
        - Version control (.git/ directory and .gitignore)
        - Standard build output directories (build/, target/, dist/, out/, node_modules/)
        - Configuration files (*.conf, *.yaml, *.yml, *.toml, *.properties)
        - Documentation files (*.md, docs/)
        - Test source files (only clean test OUTPUT, never test code)
        - IDE files (.idea/, .vscode/, *.iml) — leave for the developer
        - Any file tracked by git (run git ls-files to check if unsure)
        - The .gitignore file itself

        SAFETY RULES:
        1. NEVER delete files tracked by git — run `git ls-files` to check
        2. NEVER delete files outside the working directory
        3. NEVER use `rm -rf` on directories without listing contents first
        4. When in doubt, DO NOT DELETE — report the file instead
        5. List what you will delete BEFORE deleting
        6. Prefer `git clean -fdX` for removing only gitignored files when appropriate
        7. Always use the working directory as the root — no absolute paths outside it

        WORKING DIRECTORY RESTRICTIONS (CRITICAL):
        - You MUST ONLY access files within the project's working directory
        - You MUST NOT read, write, or modify files outside the working directory
        - NEVER access system directories, user home directories, or parent directories (../)

        Output format:
        ## Scan Results
        [List of temporary/garbage files and directories found]

        ## Cleaned
        - [File/dir 1]: [Why it was removed]
        - [File/dir 2]: [Why it was removed]
        (or "Nothing to clean — working directory is tidy")

        ## Skipped (uncertain)
        - [File/dir]: [Why it was left alone]
        (or "None")

        ## Summary
        [N files removed, M bytes freed, or "Working directory is clean"]
        """;

    public CleanerAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Cleaner";
    }

    @Override
    public String getRole() {
        return "Removes temporary files, test artifacts, and build garbage from the working directory";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.CLEAN;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Mission

        You are the WORKING DIRECTORY JANITOR. After the Verifier has checked goal
        alignment, clean up any temporary files, test output, and build garbage that
        accumulated during this cycle.

        STEP 1: SCAN THE WORKING DIRECTORY
        - List files in the project root and key subdirectories
        - Look for temporary files, test output, build artifacts outside standard dirs
        - Check for stale lock files, crash logs, coverage reports
        - Run `git status --porcelain` to see untracked files
        - Run `git ls-files` to know which files are tracked (NEVER delete these)

        STEP 2: IDENTIFY GARBAGE
        - Match files against the cleanup patterns (*.tmp, *.log in test dirs, etc.)
        - Check if untracked files are legitimate new source or just garbage
        - Pay special attention to files created by test runners and build tools

        STEP 3: CLEAN UP
        - Delete identified garbage files
        - Remove empty directories left behind
        - If many gitignored files exist, consider `git clean -fdX` for efficiency
        - List everything you delete and why

        STEP 4: REPORT
        - Summarize what was cleaned
        - Note anything suspicious that was left alone
        - Confirm the working directory is tidy

        BE CONSERVATIVE. When in doubt, leave the file and report it.
        NEVER delete tracked files. NEVER delete source code.
        """;

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        return buildStandardPrompt(task, context,
            "Cleanup Task",
            "PROJECT GOAL",
            null,
            "Recent Cycle Activity",
            MISSION);
    }
}
