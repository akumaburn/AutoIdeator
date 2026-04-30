package com.autoideator.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads configuration from HOCON files.
 */
public class ConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigLoader.class);

    private final Path configPath;

    public ConfigLoader() {
        this.configPath = null;
    }

    public ConfigLoader(Path configPath) {
        this.configPath = configPath;
    }

    public AutoIdeatorConfig load() {
        try {
            Config typesafeConfig = loadTypesafeConfig();
            return mapToConfig(typesafeConfig);
        } catch (Exception e) {
            LOG.warn("Failed to load config, using defaults: {}", e.getMessage());
            return AutoIdeatorConfig.DEFAULT;
        }
    }

    private Config loadTypesafeConfig() {
        Config config = ConfigFactory.load();

        // Try to load from application.conf in working directory
        Path localConfig = Path.of("application.conf");
        if (Files.exists(localConfig)) {
            Config local = ConfigFactory.parseFile(localConfig.toFile());
            config = local.withFallback(config);
        }

        // Custom path has highest priority (overrides local application.conf)
        if (configPath != null) {
            if (!Files.exists(configPath)) {
                LOG.warn("Custom config file not found: {}, using defaults", configPath);
            } else {
                Config customConfig = ConfigFactory.parseFile(configPath.toFile());
                config = customConfig.withFallback(config);
            }
        }

        return config.getConfig("autoideator");
    }

    private AutoIdeatorConfig mapToConfig(Config c) {
        return new AutoIdeatorConfig(
            mapLlmConfig(c.getConfig("llm")),
            mapOrchestrationConfig(c.getConfig("orchestration")),
            mapSelfImprovementConfig(c.getConfig("self-improvement")),
            mapAgentsConfig(c.getConfig("agents")),
            mapGitConfig(c.getConfig("git")),
            mapLoggingConfig(c.getConfig("logging")),
            false,
            AutoIdeatorConfig.DEFAULT.workingDir()
        );
    }

    private AutoIdeatorConfig.LlmConfig mapLlmConfig(Config c) {
        return new AutoIdeatorConfig.LlmConfig(
            c.getString("backend"),
            c.getString("model"),
            c.getDuration("timeout"),
            mapOpenRouterConfig(c.getConfig("openrouter")),
            mapCliConfig(c.getConfig("claude-cli")),
            mapCliConfig(c.getConfig("opencode-cli")),
            c.hasPath("custom-claude-cli")
                ? mapCustomClaudeCliConfig(c.getConfig("custom-claude-cli"))
                : AutoIdeatorConfig.CustomClaudeCliConfig.withDefaults()
        );
    }

    private AutoIdeatorConfig.CustomClaudeCliConfig mapCustomClaudeCliConfig(Config c) {
        String path = c.getString("path");
        String apiKey = c.hasPath("api-key") ? c.getString("api-key") : null;
        String baseUrl = c.hasPath("base-url") ? c.getString("base-url") : null;
        String model = c.hasPath("model") ? c.getString("model") : null;
        String haikuModel = c.hasPath("haiku-model") ? c.getString("haiku-model") : null;
        String sonnetModel = c.hasPath("sonnet-model") ? c.getString("sonnet-model") : null;
        String opusModel = c.hasPath("opus-model") ? c.getString("opus-model") : null;
        boolean dangerouslySkipPermissions = c.hasPath("dangerously-skip-permissions")
            && c.getBoolean("dangerously-skip-permissions");
        java.util.List<String> argsList = c.hasPath("args")
            ? c.getList("args").stream()
                .map(v -> v.unwrapped().toString())
                .toList()
            : java.util.List.of();
        // Normalize empty strings to null
        return new AutoIdeatorConfig.CustomClaudeCliConfig(
            path,
            (apiKey != null && !apiKey.isBlank()) ? apiKey : null,
            (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : null,
            (model != null && !model.isBlank()) ? model : null,
            (haikuModel != null && !haikuModel.isBlank()) ? haikuModel : null,
            (sonnetModel != null && !sonnetModel.isBlank()) ? sonnetModel : null,
            (opusModel != null && !opusModel.isBlank()) ? opusModel : null,
            dangerouslySkipPermissions,
            argsList.toArray(new String[0])
        );
    }

    private AutoIdeatorConfig.OpenRouterConfig mapOpenRouterConfig(Config c) {
        String apiKey = c.hasPath("api-key") ? c.getString("api-key") : null;
        return new AutoIdeatorConfig.OpenRouterConfig(
            apiKey,
            c.getString("base-url"),
            c.getString("model")
        );
    }

    private AutoIdeatorConfig.CliConfig mapCliConfig(Config c) {
        String path = c.getString("path");
        java.util.List<String> argsList = c.hasPath("args")
            ? c.getList("args").stream()
                .map(v -> v.unwrapped().toString())
                .toList()
            : java.util.List.of();
        return new AutoIdeatorConfig.CliConfig(path, argsList.toArray(new String[0]));
    }

    private AutoIdeatorConfig.OrchestrationConfig mapOrchestrationConfig(Config c) {
        int maxConcurrentCoders = c.hasPath("max-concurrent-coders")
            ? c.getInt("max-concurrent-coders")
            : 5;
        boolean hackerEnabled = !c.hasPath("hacker-enabled") || c.getBoolean("hacker-enabled");
        int synthesizeInterval = c.hasPath("synthesize-interval")
            ? c.getInt("synthesize-interval")
            : 5;
        int minGoalAlignment = c.hasPath("min-goal-alignment")
            ? c.getInt("min-goal-alignment")
            : 6;
        int minOverallScore = c.hasPath("min-overall-score")
            ? c.getInt("min-overall-score")
            : 5;

        AutoIdeatorConfig.IdeaQueueWeights weights = AutoIdeatorConfig.IdeaQueueWeights.DEFAULT;
        if (c.hasPath("idea-queue-weights")) {
            Config w = c.getConfig("idea-queue-weights");
            weights = new AutoIdeatorConfig.IdeaQueueWeights(
                w.hasPath("dreamer")   ? w.getInt("dreamer")   : weights.dreamer(),
                w.hasPath("artist")    ? w.getInt("artist")    : weights.artist(),
                w.hasPath("refiner")   ? w.getInt("refiner")   : weights.refiner(),
                w.hasPath("hacker")    ? w.getInt("hacker")    : weights.hacker(),
                w.hasPath("obsessor")  ? w.getInt("obsessor")  : weights.obsessor(),
                w.hasPath("advancer")  ? w.getInt("advancer")  : weights.advancer()
            );
        }

        boolean sandboxEnabled = !c.hasPath("sandbox-enabled") || c.getBoolean("sandbox-enabled");

        return new AutoIdeatorConfig.OrchestrationConfig(
            c.getInt("plan-refinement-cycles"),
            c.getInt("max-concurrent-agents"),
            maxConcurrentCoders,
            c.getBoolean("parallel-execution"),
            c.getDuration("commit-interval"),
            hackerEnabled,
            synthesizeInterval,
            minGoalAlignment,
            minOverallScore,
            sandboxEnabled,
            weights
        );
    }

    private AutoIdeatorConfig.SelfImprovementConfig mapSelfImprovementConfig(Config c) {
        return new AutoIdeatorConfig.SelfImprovementConfig(
            c.getBoolean("enabled"),
            c.getDuration("scan-interval"),
            c.getInt("max-improvements-per-cycle")
        );
    }

    private AutoIdeatorConfig.AgentsConfig mapAgentsConfig(Config c) {
        return new AutoIdeatorConfig.AgentsConfig(
            mapAgentConfig(c.getConfig("planner")),
            mapAgentConfig(c.getConfig("coder")),
            mapAgentConfig(c.getConfig("reviewer")),
            mapAgentConfig(c.getConfig("tester")),
            mapAgentConfig(c.getConfig("git"))
        );
    }

    private AutoIdeatorConfig.AgentConfig mapAgentConfig(Config c) {
        return new AutoIdeatorConfig.AgentConfig(
            c.getBoolean("enabled"),
            c.getInt("max-iterations")
        );
    }

    private AutoIdeatorConfig.GitConfig mapGitConfig(Config c) {
        return new AutoIdeatorConfig.GitConfig(
            c.getBoolean("auto-commit"),
            c.getString("improvement-branch-prefix"),
            c.getString("commit-format")
        );
    }

    private AutoIdeatorConfig.LoggingConfig mapLoggingConfig(Config c) {
        return new AutoIdeatorConfig.LoggingConfig(
            c.getString("level"),
            c.getString("file"),
            c.getInt("max-size"),
            c.getInt("backup-count")
        );
    }
}
