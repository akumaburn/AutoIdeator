package com.autoideator.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Configuration Tests")
class ConfigTest {

    @Test
    @DisplayName("Should provide default configuration")
    void shouldProvideDefaultConfiguration() {
        AutoIdeatorConfig config = AutoIdeatorConfig.DEFAULT;

        assertThat(config).isNotNull();
        assertThat(config.llm()).isNotNull();
        assertThat(config.orchestration()).isNotNull();
        assertThat(config.selfImprovement()).isNotNull();
    }

    @Test
    @DisplayName("Should create modified config with new backend")
    void shouldCreateModifiedConfigWithNewBackend() {
        AutoIdeatorConfig config = AutoIdeatorConfig.DEFAULT;

        AutoIdeatorConfig modified = config.withLlmBackend("openrouter");

        assertThat(modified.llm().backend()).isEqualTo("openrouter");
        assertThat(config.llm().backend()).isEqualTo("opencode-cli");
    }

    @Test
    @DisplayName("Should create modified config with refinement cycles")
    void shouldCreateModifiedConfigWithRefinementCycles() {
        AutoIdeatorConfig config = AutoIdeatorConfig.DEFAULT;

        AutoIdeatorConfig modified = config.withRefinementCycles(20);

        assertThat(modified.orchestration().planRefinementCycles()).isEqualTo(20);
        assertThat(config.orchestration().planRefinementCycles()).isEqualTo(12);
    }

    @Test
    @DisplayName("Should chain configuration modifications")
    void shouldChainConfigurationModifications() {
        AutoIdeatorConfig config = AutoIdeatorConfig.DEFAULT
            .withLlmBackend("openrouter")
            .withRefinementCycles(15)
            .withSelfImprovementEnabled(false)
            .withDryRun(true);

        assertThat(config.llm().backend()).isEqualTo("openrouter");
        assertThat(config.orchestration().planRefinementCycles()).isEqualTo(15);
        assertThat(config.selfImprovement().enabled()).isFalse();
        assertThat(config.dryRun()).isTrue();
    }

    @Test
    @DisplayName("Should have correct default idea queue weights")
    void shouldHaveCorrectDefaultIdeaQueueWeights() {
        AutoIdeatorConfig.IdeaQueueWeights w = AutoIdeatorConfig.IdeaQueueWeights.DEFAULT;

        assertThat(w.dreamer()).isEqualTo(1);
        assertThat(w.artist()).isEqualTo(2);
        assertThat(w.refiner()).isEqualTo(1);
        assertThat(w.hacker()).isEqualTo(1);
        assertThat(w.obsessor()).isEqualTo(5);
        assertThat(w.advancer()).isEqualTo(5);
    }

