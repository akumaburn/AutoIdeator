package com.autoideator.llm;

import com.autoideator.ProcessManager;
import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.model.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * LLM interface using the Claude CLI in streaming JSON mode.
 *
 * <p>Runs the {@code claude} binary in headless mode with
 * {@code --print --output-format stream-json}. Each tool call, sub-agent
 * spawn, and thinking step emits a JSON event on stdout, keeping the
 * activity monitor alive during long agentic operations. The final result
 * is extracted from the {@code "type":"result"} event.
 *
 * <p>The prompt is delivered via stdin to avoid OS argument-length limits.
 * Stdout and stderr are read on parallel virtual threads to prevent
 * pipe-buffer deadlock, and the process is bounded by a configurable timeout.
 */
public class ClaudeCliClient implements LlmInterface {

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeCliClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long STALL_TIMEOUT_MS = 240_000; // 4 minutes

    private final AutoIdeatorConfig config;
    private final ExecutorService executor;

    public ClaudeCliClient(AutoIdeatorConfig config) {
        this.config = config;
        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> 
            LOG.error("Uncaught exception in ClaudeCliClient virtual thread '{}': {}", 
                thread.getName(), throwable.getMessage(), throwable);
        this.executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual()
                .uncaughtExceptionHandler(handler)
                .factory());
    }

    @Override
    public CompletableFuture<AgentResponse> sendPrompt(String systemPrompt, String userPrompt) {
        return CompletableFuture.supplyAsync(() -> executeClaude(systemPrompt, userPrompt, null), executor);
    }

    @Override
    public CompletableFuture<AgentResponse> sendPrompt(String systemPrompt, String userPrompt, Consumer<String> onChunk) {
        return CompletableFuture.supplyAsync(() -> executeClaude(systemPrompt, userPrompt, onChunk), executor);
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

    private AgentResponse executeClaude(String systemPrompt, String userPrompt, Consumer<String> onChunk) {
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
            String prompt = buildPrompt(systemPrompt, userPrompt);
            LOG.debug("Executing Claude CLI: {} (prompt via stdin, {} chars)",
                String.join(" ", command), prompt.length());

            if (config.orchestration().sandboxEnabled()
                    && com.autoideator.sandbox.BubblewrapSandbox.isAvailable()) {
                // Claude CLI needs write access to ~/.claude for session state and logs
                java.nio.file.Path claudeDir = java.nio.file.Path.of(
                    System.getProperty("user.home"), ".claude");
                command = new java.util.ArrayList<>(
                    com.autoideator.sandbox.BubblewrapSandbox.wrapCommand(
                        command, config.workingDir(), claudeDir));
                LOG.debug("Sandbox enabled — command wrapped with bwrap");
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);

            if (config.workingDir() != null) {
                pb.directory(config.workingDir().toFile());
            }

            process = pb.start();
            ProcessManager.getInstance().register(process);

            final Process finalProcess = process;
            StringBuffer outputBuilder = new StringBuffer();
            StringBuffer errorBuilder = new StringBuffer();
            AtomicReference<String> parsedResult = new AtomicReference<>(null);
            StringBuffer salvageBuffer = new StringBuffer();

            outputThread = Thread.startVirtualThread(() -> {
                ClaudeStreamParser streamParser = new ClaudeStreamParser();
                // Wrap onChunk to accumulate streamed text for salvage on crash
                Consumer<String> streamConsumer = chunk -> {
                    salvageBuffer.append(chunk);
                    CliProcessUtils.capBuffer(salvageBuffer, CliProcessUtils.MAX_SALVAGE_BUFFER_CHARS);
                    if (onChunk != null) {
                        try { onChunk.accept(chunk); } catch (Exception ignored) {}
                    }
                };
                try (BufferedReader reader = finalProcess.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputBuilder.append(line).append("\n");
                        CliProcessUtils.capBuffer(outputBuilder, CliProcessUtils.MAX_OUTPUT_BUFFER_CHARS);
                        outputStarted.set(true);
                        lastActivityTime.set(System.currentTimeMillis());
                        try {
                            JsonNode event = JSON.readTree(line);
                            String eventType = event.path("type").asText();
                            if ("result".equals(eventType)) {
                                JsonNode resultNode = event.path("result");
                                if (resultNode.isTextual()) {
                                    parsedResult.set(resultNode.asText());
                                }
                            }
                            // Always stream for salvage; forwards to onChunk if non-null
                            streamParser.processEvent(event, eventType, streamConsumer);
                        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                            // Non-JSON line — stream it raw
                            if (!line.isBlank()) {
                                try { streamConsumer.accept(line + "\n"); } catch (Exception ignored) {}
                            }
                        } catch (Exception e) {
                            LOG.trace("Unexpected error parsing line: {}", e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    LOG.debug("Error reading stdout: {}", e.getMessage());
                } catch (Throwable t) {
                    LOG.warn("Unexpected error in stdout reader thread: {}", t.toString(), t);
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
                } catch (Throwable t) {
                    LOG.warn("Unexpected error in stderr reader thread: {}", t.toString(), t);
                }
            });

            // Write the prompt to stdin on a dedicated thread, AFTER the reader threads
            // are already draining stdout/stderr. Writing the prompt before the readers
            // start risks a pipe-buffer deadlock: a prompt larger than the OS stdin
            // buffer (~64 KB) blocks the writer, while the child simultaneously blocks
            // writing stdout/stderr that nothing is draining yet.
            Thread stdinThread = Thread.startVirtualThread(() -> {
                try (var os = finalProcess.getOutputStream()) {
                    os.write(prompt.getBytes(StandardCharsets.UTF_8));
                    os.write('\n');
                    os.flush();
                } catch (IOException e) {
                    LOG.debug("Error writing prompt to stdin: {}", e.getMessage());
                } catch (Throwable t) {
                    LOG.warn("Unexpected error writing prompt to stdin: {}", t.toString(), t);
                }
            });

            // Measure baseline AFTER reader threads are running (avoids pipe-buffer deadlock during 2s sleep)
            long baselineDescendants = CliProcessUtils.measureBaselineDescendants(process);

            // Monitor thread — tool-aware stall detection
            AtomicBoolean shouldStop = new AtomicBoolean(false);
            monitorThread = CliProcessUtils.startMonitorThread(
                process, STALL_TIMEOUT_MS, lastActivityTime, outputStarted,
                stalled, shouldStop, baselineDescendants, "Claude CLI");

            // Tool-aware wait — extends deadline while tool subprocesses are active
            long timeoutMs = config.llm().timeout() != null
                ? config.llm().timeout().toMillis() : 300_000;
            boolean completed = CliProcessUtils.waitForWithToolAwareness(
                process, timeoutMs, lastActivityTime, baselineDescendants, "Claude CLI");
            shouldStop.set(true);
            if (monitorThread != null) monitorThread.interrupt();

            if (stalled.get()) {
                process.destroyForcibly();
                waitForProcessDeath(process, 2000);
                joinThread(outputThread, 5000);
                joinThread(errorThread, 5000);
                joinThread(monitorThread, 1000);
                LOG.error("Claude CLI stalled — no output for {} seconds (no active tool processes)",
                    STALL_TIMEOUT_MS / 1000);
                return AgentResponse.failure(
                    "Claude CLI stalled — no output received for " + (STALL_TIMEOUT_MS / 1000) + " seconds");
            }

            if (!completed) {
                process.destroyForcibly();
                waitForProcessDeath(process, 2000);
            }

            joinThread(outputThread, 5000);
            joinThread(errorThread, 5000);
            joinThread(monitorThread, 1000);

            long duration = System.currentTimeMillis() - startTime;
            String output = outputBuilder.toString().trim();
            String error = errorBuilder.toString().trim();

            if (!completed) {
                LOG.error("Claude CLI timed out after {}ms (tool-aware)", timeoutMs);
                return AgentResponse.failure("Claude CLI timed out");
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                String result = parsedResult.get();
                if (result != null && !result.isBlank()) {
                    LOG.debug("Claude CLI completed in {}ms (stream-json)", duration);
                    return AgentResponse.success(result, 0, duration);
                } else if (!output.isBlank()) {
                    LOG.debug("Claude CLI completed in {}ms (raw output fallback)", duration);
                    return AgentResponse.success(output, 0, duration);
                } else {
                    LOG.error("Claude CLI exit 0 but produced no output");
                    return AgentResponse.failure("Claude CLI produced no output");
                }
            } else {
                // Salvage partial output — the agent may have done useful work
                // (tool calls, analysis) before the API/CLI crashed
                String salvaged = salvageBuffer.toString().trim();
                if (salvaged.length() > 200) {
                    LOG.warn("Claude CLI exited with code {} but produced {} chars of streamed output — salvaging partial result",
                        exitCode, salvaged.length());
                    return AgentResponse.success(salvaged, 0, duration);
                }
                String cleanError = CliProcessUtils.stripAnsi(error);
                String cleanOutput = CliProcessUtils.stripAnsi(output);
                String errorDetail = !cleanError.isBlank() ? cleanError
                    : (!cleanOutput.isBlank() ? cleanOutput : "exit code " + exitCode);
                LOG.error("Claude CLI failed with exit code {}: {}", exitCode, errorDetail);
                String fullCaptured = CliProcessUtils.buildFailureOutput("Claude CLI", cleanOutput, cleanError, exitCode);
                return AgentResponse.failureWithOutput("Claude CLI failed: " + errorDetail, fullCaptured);
            }
        } catch (IOException e) {
            LOG.error("Failed to execute Claude CLI. Is '{}' in PATH?",
                config.llm().claudeCli().path(), e);
            return AgentResponse.failure("Failed to execute Claude CLI: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AgentResponse.failure("Claude CLI interrupted");
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
                LOG.warn("Claude CLI process {} did not terminate within {}ms", process.pid(), timeoutMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add(config.llm().claudeCli().path());

        // Add model if explicitly configured and not the default opencode-cli model
        String model = config.llm().model();
        if (model != null && !model.isBlank() && !"glm-5.2".equals(model)) {
            command.add("--model");
            command.add(model);
        }

        // Add custom args
        for (String arg : config.llm().claudeCli().args()) {
            command.add(arg);
        }

        // --print enables headless mode; --verbose + stream-json emits an event
        // per tool call / sub-agent / thinking step so the activity monitor stays alive.
        command.add("--print");
        command.add("--verbose");
        command.add("--output-format");
        command.add("stream-json");

        return command;
    }

    private String buildPrompt(String systemPrompt, String userPrompt) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append(systemPrompt).append("\n\n");
        }
        prompt.append(userPrompt);
        return prompt.toString();
    }

    private void joinThread(Thread thread, long timeoutMs) {
        CliProcessUtils.joinThread(thread, timeoutMs);
    }

    @Override
    public String getBackendName() {
        return "Claude CLI";
    }

    @Override
    public boolean isAvailable() {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(config.llm().claudeCli().path(), "--version");
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
            LOG.warn("Claude CLI not available: {}", e.getMessage());
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
