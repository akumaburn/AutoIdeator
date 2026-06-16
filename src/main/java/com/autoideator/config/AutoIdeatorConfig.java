package com.autoideator.config;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Immutable configuration for AutoIdeator.
 */
public record AutoIdeatorConfig(
    LlmConfig llm,
    OrchestrationConfig orchestration,
    SelfImprovementConfig selfImprovement,
    AgentsConfig agents,
    GitConfig git,
    LoggingConfig logging,
    boolean dryRun,
    Path workingDir
) {
    public static final AutoIdeatorConfig DEFAULT = new AutoIdeatorConfig(
        LlmConfig.DEFAULT,
        OrchestrationConfig.DEFAULT,
        SelfImprovementConfig.DEFAULT,
        AgentsConfig.DEFAULT,
        GitConfig.DEFAULT,
        LoggingConfig.DEFAULT,
        false,
        Path.of(".")
    );

    public AutoIdeatorConfig withLlmBackend(String backend) {
        return new AutoIdeatorConfig(llm.withBackend(backend), orchestration, selfImprovement, agents, git, logging, dryRun, workingDir);
    }

    public AutoIdeatorConfig withApiKey(String apiKey) {
        return new AutoIdeatorConfig(llm.withApiKey(apiKey), orchestration, selfImprovement, agents, git, logging, dryRun, workingDir);
    }

    public AutoIdeatorConfig withModel(String model) {
        return new AutoIdeatorConfig(llm.withModel(model), orchestration, selfImprovement, agents, git, logging, dryRun, workingDir);
    }

    public AutoIdeatorConfig withRefinementCycles(int cycles) {
        return new AutoIdeatorConfig(llm, orchestration.withRefinementCycles(cycles), selfImprovement, agents, git, logging, dryRun, workingDir);
    }

    public AutoIdeatorConfig withSelfImprovementEnabled(boolean enabled) {
        return new AutoIdeatorConfig(llm, orchestration, selfImprovement.withEnabled(enabled), agents, git, logging, dryRun, workingDir);
    }

    public AutoIdeatorConfig withDryRun(boolean dryRun) {
        return new AutoIdeatorConfig(llm, orchestration, selfImprovement, agents, git, logging, dryRun, workingDir);
    }

    public AutoIdeatorConfig withWorkingDir(Path workingDir) {
        return new AutoIdeatorConfig(llm, orchestration, selfImprovement, agents, git, logging, dryRun, workingDir);
    }

    public record LlmConfig(
        String backend,
        String model,
        Duration timeout,
        OpenRouterConfig openRouter,
        CliConfig claudeCli,
        CliConfig opencodeCli,
        CustomClaudeCliConfig customClaudeCli
    ) {
        public static final LlmConfig DEFAULT = new LlmConfig(
            "opencode-cli",
            "glm-5.2",
            Duration.ofMinutes(5),
            OpenRouterConfig.DEFAULT,
            CliConfig.claudeDefault(),
            CliConfig.opencodeDefault(),
            CustomClaudeCliConfig.withDefaults()
        );

        public LlmConfig withBackend(String backend) {
            return new LlmConfig(backend, model, timeout, openRouter, claudeCli, opencodeCli, customClaudeCli);
        }

        public LlmConfig withApiKey(String apiKey) {
            return new LlmConfig(backend, model, timeout, openRouter.withApiKey(apiKey), claudeCli, opencodeCli, customClaudeCli);
        }

        public LlmConfig withModel(String model) {
            return new LlmConfig(backend, model, timeout, openRouter.withModel(model), claudeCli, opencodeCli, customClaudeCli);
        }

        public LlmConfig withCustomClaudeCli(CustomClaudeCliConfig c) {
            return new LlmConfig(backend, model, timeout, openRouter, claudeCli, opencodeCli, c);
        }
    }

    public record CustomClaudeCliConfig(
        String path,
        String apiKey,
        String baseUrl,
        String model,
        String haikuModel,
        String sonnetModel,
        String opusModel,
        boolean dangerouslySkipPermissions,
        String[] args
    ) {
        public CustomClaudeCliConfig {
            args = args != null ? args.clone() : new String[0];
        }

        @Override
        public String[] args() {
            return args.clone();
        }

        /**
         * Redacts {@code apiKey} so the auto-generated record {@code toString()}
         * cannot leak credentials to logs if a future caller ever does
         * {@code LOG.info("...", config.llm().customClaudeCli())}.
         */
        @Override
        public String toString() {
            return "CustomClaudeCliConfig[path=" + path
                + ", apiKey=" + redactSecret(apiKey)
                + ", baseUrl=" + baseUrl
                + ", model=" + model
                + ", haikuModel=" + haikuModel
                + ", sonnetModel=" + sonnetModel
                + ", opusModel=" + opusModel
                + ", dangerouslySkipPermissions=" + dangerouslySkipPermissions
                + ", args=" + java.util.Arrays.toString(args) + "]";
        }

        public static CustomClaudeCliConfig withDefaults() {
            return new CustomClaudeCliConfig(
                "claude",
                null,
                "https://api.z.ai/api/anthropic",
                "glm-5.2",
                "glm-4.5-air",
                "glm-4.7",
                "glm-5.2",
                false,  // Require explicit opt-in for dangerouslySkipPermissions
                new String[0]
            );
        }
    }

    public record OpenRouterConfig(
        String apiKey,
        String baseUrl,
        String model
    ) {
        public static final OpenRouterConfig DEFAULT = new OpenRouterConfig(
            null,
            "https://openrouter.ai/api/v1",
            "anthropic/claude-3.5-sonnet"
        );

        public OpenRouterConfig withApiKey(String apiKey) {
            return new OpenRouterConfig(apiKey, baseUrl, model);
        }

        public OpenRouterConfig withModel(String model) {
            return new OpenRouterConfig(apiKey, baseUrl, model);
        }

        /**
         * Redacts {@code apiKey} so the auto-generated record {@code toString()}
         * cannot leak credentials to logs.
         */
        @Override
        public String toString() {
            return "OpenRouterConfig[apiKey=" + redactSecret(apiKey)
                + ", baseUrl=" + baseUrl
                + ", model=" + model + "]";
        }
    }

    /**
     * Returns a non-revealing placeholder for credential-bearing strings used in
     * {@link Object#toString()}. Never reveals length or any prefix/suffix —
     * accidentally pasting log output should never expose any portion of the secret.
     */
    private static String redactSecret(String secret) {
        return (secret == null || secret.isBlank()) ? "<none>" : "<redacted>";
    }

    public record CliConfig(
        String path,
        String[] args
    ) {
        public CliConfig {
            // Defensive copy to maintain immutability
            args = args != null ? args.clone() : new String[0];
        }

        // Override accessor to return defensive copy
        @Override
        public String[] args() {
            return args.clone();
        }

        public static CliConfig claudeDefault() {
            return new CliConfig("claude", new String[0]);
        }

        public static CliConfig opencodeDefault() {
            return new CliConfig("opencode", new String[0]);
        }
    }

    public record IdeaQueueWeights(
        int dreamer,
        int artist,
        int refiner,
        int hacker,
        int obsessor,
        int advancer
    ) {
        public static final IdeaQueueWeights DEFAULT = new IdeaQueueWeights(1, 2, 1, 1, 5, 5);

        private static final int MAX_WEIGHT = 100;

        public IdeaQueueWeights {
            validateWeight(dreamer,  "dreamer");
            validateWeight(artist,   "artist");
            validateWeight(refiner,  "refiner");
            validateWeight(hacker,   "hacker");
            validateWeight(obsessor, "obsessor");
            validateWeight(advancer, "advancer");
        }

        private static void validateWeight(int value, String name) {
            if (value < 1 || value > MAX_WEIGHT) {
                throw new IllegalArgumentException(
                    name + " weight must be between 1 and " + MAX_WEIGHT + ", got " + value);
            }
        }
    }

    public record OrchestrationConfig(
        int planRefinementCycles,
        int maxConcurrentAgents,
        int maxConcurrentCoders,
        boolean parallelExecution,
        Duration commitInterval,
        boolean hackerEnabled,
        int synthesizeInterval,
        int minGoalAlignment,
        int minOverallScore,
        boolean sandboxEnabled,
        IdeaQueueWeights ideaQueueWeights
    ) {
        public OrchestrationConfig {
            if (maxConcurrentCoders < 1) {
                throw new IllegalArgumentException("maxConcurrentCoders must be >= 1, got " + maxConcurrentCoders);
            }
            if (maxConcurrentAgents < 1) {
                throw new IllegalArgumentException("maxConcurrentAgents must be >= 1, got " + maxConcurrentAgents);
            }
            if (synthesizeInterval < 1) {
                throw new IllegalArgumentException("synthesizeInterval must be >= 1, got " + synthesizeInterval);
            }
            if (planRefinementCycles < 0) {
                throw new IllegalArgumentException("planRefinementCycles must be >= 0, got " + planRefinementCycles);
            }
        }

        public static final OrchestrationConfig DEFAULT = new OrchestrationConfig(
            12,
            3,
            3,
            true,
            Duration.ofMinutes(5),
            true,
            5,
            6,
            5,
            true,
            IdeaQueueWeights.DEFAULT
        );

        public OrchestrationConfig withRefinementCycles(int cycles) {
            return new OrchestrationConfig(cycles, maxConcurrentAgents, maxConcurrentCoders, parallelExecution, commitInterval, hackerEnabled, synthesizeInterval, minGoalAlignment, minOverallScore, sandboxEnabled, ideaQueueWeights);
        }

        public OrchestrationConfig withHackerEnabled(boolean enabled) {
            return new OrchestrationConfig(planRefinementCycles, maxConcurrentAgents, maxConcurrentCoders, parallelExecution, commitInterval, enabled, synthesizeInterval, minGoalAlignment, minOverallScore, sandboxEnabled, ideaQueueWeights);
        }

        public OrchestrationConfig withSandboxEnabled(boolean enabled) {
            return new OrchestrationConfig(planRefinementCycles, maxConcurrentAgents, maxConcurrentCoders, parallelExecution, commitInterval, hackerEnabled, synthesizeInterval, minGoalAlignment, minOverallScore, enabled, ideaQueueWeights);
        }

        public OrchestrationConfig withIdeaQueueWeights(IdeaQueueWeights weights) {
            return new OrchestrationConfig(planRefinementCycles, maxConcurrentAgents, maxConcurrentCoders, parallelExecution, commitInterval, hackerEnabled, synthesizeInterval, minGoalAlignment, minOverallScore, sandboxEnabled, weights);
        }
    }

    public record SelfImprovementConfig(
        boolean enabled,
        Duration scanInterval,
        int maxImprovementsPerCycle
    ) {
        public static final SelfImprovementConfig DEFAULT = new SelfImprovementConfig(
            true,
            Duration.ofMinutes(30),
            5
        );

        public SelfImprovementConfig withEnabled(boolean enabled) {
            return new SelfImprovementConfig(enabled, scanInterval, maxImprovementsPerCycle);
        }
    }

    public record AgentsConfig(
        AgentConfig planner,
        AgentConfig coder,
        AgentConfig reviewer,
        AgentConfig tester,
        AgentConfig git
    ) {
        public static final AgentsConfig DEFAULT = new AgentsConfig(
            new AgentConfig(true, 3),
            new AgentConfig(true, 2),
            new AgentConfig(true, 1),
            new AgentConfig(true, 1),
            new AgentConfig(true, 1)
        );
    }

    public record AgentConfig(
        boolean enabled,
        int maxIterations
    ) {}

    public record GitConfig(
        boolean autoCommit,
        String improvementBranchPrefix,
        String commitFormat
    ) {
        public static final GitConfig DEFAULT = new GitConfig(
            true,
            "improvement/",
            "${prefix}: ${message}"
        );
    }

    public record LoggingConfig(
        String level,
        String file,
        int maxSizeMb,
        int backupCount
    ) {
        public static final LoggingConfig DEFAULT = new LoggingConfig(
            "INFO",
            "logs/autoideator.log",
            10,
            5
        );
    }
}
