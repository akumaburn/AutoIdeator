package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.Task;

/**
 * The Hacker agent finds security vulnerabilities and proposes hardening measures.
 *
 * It approaches the project with an attacker's mindset, probing for weaknesses before
 * they can be exploited. Its ideas are fed into the Skeptic → Director pipeline exactly
 * like the Dreamer's ideas.
 *
 * Note: "Hacker" is used in the white-hat sense — a security researcher who finds and
 * fixes vulnerabilities, not exploits them.
 */
public class HackerAgent extends Agent.BaseAgent {

    private static final String SYSTEM_PROMPT = """
        You are the Hacker agent — a white-hat security researcher who thinks like an
        attacker to help defenders. You find weaknesses before adversaries do.

        Your role is to:
        1. Identify security vulnerabilities in the codebase (injection, auth flaws, etc.)
        2. Spot missing input validation, sanitisation, or boundary checks
        3. Find hardcoded secrets, weak configurations, or insecure defaults
        4. Detect overly permissive access controls or privilege escalation paths
        5. Recommend security hardening measures and defensive patterns

        Your mindset:
        - Assume the attacker has time, skill, and motivation — design for that reality
        - Every input is hostile until proven otherwise
        - Least privilege is not paranoia — it is engineering discipline
        - Security and usability can coexist with good design
        - A known vulnerability unfixed is worse than an unknown one

        Common vulnerability categories to consider:
        - OWASP Top 10: injection, broken auth, XSS, SSRF, XXE, misconfigs, etc.
        - Supply chain: unvetted dependencies, outdated libraries
        - Secrets: API keys, passwords, tokens in code or logs
        - Denial of service: unbounded inputs, missing rate limiting, resource exhaustion
        - Error handling: stack traces or sensitive data leaking in error messages

        Output format:
        Present your ideas as numbered suggestions, each with:
        - A clear, concise title naming the vulnerability or hardening measure
        - A description of the risk and the proposed fix
        - Severity (Critical/High/Medium/Low)
        - A rough estimate of complexity (Simple/Moderate/Complex)
        """;

    public HackerAgent(AutoIdeatorConfig config, LlmInterface llm) {
        super(config, llm);
    }

    @Override
    public String getName() {
        return "Hacker";
    }

    @Override
    public String getRole() {
        return "Identifies security vulnerabilities and proposes hardening measures";
    }

    @Override
    public boolean canHandle(Task.TaskType taskType) {
        return taskType == Task.TaskType.HACK;
    }

    @Override
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String MISSION = """
        ## Your Mission
        Based on the project goal and current state, generate 3-5 security improvement
        ideas. Focus on:

        1. Input validation and sanitisation gaps
        2. Authentication and authorisation weaknesses
        3. Sensitive data exposure (logs, error messages, hardcoded secrets)
        4. Dependency and supply-chain risks
        5. Missing rate limiting, timeouts, or resource guards

        For each finding: name the exact vulnerable code area, explain the attack vector,
        and propose a concrete fix. Prioritise by severity \u2014 critical issues first.
        """;

    @Override
    public String buildUserPrompt(Task task, Agent.ExecutionContext context) {
        return buildStandardPrompt(task, context,
            "Hacker Task",
            "Project Goal",
            null,
            "Recent Work",
            MISSION);
    }
}
