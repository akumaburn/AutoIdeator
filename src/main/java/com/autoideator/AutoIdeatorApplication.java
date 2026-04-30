package com.autoideator;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.config.ConfigLoader;
import com.autoideator.orchestrator.DirectorOrchestrator;
import com.autoideator.orchestrator.Orchestrator;
import com.autoideator.model.Idea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * AutoIdeator - A self-improving AI orchestration agent.
 *
 * <p>Takes a core idea from concept to implementation through coordinated agent
 * swarms, autonomously building, reviewing, and refining its own execution plan.
 *
 * <p>Two orchestration modes:
 * <ul>
 *   <li><b>director</b> (default) — Multi-agent pipeline: IdeaQueue (Dreamer, Artist,
 *       Refiner, Hacker, Obsessor) → Skeptic → Director → Coders → Reviewer → Documenter.
 *       Overseer agent allows user suggestion injection; Maestro gates Artist activation.</li>
 *   <li><b>classic</b> — Original planner-coder-reviewer workflow with iterative plan
 *       refinement.</li>
 * </ul>
 */
@Command(
    name = "autoideator",
    description = "Self-improving AI orchestration agent",
    mixinStandardHelpOptions = true,
    version = "AutoIdeator 2.0.0"
)
public class AutoIdeatorApplication implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(AutoIdeatorApplication.class);

    @Option(
        names = {"--idea", "-i"},
        description = "Detailed description of the finished project (what done looks like — features, behavior, output, UX). Do not include implementation steps or phases.",
        required = true
    )
    private String ideaText;

    @Option(
        names = {"--mode", "-M"},
        description = "Orchestration mode: director (default) or classic",
        defaultValue = "director"
    )
    private String mode;

    @Option(
        names = {"--backend", "-b"},
        description = "LLM backend: claude-cli, custom-claude-cli, opencode-cli, openrouter"
    )
    private String backend;

    @Option(
        names = {"--api-key"},
        description = "API key for OpenRouter"
    )
    private String apiKey;

    @Option(
        names = {"--model", "-m"},
        description = "Model to use"
    )
    private String model;

    @Option(
        names = {"--refinement-cycles", "-r"},
        description = "Number of plan refinement cycles (classic mode only)"
    )
    private Integer refinementCycles;

    @Option(
        names = {"--no-self-improve"},
        description = "Disable self-improvement cycle"
    )
    private boolean noSelfImprove;

    @Option(
        names = {"--dry-run"},
        description = "Plan only, don't implement (runs one cycle in director mode)"
    )
    private boolean dryRun;

    @Option(
        names = {"--config", "-c"},
        description = "Custom config file path"
    )
    private String configPath;

    @Option(
        names = {"--working-dir", "-w"},
        description = "Working directory for the project (required)",
        required = true
    )
    private String workingDir;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AutoIdeatorApplication()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        try {
            LOG.info("Starting AutoIdeator v2.0.0...");
            LOG.info("Idea: {}", ideaText);
            LOG.info("Mode: {}", mode);

            // Validate and resolve working directory
            Path workingDirPath = Path.of(workingDir).toAbsolutePath().normalize();

            if (!Files.exists(workingDirPath)) {
                LOG.error("Working directory does not exist: {}", workingDirPath);
                return 1;
            }
            if (!Files.isDirectory(workingDirPath)) {
                LOG.error("Working directory path is not a directory: {}", workingDirPath);
                return 1;
            }
            if (!Files.isReadable(workingDirPath)) {
                LOG.error("Cannot read working directory: {}", workingDirPath);
                return 1;
            }
            if (!Files.isWritable(workingDirPath)) {
                LOG.error("Cannot write to working directory: {}", workingDirPath);
                return 1;
            }

            // Load configuration
            AutoIdeatorConfig config = loadConfig();

            // Create the idea
            Idea idea = new Idea(ideaText, workingDirPath);

            // Choose orchestrator based on mode
            if ("director".equalsIgnoreCase(mode)) {
                runDirectorMode(config, idea);
            } else {
                runClassicMode(config, idea);
            }

            return 0;
        } catch (Exception e) {
            LOG.error("Fatal error", e);
            return 1;
        }
    }

    /**
     * Run in Director mode - multi-agent system with Dreamer, Skeptic, Director, Coder, Reviewer.
     */
    private void runDirectorMode(AutoIdeatorConfig config, Idea idea) {
        LOG.info("Running in Director mode - multi-agent collaboration");
        LOG.info("Agents: Dreamer → Skeptic → Director → Coders → Reviewer");

        DirectorOrchestrator orchestrator = new DirectorOrchestrator(config, new java.util.concurrent.atomic.AtomicReference<>());

        // Add shutdown hook for graceful termination
        Thread shutdownHook = new Thread(() -> {
            LOG.info("Shutdown requested...");
            orchestrator.stop();
            orchestrator.shutdown();
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            // Run the orchestration
            orchestrator.orchestrate(idea)
                .thenAccept(result -> {
                    LOG.info("Director orchestration completed: {}", result);
                    LOG.info("Total cycles: {}", orchestrator.getCycleCount());
                })
                .exceptionally(ex -> {
                    LOG.error("Director orchestration failed", ex);
                    return null;
                })
                .join();
        } finally {
            boolean hookRemoved = false;
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
                hookRemoved = true;
            } catch (IllegalStateException e) {
                // JVM is already shutting down; hook will handle cleanup
            }
            // Only shutdown here if the hook was removed (i.e., JVM is NOT shutting down).
            // If the hook was NOT removed, the hook itself will call shutdown().
            if (hookRemoved) {
                orchestrator.shutdown();
            }
        }
    }

    /**
     * Run in Classic mode - original planner-coder-reviewer workflow.
     */
    private void runClassicMode(AutoIdeatorConfig config, Idea idea) {
        LOG.info("Running in Classic mode - planner-coder-reviewer workflow");

        Orchestrator orchestrator = new Orchestrator(config);

        // Add shutdown hook for graceful termination
        Thread shutdownHook = new Thread(() -> {
            LOG.info("Shutdown requested...");
            orchestrator.stop();
            orchestrator.shutdown();
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            // Run the orchestration
            orchestrator.orchestrate(idea)
                .thenAccept(result -> {
                    LOG.info("Classic orchestration completed: {}", result);
                })
                .exceptionally(ex -> {
                    LOG.error("Classic orchestration failed", ex);
                    return null;
                })
                .join();
        } finally {
            boolean hookRemoved = false;
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
                hookRemoved = true;
            } catch (IllegalStateException e) {
                // JVM is already shutting down; hook will handle cleanup
            }
            if (hookRemoved) {
                orchestrator.shutdown();
            }
        }
    }

    private AutoIdeatorConfig loadConfig() {
        ConfigLoader loader = configPath != null
            ? new ConfigLoader(Path.of(configPath))
            : new ConfigLoader();

        AutoIdeatorConfig config = loader.load();

        // Override with CLI arguments
        if (backend != null) {
            config = config.withLlmBackend(backend);
        }
        if (apiKey != null) {
            LOG.warn("API key supplied via --api-key is visible to other processes on this host "
                + "(`ps aux`, /proc/<pid>/cmdline). Prefer the OPENROUTER_API_KEY environment "
                + "variable or set api-key in your application.conf.");
            config = config.withApiKey(apiKey);
        }
        if (model != null) {
            config = config.withModel(model);
        }
        if (refinementCycles != null) {
            config = config.withRefinementCycles(refinementCycles);
        }
        if (noSelfImprove) {
            config = config.withSelfImprovementEnabled(false);
        }
        config = config.withDryRun(dryRun);
        config = config.withWorkingDir(Path.of(workingDir).toAbsolutePath().normalize());

        return config;
    }
}
