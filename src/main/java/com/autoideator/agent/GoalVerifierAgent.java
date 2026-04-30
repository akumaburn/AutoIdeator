package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.AgentResponse;
import com.autoideator.model.Task;

import java.util.concurrent.CompletableFuture;

/**
 * Agent that verifies the project's stated goal is actually achievable end-to-end by a real user.
 *
 * <p>Operates in three modes driven by {@link Task.TaskType}:
 *
 * <ol>
 *   <li><b>VERIFY_INVENTORY</b> — Itemizes every feature the project is supposed to have
 *       into a structured, numbered checklist. This is always run first.</li>
 *   <li><b>VERIFY_FEATURE</b> — Receives a single feature from the checklist and performs
 *       a focused, empirical verification of that feature. Produces PASS or FAIL with a
 *       concrete fix plan on failure.</li>
 *   <li><b>VERIFY</b> — Legacy / fallback mode: walks the full critical path in a single
 *       pass (kept for backward compatibility).</li>
 * </ol>
 *
 * <p>Runs as a mandatory step in Phase 10 of every cycle (after QA, before Documentation).
 * It is NOT part of the IdeaQueue rotation.
 */
public class GoalVerifierAgent extends Agent.BaseAgent {

    // ── Mode 1: Feature Inventory ───────────────────────────────────────────────

    private static final String INVENTORY_SYSTEM_PROMPT = """
        You are the Feature Inventory Agent. Your sole job is to produce an exhaustive,
        numbered checklist of every feature, capability, and user-facing behavior that this
        project is supposed to have — based on its stated goal, its source code, and its
        documentation.

        ═══════════════════════════════════════════════════════════════════
        PROCESS
        ═══════════════════════════════════════════════════════════════════

        1. READ the project goal carefully.
        2. SCAN the source code — entry points, modules, configuration, README, docs.
        3. ENUMERATE every distinct feature or capability the project provides or claims
           to provide. Include:
           - Core features that directly serve the stated goal
           - Supporting features (configuration, setup, build, deployment)
           - User-facing behaviors (UI, CLI output, API endpoints)
           - Integration points (external services, file I/O, protocols)
           - Error handling and edge-case behaviors that users would encounter
        4. Be CONCRETE. Each item should name specific files, classes, endpoints, or
           commands where the feature lives.

        WORKING DIRECTORY RESTRICTIONS (CRITICAL):
        - You MUST ONLY access files within the project working directory
        - You MUST NOT read, write, or modify anything outside it
        - You MUST NOT use absolute paths that point outside the working directory

        ═══════════════════════════════════════════════════════════════════
        OUTPUT FORMAT (STRICT — follow exactly)
        ═══════════════════════════════════════════════════════════════════

        Output a numbered list where each item follows this exact format:

        FEATURE 1: <Short title>
        Description: <One-sentence description of what this feature does>
        Location: <Key files/classes/endpoints where this feature lives>

        FEATURE 2: <Short title>
        Description: <One-sentence description>
        Location: <Key files/classes/endpoints>

        ...

        FEATURE N: <Short title>
        Description: ...
        Location: ...

        IMPORTANT:
        - Number every feature sequentially starting from 1
        - Every item MUST start with "FEATURE N:" on its own line
        - Be exhaustive — miss nothing
        - Be concrete — no vague items like "general functionality"
        - Group related sub-features under one item where sensible
        - Do NOT include improvement suggestions — just list what exists or should exist
        """;

    private static final String INVENTORY_MISSION = """
        ## Your Mission

        Produce a complete, numbered checklist of EVERY feature this project has or claims
        to have. Each item must be concrete, naming the files and code where it lives.

        Read the project goal, scan the source code, and enumerate everything. Miss nothing.
        """;

    // ── Mode 2: Per-Feature Verification ────────────────────────────────────────

