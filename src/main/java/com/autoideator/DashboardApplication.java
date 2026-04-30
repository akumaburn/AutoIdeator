package com.autoideator;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.config.ConfigLoader;
import com.autoideator.web.DashboardServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * Web Dashboard application for AutoIdeator.
 * Starts a web server with a visual interface for managing runs.
 */
@Command(
    name = "autoideator-dashboard",
    description = "Web dashboard for AutoIdeator",
    mixinStandardHelpOptions = true,
    version = "AutoIdeator Dashboard 2.0.0"
)
public class DashboardApplication implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardApplication.class);

    @Option(
        names = {"--port", "-p"},
        description = "Port for the web server",
        defaultValue = "7070"
    )
    private int port;

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
        names = {"--config", "-c"},
        description = "Custom config file path"
    )
    private String configPath;

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            LOG.error("Uncaught exception in thread '{}': {}", thread.getName(), throwable.getMessage(), throwable);
        });
        int exitCode = new CommandLine(new DashboardApplication()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        try {
            // Validate port
            if (port < 1024 || port > 65535) {
                LOG.error("Port must be between 1024 and 65535, got: {}", port);
                return 1;
            }

            LOG.info("Starting AutoIdeator Dashboard...");
            LOG.info("Open http://localhost:{} in your browser", port);

            // Load configuration
            AutoIdeatorConfig config = loadConfig();

            // Create and start dashboard server
            DashboardServer server = new DashboardServer(port, config);
            CountDownLatch shutdownLatch = new CountDownLatch(1);

            // Start server
            server.start();

            // Add shutdown hook AFTER successful start
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    LOG.info("Shutting down dashboard...");
                    server.stop();
                } catch (Throwable t) {
                    LOG.error("Error during shutdown", t);
                } finally {
                    shutdownLatch.countDown();
                }
            }));

            // Block until shutdown signal
            shutdownLatch.await();

            return 0;
        } catch (Throwable t) {
            LOG.error("Fatal error", t);
            return 1;
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

        return config;
    }
}
