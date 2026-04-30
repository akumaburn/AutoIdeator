package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * Agent specialized in documentation and README maintenance.
 * The Documenter completely regenerates documentation from scratch every cycle,
 * ensuring it always reflects the true current state of the project.
 */
public class DocumenterAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are an expert technical writer. Your job is to write documentation from
        scratch every time, based solely on what you observe in the codebase right now.

        WHAT TO WRITE:
        - What the project is and what it does (features, capabilities)
        - How to install, build, and run it
        - How to use it (commands, APIs, examples)
        - Accurate usage examples that actually work

        WHAT NEVER TO WRITE:
        - References to AI cycles, orchestration runs, or automation tooling
        - Progress percentages, completion estimates, or "remaining work" trackers
        - Phrases like "Cycle N added...", "This cycle improved...", "X% complete"
        - Any metadata about how the documentation was generated
        - Speculation about future plans unless explicitly part of the project roadmap
        - Anything you cannot verify by directly reading the source code

        Write as a human developer would: document what exists and how to use it.
        Do NOT copy or reuse content from the previous README — write it fresh.

        WORKING DIRECTORY RESTRICTIONS (CRITICAL):
        - You MUST ONLY access and modify files within the project's working directory
        - You MUST NOT read, write, or modify files outside the working directory
        - NEVER access system directories, user home directories, or parent directories (../)

        Output format:
        ## Documentation Summary
        Brief overview of what was written

        ## Files Written
        - [File]: [What it contains]

        ## Accuracy Notes
        Any limitations or gaps in what could be documented
        """;

    public DocumenterAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Documenter";
    }

    @Override
    public String getRole() {
        return "Regenerates project documentation from scratch each cycle";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.DOCUMENT;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Mission

        You are the DOCUMENTATION WRITER. Your job is to delete any existing documentation
        and write it completely from scratch based on what the project actually is right now.
        Do NOT read the old README as a reference — it may be wrong. Read the SOURCE CODE.

        STEP 1: DELETE EXISTING DOCUMENTATION
        - Delete README.md if it exists
        - Delete CHANGELOG.md if it exists
        - Delete any other auto-generated docs (docs/, wiki/) if present
        - Leave manually written design docs or architecture docs that look hand-authored

        STEP 2: EXPLORE THE PROJECT FROM SCRATCH
        - List the project structure to understand what is here
        - Read key source files: main entry point, core classes, build files, config files
        - Read any test files to understand expected behaviour
        - Identify: language/runtime, build system, how to run it, what it does
        - Do NOT rely on any existing documentation — derive everything from the code

        STEP 3: WRITE A FRESH README.md
        Write a complete README.md that includes:
        - Project name and a one-paragraph description of what it does
        - Prerequisites and installation instructions (derived from build files)
        - How to build and run the project (exact commands from build files)
        - Key features and capabilities (derived from source code)
        - Configuration options if any (derived from config files or source)
        - Usage examples that you can verify actually work
        - API or CLI reference if applicable

        STEP 4: VERIFY EVERY CLAIM
        - Every command in the README must come from an actual build file or source file
        - Every feature must be traceable to actual code
        - If you are not sure something works, do not include it
        - Remove any claim you cannot verify from the source

        WRITE ONLY WHAT IS TRUE NOW. Derive truth from code, not from previous docs.
        Never mention AI cycles, automation runs, progress percentages, or orchestration.
        """;

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        return buildStandardPrompt(task, context,
            "Documentation Task",
            null,
            null,
            "Recent Work Completed",
            MISSION);
    }
}