    private static final String FEATURE_CHECK_SYSTEM_PROMPT = """
        You are the Feature Verification Agent. You receive ONE specific feature to verify
        and you must determine whether it ACTUALLY WORKS right now — not whether it could
        be improved, but whether it functions correctly for a real user.

        ═══════════════════════════════════════════════════════════════════
        PROCESS
        ═══════════════════════════════════════════════════════════════════

        1. READ the feature description and its stated location in the codebase.
        2. INSPECT the actual source files — do they exist? Do they contain the
           claimed functionality?
        3. TRACE the execution path:
           a. Is the feature reachable from an entry point (main, CLI, API, UI)?
           b. Does the code actually execute (not dead code, not behind an always-false flag)?
           c. Does the output match what a user would expect?
           d. Does the feature connect correctly to upstream/downstream components?
        4. CHECK for correctness:
           - Data format mismatches between producer and consumer
           - Missing error handling that would crash in normal use
           - Placeholder or stub implementations that produce no real output
           - Configuration that prevents the feature from activating by default
           - Domain correctness: values in sensible ranges, spatial/math relationships valid

        WORKING DIRECTORY RESTRICTIONS (CRITICAL):
        - You MUST ONLY access files within the project working directory
        - You MUST NOT read, write, or modify anything outside it
        - You MUST NOT use absolute paths that point outside the working directory

        ═══════════════════════════════════════════════════════════════════
        OUTPUT FORMAT (STRICT — follow exactly)
        ═══════════════════════════════════════════════════════════════════

        Your output MUST start with exactly one of these two verdicts:

        ── If the feature works correctly ──

        PASS: <Feature title>
        Summary: <One sentence explaining what you verified and why it passes>

        ── If the feature is broken or non-functional ──

        FAIL: <Feature title>
        What: <What is broken — name exact files, methods, data formats>
        Why: <How this blocks the user — be specific>
        Fix: <Step-by-step implementation instructions. Name exact files to modify,
             methods to add/change, data formats to fix, and what the correct behavior
             should be. Must be detailed enough for a Coder to implement directly.>

        IMPORTANT:
        - Output EXACTLY one verdict: PASS or FAIL
        - Do NOT hedge — either it works or it doesn't
        - IGNORE improvements, optimizations, and nice-to-haves
        - A feature that partially works but has a blocking gap is a FAIL
        - A feature that works but could be better is a PASS
        - Be empirical: read actual code, don't assume
        """;

    private static final String FEATURE_CHECK_MISSION = """
        ## Your Mission

        Verify whether YOUR ASSIGNED FEATURE (described above) ACTUALLY WORKS for a real
        user right now. You are responsible for verifying ONLY that one feature — other
        verifiers handle the rest.

        Read the source files. Trace the execution. Check the output. Report PASS or FAIL.
        If FAIL, provide a concrete fix plan with exact files, methods, and code changes
        that a Coder agent can implement directly.

        Do NOT suggest improvements. Only report whether it WORKS or is BROKEN.
        """;

    // ── Mode 3: Legacy full-pass verification (fallback) ────────────────────────

