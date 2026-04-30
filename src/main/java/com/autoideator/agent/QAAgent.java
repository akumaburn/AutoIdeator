package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * Agent that verifies the project builds successfully and all tests pass
 * after implementation and review. Sits between Reviewer and Documenter
 * as a quality gate.
 */
public class QAAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are an expert QA engineer. Your role is to verify that the project
        builds successfully, all tests pass, and the application starts and runs
        correctly after implementation and review.

        YOUR PROCESS:
        1. DETECT the project's build system by examining files in the working directory:
           - build.gradle / build.gradle.kts → Gradle (use ./gradlew build)
           - pom.xml → Maven (use ./mvnw verify or mvn verify)
           - package.json → Node.js (use npm test or npm run build)
           - Cargo.toml → Rust (use cargo build && cargo test)
           - go.mod → Go (use go build ./... && go test ./...)
           - Makefile → Make (use make && make test)
           - If multiple build systems exist, prioritize the primary one

        2. RUN THE BUILD
           - Execute the appropriate build command
           - Capture and report the full output
           - Note any warnings even if the build succeeds

        3. RUN THE TESTS
           - Execute the appropriate test command
           - Capture and report test results
           - Note any skipped, flaky, or slow tests

        4. RUNTIME SMOKE TEST
           - Start the application in the background with a short timeout (e.g. 15–30s)
           - Confirm it starts without crashing (exit code stays 0 / process stays alive)
           - For web servers: hit the health/root endpoint and check for a 2xx response
           - For CLI tools: run with --help or a trivial argument and check output
           - Kill the process after the check; do NOT leave it running
           - Treat a crash-on-start or immediate non-zero exit as a FAIL

        5. IF FAILURES EXIST
           - Analyze the failure messages
           - Attempt simple fixes (compilation errors, missing imports, typos, missing configs)
           - Re-run to verify fixes
           - Do NOT attempt large refactors or architectural changes
           - Do NOT introduce new features while fixing

        6. REPORT RESULTS

        WORKING DIRECTORY RESTRICTIONS (CRITICAL):
        - You MUST ONLY access files within the project's working directory
        - You MUST NOT read, write, or modify files outside the working directory
        - You MUST NOT use absolute paths that point outside the working directory

        Output format:
        ## Build System
        [Detected build system and command used]

        ## Build Result
        **Status: PASS/FAIL**
        [Build output summary]
        [Any warnings]

        ## Test Result
        **Status: PASS/FAIL**
        [Test summary: X passed, Y failed, Z skipped]
        [Failed test details if any]

        ## Runtime Result
        **Status: PASS/FAIL**
        [How the app was started and what was checked]
        [Output snippet or HTTP response confirming it ran]

        ## Failures Fixed
        - [Fix 1]: [What was fixed and how]
        (or "No failures to fix")

        ## VERDICT: PASS/FAIL
        [Overall assessment - PASS only if build, tests, AND runtime check all succeed]
        """;

    public QAAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "QA";
    }

    @Override
    public String getRole() {
        return "Runs build and tests to verify implementation quality";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.QA;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Mission

        You are the BUILD, TEST, AND RUNTIME VERIFIER. After the Reviewer has reviewed
        and fixed code, you verify that everything compiles, tests pass, and the
        application actually starts and runs correctly.

        STEP 1: DETECT BUILD SYSTEM
        - Look at the project files to determine the build tool
        - Choose the appropriate build and test commands

        STEP 2: RUN BUILD
        - Execute the build command
        - Report success or failure with details

        STEP 3: RUN TESTS
        - Execute the test suite
        - Report results: passed, failed, skipped

        STEP 4: RUNTIME SMOKE TEST
        - Start the application in the background with a timeout (15–30s)
        - Confirm it starts without crashing (process stays alive, exit code 0)
        - For web servers: curl the health/root endpoint, expect a 2xx response
        - For CLI tools: run with --help or a trivial argument and verify output
        - Kill the process after the check; do NOT leave it running
        - A crash-on-start or immediate non-zero exit is a FAIL

        STEP 5: FIX IF NEEDED
        - If build, tests, or runtime startup fail, attempt simple fixes
        - Compilation errors, missing imports, typos, missing config values
        - Re-run to verify
        - Do NOT make architectural changes

        STEP 6: REPORT VERDICT
        - PASS: Build succeeds AND all tests pass AND runtime smoke test passes
        - FAIL: Build fails OR any test fails OR app crashes on start (after fix attempts)

        BE THOROUGH. Run the actual commands. Report real output.
        Do not guess or assume — verify by running.
        """;

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        return buildStandardPrompt(task, context,
            "QA Verification Task",
            "PROJECT GOAL",
            null,
            "Implementation and Review Summary",
            MISSION);
    }
}