    @Test
    @DisplayName("IdeaQueueWeights should reject zero or negative values")
    void ideaQueueWeightsShouldRejectInvalidValues() {
        assertThatThrownBy(() -> new AutoIdeatorConfig.IdeaQueueWeights(0, 1, 1, 1, 1, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dreamer");

        assertThatThrownBy(() -> new AutoIdeatorConfig.IdeaQueueWeights(1, -1, 1, 1, 1, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("artist");

        assertThatThrownBy(() -> new AutoIdeatorConfig.IdeaQueueWeights(1, 1, 1, 1, 1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("advancer");
    }

    @Test
    @DisplayName("OrchestrationConfig withIdeaQueueWeights should preserve other fields")
    void witherShouldPreserveOtherFields() {
        AutoIdeatorConfig.OrchestrationConfig orig = AutoIdeatorConfig.OrchestrationConfig.DEFAULT;
        AutoIdeatorConfig.IdeaQueueWeights custom = new AutoIdeatorConfig.IdeaQueueWeights(2, 3, 2, 2, 10, 10);
        AutoIdeatorConfig.OrchestrationConfig updated = orig.withIdeaQueueWeights(custom);

        assertThat(updated.ideaQueueWeights()).isEqualTo(custom);
        assertThat(updated.planRefinementCycles()).isEqualTo(orig.planRefinementCycles());
        assertThat(updated.maxConcurrentAgents()).isEqualTo(orig.maxConcurrentAgents());
        assertThat(updated.maxConcurrentCoders()).isEqualTo(orig.maxConcurrentCoders());
        assertThat(updated.parallelExecution()).isEqualTo(orig.parallelExecution());
        assertThat(updated.hackerEnabled()).isEqualTo(orig.hackerEnabled());

        // Original unchanged
        assertThat(orig.ideaQueueWeights()).isEqualTo(AutoIdeatorConfig.IdeaQueueWeights.DEFAULT);
    }

    @Test
    @DisplayName("Default orchestration config should include idea queue weights")
    void defaultOrchestrationShouldIncludeWeights() {
        AutoIdeatorConfig config = AutoIdeatorConfig.DEFAULT;
        assertThat(config.orchestration().ideaQueueWeights()).isEqualTo(AutoIdeatorConfig.IdeaQueueWeights.DEFAULT);
    }

    @Test
    @DisplayName("OpenRouterConfig.toString must redact apiKey so logs cannot leak credentials")
    void openRouterToStringMustRedactApiKey() {
        String secret = "sk-or-v1-LIVE-TEST-KEY-DO-NOT-LEAK-1234567890";
        AutoIdeatorConfig.OpenRouterConfig cfg =
            new AutoIdeatorConfig.OpenRouterConfig(secret, "https://openrouter.ai/api/v1", "anthropic/claude-3.5-sonnet");

        String s = cfg.toString();

        assertThat(s).doesNotContain(secret);
        assertThat(s).contains("apiKey=<redacted>");
        // Other fields stay readable for debugging
        assertThat(s).contains("baseUrl=https://openrouter.ai/api/v1");
        assertThat(s).contains("model=anthropic/claude-3.5-sonnet");
    }

    @Test
    @DisplayName("OpenRouterConfig.toString shows <none> when apiKey is null/blank")
    void openRouterToStringMarksMissingKey() {
        AutoIdeatorConfig.OpenRouterConfig nullKey =
            new AutoIdeatorConfig.OpenRouterConfig(null, "https://openrouter.ai/api/v1", "x");
        AutoIdeatorConfig.OpenRouterConfig blankKey =
            new AutoIdeatorConfig.OpenRouterConfig("   ", "https://openrouter.ai/api/v1", "x");

        assertThat(nullKey.toString()).contains("apiKey=<none>");
        assertThat(blankKey.toString()).contains("apiKey=<none>");
    }

    @Test
    @DisplayName("CustomClaudeCliConfig.toString must redact apiKey")
    void customClaudeCliToStringMustRedactApiKey() {
        String secret = "sk-ant-LIVE-TEST-KEY-DO-NOT-LEAK-abcdefghij";
        AutoIdeatorConfig.CustomClaudeCliConfig cfg = new AutoIdeatorConfig.CustomClaudeCliConfig(
            "claude", secret, "https://api.z.ai/api/anthropic", "glm-5",
            "glm-4.5-air", "glm-4.7", "glm-5", false, new String[0]);

        String s = cfg.toString();

        assertThat(s).doesNotContain(secret);
        assertThat(s).contains("apiKey=<redacted>");
        // Surrounding fields stay readable
        assertThat(s).contains("path=claude");
        assertThat(s).contains("baseUrl=https://api.z.ai/api/anthropic");
        assertThat(s).contains("model=glm-5");
    }

    @Test
    @DisplayName("LlmConfig.toString must transitively redact nested apiKeys")
    void llmConfigToStringMustRedactNestedApiKeys() {
        String orSecret = "sk-or-LIVE-1111111111111111";
        String cccSecret = "sk-ant-LIVE-2222222222222222";

        AutoIdeatorConfig.LlmConfig llm = new AutoIdeatorConfig.LlmConfig(
            "openrouter",
            "x",
            java.time.Duration.ofSeconds(60),
            new AutoIdeatorConfig.OpenRouterConfig(orSecret, "https://openrouter.ai/api/v1", "m"),
            AutoIdeatorConfig.CliConfig.claudeDefault(),
            AutoIdeatorConfig.CliConfig.opencodeDefault(),
            new AutoIdeatorConfig.CustomClaudeCliConfig(
                "claude", cccSecret, "https://api.z.ai/api/anthropic",
                "glm-5", "glm-4.5-air", "glm-4.7", "glm-5", false, new String[0])
        );

        String s = llm.toString();

        assertThat(s).doesNotContain(orSecret);
        assertThat(s).doesNotContain(cccSecret);
    }
}