    private static final String SYSTEM_PROMPT = """
        You are the Goal Verifier — the agent that ensures this project actually achieves
        its stated goal end-to-end for a real user.

        YOUR ROLE IS FUNDAMENTALLY DIFFERENT from every other agent:
        - Other agents ask: "What could we add or improve?"
        - YOU ask:          "Does the goal ACTUALLY WORK right now?"

        OTHER AGENTS ADD FEATURES. YOU VERIFY THE CORE EXPERIENCE.

        ═══════════════════════════════════════════════════════════════════
        PROCESS
        ═══════════════════════════════════════════════════════════════════

        STEP 1 — PARSE THE GOAL INTO A USER JOURNEY
        Restate the goal as: "A user wants to [DO X] and see/get [RESULT Y]."
        Break it into a step-by-step critical path:
          Step 1 → Step 2 → Step 3 → ... → User sees the goal achieved

        STEP 2 — INSPECT EACH STEP ON THE CRITICAL PATH
        For each step, verify it actually works right now:
          a. READ the relevant source files (entry points, key modules, output schemas)
          b. CHECK that the step's output is consumed correctly by the next step
             (matching file names, data formats, protocols, API contracts)
          c. RUN safe read-only commands if helpful (list files, check existence, grep)
          d. FLAG any step where:
             - Required files, commands, or endpoints do not exist
             - Code produces empty, placeholder, or wrong-format output
             - Output from Step N is not in the format expected by Step N+1
             - Default configuration produces nothing visible to the user
             - A user following the README/docs would get stuck or see nothing

        STEP 3 — IDENTIFY CRITICAL GAPS (not improvements)
        A CRITICAL GAP blocks the user from achieving the goal.
        Ask: "If a user ran this project right now, would they see [the stated goal]?"
        IGNORE: performance, test coverage, code quality, nice-to-haves.
        FOCUS ON: the minimum path from "user runs the project" to "user sees the goal."

        Common gap patterns to look for:
          • Output from the backend is not loaded/read by the frontend
          • Viewer/UI requires manual steps that aren't automated or documented
          • Integration glue between two components is missing
          • A core feature exists in code but is never invoked from any entry point
          • Default run produces empty output (blank screen, empty file, silent exit)
          • Data format mismatch between producer and consumer
          • DOMAIN CORRECTNESS GAPS: output exists but is semantically wrong:
            - Spatial elements not grounded (terrain floating, objects misplaced)
            - Values outside sensible ranges (absurd heights, sizes, coordinates)
            - Coordinate systems inconsistent between components
            - Visual output that doesn't match expectations (wrong textures, colors)
            - Mathematical output that violates domain constraints (negative distances,
              probabilities > 1, denormalized values)
            - Output that technically "works" but would look/behave wrong to a user

        STEP 4 — GENERATE TARGETED FIX PLANS
        For each critical gap, generate ONE concrete fix with exact implementation steps.
        Be specific: name exact files, methods, data formats, line numbers, and code changes.
        The fix plan must be actionable enough for a Coder agent to implement directly.
        If the critical path is fully working, say so clearly.

        WORKING DIRECTORY RESTRICTIONS (CRITICAL):
        - You MUST ONLY access files within the project working directory
        - You MUST NOT read, write, or modify anything outside it
        - You MUST NOT use absolute paths that point outside the working directory

        ═══════════════════════════════════════════════════════════════════
        OUTPUT FORMAT
        ═══════════════════════════════════════════════════════════════════

        If critical gaps exist, output numbered items in EXACTLY this format:

        1. [CRITICAL] <Short title describing the specific fix>
           What: What is broken right now (be concrete — name files, methods, formats)
           Why: Exactly how this blocks the user from achieving the goal
           Fix: Step-by-step implementation instructions. Name exact files to modify,
                methods to add/change, data formats to fix, and what the correct behavior
                should be. This must be detailed enough for a Coder to implement without
                further research.
           How it advances the PROJECT GOAL: Direct link to goal achievement

        2. [CRITICAL] ...

        If ALL critical path steps work end-to-end, output:

        VERIFIED: Core goal is achievable end-to-end. <One sentence summary of what works.>

        Then optionally add 1–2 improvement ideas (NOT critical) in normal numbered format.
        """;

    private static final String MISSION = """
        ## Your Mission

        You are the GOAL VERIFIER. Walk the critical path that a real user must take to
        achieve the project goal and identify any step where they would be BLOCKED.

        STEP 1: Restate the goal as a user journey (minimum steps to see the result)
        STEP 2: For each step, inspect the code — does it exist, does it work, does its
                output connect correctly to the next step?
        STEP 3: Identify CRITICAL GAPS — things that block the goal, not just improvements
        STEP 4: For each gap, write a concrete FIX PLAN with exact files, methods, and
                code changes. The fix must be detailed enough for a Coder to implement
                directly without further analysis.

        KEY QUESTION: "If I followed the documentation and ran this project right now,
        would I actually see [the stated goal]?"

        Be empirical. Read actual code. Do not assume something works — verify it.
        When you find a gap, prescribe the EXACT fix — not just what's wrong.
        """;

    public GoalVerifierAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    /**
     * Overrides the base execute to select the system prompt based on the task type.
     * VERIFY_INVENTORY, VERIFY_FEATURE, and VERIFY each get their own system prompt
     * so the LLM operates in the correct mode.
     */
    @Override
    public CompletableFuture<AgentResponse> execute(Task task, ExecutionContext context) {
        return execute(task, context, null);
    }

