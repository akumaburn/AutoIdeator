package com.autoideator.llm;

import com.autoideator.ProcessManager;
import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.model.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * LLM interface using the OpenCode CLI.
 * Includes streaming activity detection and automatic retry with exponential backoff.
 */
public class OpenCodeCliClient implements LlmInterface {

    private static final Logger LOG = LoggerFactory.getLogger(OpenCodeCliClient.class);

    // Configuration constants
    private static final long MAX_TIMEOUT_SECONDS = 300;           // 5 minute hard limit
    private static final long STREAM_ACTIVITY_TIMEOUT_MS = 240_000; // 4 minutes without output = stalled
    private static final long INITIAL_BACKOFF_MS = 2000;           // Start with 2 second delay
    private static final long MAX_BACKOFF_MS = 60000;              // Cap at 60 seconds
    private static final double BACKOFF_MULTIPLIER = 2.0;          // Double each time
    private static final int MAX_RETRIES = 5;                      // Maximum retry attempts


    private final AutoIdeatorConfig config;
    private final ExecutorService executor;

    public OpenCodeCliClient(AutoIdeatorConfig config) {
        this.config = config;
        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> 
            LOG.error("Uncaught exception in OpenCodeCliClient virtual thread '{}': {}", 
                thread.getName(), throwable.getMessage(), throwable);
        this.executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual()
                .uncaughtExceptionHandler(handler)
                .factory());
    }

    @Override
    public CompletableFuture<AgentResponse> sendPrompt(String systemPrompt, String userPrompt) {
        return CompletableFuture.supplyAsync(() -> executeWithRetry(systemPrompt, userPrompt, null), executor);
    }

    @Override
    public CompletableFuture<AgentResponse> sendPrompt(String systemPrompt, String userPrompt, Consumer<String> onChunk) {
        return CompletableFuture.supplyAsync(() -> executeWithRetry(systemPrompt, userPrompt, onChunk), executor);
    }

    @Override
    public CompletableFuture<AgentResponse> sendPromptWithHistory(
        String systemPrompt,
        Iterable<Message> messages,
        String userPrompt
    ) {
        // Build conversation context
        StringBuilder fullPrompt = new StringBuilder();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            fullPrompt.append("System: ").append(systemPrompt).append("\n\n");
        }

        for (Message msg : messages) {
            fullPrompt.append(msg.role()).append(": ").append(msg.content()).append("\n");
        }

        fullPrompt.append("user: ").append(userPrompt);

        return sendPrompt(null, fullPrompt.toString());
    }

    /**
     * Execute with automatic retry on streaming stall using exponential backoff.
     * Retries up to {@link #MAX_RETRIES} times before giving up.
     */
    private AgentResponse executeWithRetry(String systemPrompt, String userPrompt, Consumer<String> onChunk) {
        int attempt = 0;
        long currentBackoff = INITIAL_BACKOFF_MS;
        AgentResponse lastResponse = null;

        while (attempt < MAX_RETRIES) {
            attempt++;

            if (attempt > 1) {
                LOG.info("Retry attempt {}/{} for OpenCode CLI (backoff: {}ms)", attempt - 1, MAX_RETRIES - 1, currentBackoff);
                try {
                    Thread.sleep(currentBackoff);
                    // Increase backoff for next attempt, capped at max
                    currentBackoff = Math.min((long)(currentBackoff * BACKOFF_MULTIPLIER), MAX_BACKOFF_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return AgentResponse.failure("OpenCode CLI interrupted during retry");
                }
            }

            lastResponse = executeOpenCode(systemPrompt, userPrompt, onChunk);

            if (lastResponse.success()) {
                return lastResponse;
            }

            // Check if we should retry
            String error = lastResponse.error();
            if (error != null && (error.contains("timed out") || error.contains("stalled"))) {
                LOG.warn("OpenCode CLI {} on attempt {}, will retry with backoff", error, attempt);
                continue;
            }

            // Retry on early CLI crash — if the output is just startup banner/ANSI garbage,
            // the CLI likely failed during initialization (model loading, resource contention, etc.)
            if (error != null && error.contains("OpenCode CLI failed:") &&
                    CliProcessUtils.isStartupOnlyOutput(error)) {
                LOG.warn("OpenCode CLI crashed during startup on attempt {} (output too short to be a real response), will retry", attempt);
                continue;
            }

            // For other errors (e.g., CLI not found, permission denied), don't retry
            LOG.error("OpenCode CLI failed with non-retryable error: {}", error);
            break;
        }

        return lastResponse != null
            ? lastResponse
            : AgentResponse.failure("OpenCode CLI failed after " + MAX_RETRIES + " attempts");
    }

    private AgentResponse executeOpenCode(String systemPrompt, String userPrompt, Consumer<String> onChunk) {
        long startTime = System.currentTimeMillis();
        Process process = null;
        Thread outputThread = null;
        Thread errorThread = null;
        Thread monitorThread = null;
        AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean outputStarted = new AtomicBoolean(false);
        AtomicBoolean stalled = new AtomicBoolean(false);

        try {
            List<String> command = buildCommand();
            LOG.debug("Executing OpenCode CLI: {} (prompt via stdin)", String.join(" ", command));
            LOG.debug("Working directory: {}", config.workingDir());

            if (config.orchestration().sandboxEnabled()
                    && com.autoideator.sandbox.BubblewrapSandbox.isAvailable()) {
                // OpenCode CLI needs write access to its data and state directories
                String home = System.getProperty("user.home");
                java.nio.file.Path dataDir = java.nio.file.Path.of(home, ".local", "share", "opencode");
                java.nio.file.Path stateDir = java.nio.file.Path.of(home, ".local", "state", "opencode");
                // Ensure state directory exists so bwrap can bind-mount it
                try { java.nio.file.Files.createDirectories(stateDir); }
                catch (java.io.IOException ignored) {}
                command = new java.util.ArrayList<>(
                    com.autoideator.sandbox.BubblewrapSandbox.wrapCommand(
                        command, config.workingDir(), dataDir, stateDir));
                LOG.debug("Sandbox enabled — command wrapped with bwrap");
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);

            if (config.workingDir() != null) {
                pb.directory(config.workingDir().toFile());
            }

            process = pb.start();
            ProcessManager.getInstance().register(process);

            // Write prompt to stdin
            String fullPrompt = buildFullPrompt(systemPrompt, userPrompt);
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true)) {
                writer.println(fullPrompt);
            }

            final Process finalProcess = process;
            StringBuffer outputBuilder = new StringBuffer();
            StringBuffer errorBuilder = new StringBuffer();

            outputThread = Thread.startVirtualThread(() -> {
                try (BufferedReader reader = finalProcess.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputBuilder.append(line).append("\n");
                        CliProcessUtils.capBuffer(outputBuilder, CliProcessUtils.MAX_OUTPUT_BUFFER_CHARS);
                        outputStarted.set(true);
                        lastActivityTime.set(System.currentTimeMillis());
                        if (onChunk != null) {
                            try {
                                onChunk.accept(line + "\n");
                            } catch (Exception e) {
                                LOG.trace("Error in streaming callback: {}", e.getMessage());
                            }
                        }
                        LOG.trace("Received output line, updated activity time");
                    }
                } catch (IOException e) {
                    LOG.debug("Error reading output: {}", e.getMessage());
                }
            });

            errorThread = Thread.startVirtualThread(() -> {
                try (BufferedReader reader = finalProcess.errorReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorBuilder.append(line).append("\n");
                        CliProcessUtils.capBuffer(errorBuilder, CliProcessUtils.MAX_ERROR_BUFFER_CHARS);
                        outputStarted.set(true);
                        lastActivityTime.set(System.currentTimeMillis());
                    }
                } catch (IOException e) {
                    LOG.debug("Error reading stderr: {}", e.getMessage());
                }
            });

            // Measure baseline AFTER reader threads are running (avoids pipe-buffer deadlock during 2s sleep)
            long baselineDescendants = CliProcessUtils.measureBaselineDescendants(process);

            // Monitor thread — tool-aware stall detection
            AtomicBoolean shouldStop = new AtomicBoolean(false);
            monitorThread = CliProcessUtils.startMonitorThread(
                process, STREAM_ACTIVITY_TIMEOUT_MS, lastActivityTime, outputStarted,
                stalled, shouldStop, baselineDescendants, "OpenCode CLI");

            // Tool-aware wait — extends deadline while tool subprocesses are active
            long timeoutMs = config.llm().timeout() != null
                ? config.llm().timeout().toMillis()
                : MAX_TIMEOUT_SECONDS * 1000L;
            boolean completed = CliProcessUtils.waitForWithToolAwareness(
                process, timeoutMs, lastActivityTime, baselineDescendants, "OpenCode CLI");

            shouldStop.set(true);
            if (monitorThread != null) monitorThread.interrupt();

            if (stalled.get()) {
                waitForProcessDeath(process, 5000);
                joinThread(outputThread, 1000);
                joinThread(errorThread, 1000);
                joinThread(monitorThread, 1000);
                LOG.error("OpenCode CLI stalled — no output for {} seconds (no active tool processes)",
                    STREAM_ACTIVITY_TIMEOUT_MS / 1000);
                return AgentResponse.failure("OpenCode CLI stalled — no output received for "
                    + (STREAM_ACTIVITY_TIMEOUT_MS / 1000) + " seconds");
            }

            if (!completed) {
                process.destroyForcibly();
                waitForProcessDeath(process, 5000);
                joinThread(outputThread, 1000);
                joinThread(errorThread, 1000);
                joinThread(monitorThread, 1000);
                LOG.error("OpenCode CLI timed out after {}ms (tool-aware)", timeoutMs);
                return AgentResponse.failure("OpenCode CLI timed out");
            }

            joinThread(outputThread, 5000);
            joinThread(errorThread, 5000);
            joinThread(monitorThread, 1000);

            long duration = System.currentTimeMillis() - startTime;
            String output = outputBuilder.toString().trim();
            String error = errorBuilder.toString().trim();

            int exitCode = process.exitValue();

            // Strip ANSI escape codes from output for clean logging and error messages
            String cleanOutput = CliProcessUtils.stripAnsi(output);
            String cleanError = CliProcessUtils.stripAnsi(error);

            if (exitCode == 0 && !cleanOutput.isBlank()) {
                LOG.debug("OpenCode CLI completed in {}ms", duration);
                return AgentResponse.success(cleanOutput, 0, duration);
            } else if (exitCode == 0) {
                LOG.error("OpenCode CLI exit 0 but produced no output");
                return AgentResponse.failure("OpenCode CLI produced no output");
            } else {
                String errorDetail = !cleanError.isBlank() ? cleanError
                    : (!cleanOutput.isBlank() ? cleanOutput : "exit code " + exitCode);
                LOG.error("OpenCode CLI failed with exit code {}: {}", exitCode, errorDetail);
                // Preserve full captured output for debugging — combine stdout + stderr
                String fullCaptured = CliProcessUtils.buildFailureOutput("OpenCode CLI", cleanOutput, cleanError, exitCode);
                return AgentResponse.failureWithOutput("OpenCode CLI failed: " + errorDetail, fullCaptured);
            }
        } catch (IOException e) {
            LOG.error("Failed to execute OpenCode CLI. Is 'opencode' in PATH?", e);
            return AgentResponse.failure("Failed to execute OpenCode CLI: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AgentResponse.failure("OpenCode CLI interrupted");
        } finally {
            if (process != null) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    waitForProcessDeath(process, 2000);
                }
                ProcessManager.getInstance().unregister(process);
            }
            joinThread(outputThread, 1000);
            joinThread(errorThread, 1000);
            joinThread(monitorThread, 1000);
        }
    }

    /**
     * Wait for a process to fully terminate after destroyForcibly() is called.
     */
    private void waitForProcessDeath(Process process, long timeoutMs) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                LOG.warn("Process {} did not terminate within {}ms", process.pid(), timeoutMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void joinThread(Thread thread, long timeoutMs) {
        CliProcessUtils.joinThread(thread, timeoutMs);
    }

    private String buildFullPrompt(String systemPrompt, String userPrompt) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append(systemPrompt).append("\n\n");
        }
        prompt.append(userPrompt);
        return prompt.toString();
    }

    private List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add(config.llm().opencodeCli().path());
        command.add("run");  // OpenCode uses 'run' subcommand

        // Add model - use zai-coding-plan/glm-5.2 default if not specified
        String model = config.llm().model();
        if (model == null || model.isBlank()) {
            // Default to zai-coding-plan/glm-5.2 which works reliably
            model = "zai-coding-plan/glm-5.2";
        } else if (!model.contains("/")) {
            // If model doesn't have provider prefix, assume zai-coding-plan
            model = "zai-coding-plan/" + model;
        }
        command.add("--model");
        command.add(model);

        // Add custom args
        for (String arg : config.llm().opencodeCli().args()) {
            command.add(arg);
        }

        // Note: Prompt is passed via stdin (see executeOpenCode method)
        // Do NOT add prompt to command line to prevent command injection

        return command;
    }

    @Override
    public String getBackendName() {
        return "OpenCode CLI";
    }

    @Override
    public boolean isAvailable() {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(config.llm().opencodeCli().path(), "--version");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectErrorStream(true);
            process = pb.start();
            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            if (process != null && process.isAlive()) process.destroyForcibly();
            LOG.warn("OpenCode CLI not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