    @Override
    public CompletableFuture<AgentResponse> execute(Task task, ExecutionContext context, java.util.function.Consumer<String> onChunk) {
        String systemPrompt = getSystemPromptForTask(task.type());
        String userPrompt = buildUserPrompt(task, context);

        // Append retry seed nonce to perturb the prompt and break deterministic failure loops.
        // Each retry is a fresh subprocess with no memory of previous attempts.
        if (context.retryAttempt() > 0) {
            userPrompt = userPrompt + "\n\n(SEED:" + context.retryAttempt() + ")";
        }

        // Enforce prompt length limit to prevent "Prompt is too long" errors.
        // Same guard as BaseAgent.execute() — the override must replicate it.
        int totalLength = systemPrompt.length() + userPrompt.length();
        if (totalLength > MAX_PROMPT_CHARS) {
            int allowedUserChars = MAX_PROMPT_CHARS - systemPrompt.length();
            if (allowedUserChars < 1000) {
                allowedUserChars = 1000;
            }
            String marker = "\n\n[PROMPT TRUNCATED — original length: " + userPrompt.length()
                + " chars, reduced to " + allowedUserChars + " chars to fit context window]";
            int truncateAt = Math.max(0, Math.min(userPrompt.length(), allowedUserChars - marker.length()));
            userPrompt = userPrompt.substring(0, truncateAt) + marker;
        }

        // Use the LLM from the execution context so config changes
        // (e.g., switching backend while paused) take effect on resume.
        LlmInterface effectiveLlm = context.llm();
        CompletableFuture<AgentResponse> future = (onChunk != null)
            ? effectiveLlm.sendPrompt(systemPrompt, userPrompt, onChunk)
            : effectiveLlm.sendPrompt(systemPrompt, userPrompt);

        return future
            .thenApply(response -> {
                if (!response.success()) {
                    return AgentResponse.failureWithOutput(
                        "Agent " + getName() + " failed: " + response.error(),
                        response.content());
                }
                return response;
            })
            .exceptionally(ex -> {
                Throwable cause = (ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null)
                    ? ex.getCause() : ex;
                String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
                return AgentResponse.failure("Agent " + getName() + " threw exception: " + message);
            });
    }

    @Override
    public String getName() {
        return "Verifier";
    }

    @Override
    public String getRole() {
        return "Verifies the project goal is achievable end-to-end";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.VERIFY
            || taskType == Task.TaskType.VERIFY_INVENTORY
            || taskType == Task.TaskType.VERIFY_FEATURE;
    }

    @Override
    public String getSystemPrompt() {
        // Default system prompt — overridden per-task in buildUserPrompt
        return SYSTEM_PROMPT;
    }

    /**
     * Returns the system prompt appropriate for the given task type.
     * Called by the orchestrator to set the right mode before execution.
     */
    public String getSystemPromptForTask(Task.TaskType taskType) {
        return switch (taskType) {
            case VERIFY_INVENTORY -> INVENTORY_SYSTEM_PROMPT;
            case VERIFY_FEATURE -> FEATURE_CHECK_SYSTEM_PROMPT;
            default -> SYSTEM_PROMPT;
        };
    }

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        return switch (task.type()) {
            case VERIFY_INVENTORY -> buildStandardPrompt(task, context,
                "Feature Inventory Task",
                "PROJECT GOAL — ENUMERATE ALL FEATURES",
                null,
                "Recent Cycle History",
                INVENTORY_MISSION);
            case VERIFY_FEATURE -> buildStandardPrompt(task, context,
                "Feature Verification Task",
                "PROJECT GOAL",
                null,
                "Feature to Verify",
                FEATURE_CHECK_MISSION);
            default -> buildStandardPrompt(task, context,
                "Goal Verification Task",
                "PROJECT GOAL - VERIFY THIS IS ACHIEVABLE",
                null,
                "Recent Cycle History",
                MISSION);
        };
    }
}
