package com.autoideator.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Shared utilities for CLI-based LLM clients.
 * <p>
 * Provides tool-aware process monitoring that pauses the stall timer
 * while the CLI has active descendant processes (indicating tool/command
 * execution such as {@code ./gradlew build}).
 */
final class CliProcessUtils {

    private static final Logger LOG = LoggerFactory.getLogger(CliProcessUtils.class);

    /** How often the monitor thread checks for activity (ms). */
    static final long POLL_INTERVAL_MS = 5_000;

    /** Absolute maximum wall time before forced kill (90 minutes). */
    static final long MAX_WALL_TIME_MS = 90 * 60 * 1_000L;

    /** How often to log a "still active" message when tool processes are running (ms). */
    private static final long TOOL_ACTIVE_LOG_INTERVAL_MS = 5 * 60 * 1_000L;

    /** Maximum time a tool subprocess may run continuously before being terminated (15 minutes). */
    static final long TOOL_PROCESS_TIMEOUT_MS = 15 * 60 * 1_000L;

    /** Number of consecutive "no activity" checks before declaring a stall (prevents false positives). */
    private static final int STALL_CONFIRMATION_COUNT = 3;

    /** Matches ANSI escape sequences (CSI codes, OSC codes, simple escapes). */
    private static final Pattern ANSI_PATTERN = Pattern.compile(
        "\\x1B(?:\\[[0-9;]*[a-zA-Z]|\\][^\u0007]*\u0007|[()][AB012])");

    /**
     * Minimum meaningful content length (after stripping ANSI codes and trimming).
     * Output shorter than this from a non-zero exit is likely just the CLI's
     * startup banner / progress bar and not a real response.
     */
    static final int MIN_MEANINGFUL_OUTPUT_LENGTH = 200;

    /**
     * Maximum size (in chars) for the raw output buffer that accumulates all
     * stdout JSON events.  This is only a fallback — the parsed result is
     * preferred.  Keeping the tail gives the best chance of recovering the
     * final result event if streaming parsing failed.
     */
    static final int MAX_OUTPUT_BUFFER_CHARS = 2_000_000;

    /**
     * Maximum size (in chars) for the salvage buffer that accumulates decoded
     * text chunks for crash recovery.
     */
    static final int MAX_SALVAGE_BUFFER_CHARS = 1_000_000;

    /**
     * Maximum size (in chars) for the stderr buffer.
     */
    static final int MAX_ERROR_BUFFER_CHARS = 500_000;

    private CliProcessUtils() {}

    /**
     * Trim a {@link StringBuffer} to keep only its tail when it exceeds
     * {@code maxChars}.  Uses a 2x threshold to avoid trimming on every
     * append — the buffer is allowed to grow to twice the cap before being
     * cut back to the cap.
     *
     * @param buffer   the buffer to trim in-place
     * @param maxChars the maximum number of characters to retain
     */
    static void capBuffer(StringBuffer buffer, int maxChars) {
        if (buffer.length() > maxChars * 2) {
            buffer.delete(0, buffer.length() - maxChars);
        }
    }

    /**
     * Strip ANSI escape codes from a string.
     * Handles CSI sequences (e.g. {@code \033[0m}, {@code \033[90m}),
     * OSC sequences, and character-set designators.
     *
     * @param input the raw string potentially containing ANSI codes
     * @return the cleaned string with ANSI codes removed
     */
    static String stripAnsi(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return ANSI_PATTERN.matcher(input).replaceAll("");
    }

    /**
     * Check whether CLI output represents a meaningful response vs. just
     * startup/initialization chatter (progress bars, model loading, etc.).
     *
     * @param rawOutput the raw CLI output (may contain ANSI codes)
     * @return true if the output is too short to be a real LLM response
     */
    static boolean isStartupOnlyOutput(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return true;
        }
        String cleaned = stripAnsi(rawOutput).trim();
        return cleaned.length() < MIN_MEANINGFUL_OUTPUT_LENGTH;
    }

    /**
     * Terminate all descendant processes of the given process.
     * Used when tool subprocesses exceed {@link #TOOL_PROCESS_TIMEOUT_MS}.
     * Only descendants are killed — the main CLI process is left alive so it can
     * detect the tool failure and resume.
     *
     * @param process     the parent CLI process whose descendants to kill
     * @param clientName  name for log messages
     * @return the number of descendants terminated
     */
    private static int killToolProcesses(Process process, String clientName) {
        int killed = 0;
        try {
            var descendants = process.toHandle().descendants().toList();
            for (ProcessHandle ph : descendants) {
                LOG.info("{} terminating tool process PID {} (running {}s) after {}m timeout",
                    clientName, ph.pid(),
                    ph.info().totalCpuDuration().map(d -> d.toSeconds()).orElse(-1L),
                    TOOL_PROCESS_TIMEOUT_MS / 60_000);
                ph.destroyForcibly();
                killed++;
            }
            if (killed > 0) {
                LOG.warn("{} terminated {} tool process(es) that exceeded the {}m timeout",
                    clientName, killed, TOOL_PROCESS_TIMEOUT_MS / 60_000);
            }
        } catch (Exception e) {
            LOG.warn("{} error terminating tool processes: {}", clientName, e.getMessage());
        }
        return killed;
    }

    /**
     * Check if the process has active descendant processes beyond the baseline,
     * indicating a tool or command is currently executing.
     * <p>
     * Uses {@code descendants()} rather than {@code children()} so that nested
     * process trees (e.g. claude wrapper → node → bash → gradlew) are detected.
     * Compares the current count against a baseline measured at startup: if there
     * are MORE descendants than at startup, a tool subprocess was spawned.
     *
     * @param process  the CLI process to inspect
     * @param baselineDescendants the number of descendants measured at process start
     *                            (the "idle" process tree size); use -1 if unknown
     * @return true if the process has spawned additional descendants beyond the baseline
     */
    static boolean hasActiveToolProcesses(Process process, long baselineDescendants) {
        try {
            if (!process.isAlive()) {
                return false;
            }
            long current = process.toHandle().descendants().count();
            // If baseline is unknown (-1), use 1 as a safe minimum to avoid
            // treating the CLI runtime itself as a "tool".
            // If baseline is 0 or more, trust the measurement.
            long effectiveBaseline = baselineDescendants >= 0 ? baselineDescendants : 1;
            boolean hasTools = current > effectiveBaseline;
            LOG.trace("Descendant check: current={}, baseline={}, effectiveBaseline={}, hasTools={}",
                current, baselineDescendants, effectiveBaseline, hasTools);
            return hasTools;
        } catch (Exception e) {
            LOG.debug("Could not check descendant processes (process likely exited): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Measure the baseline descendant count for a process.
     * <p>
     * Call this AFTER starting the output reader threads so the pipe buffer
     * is being consumed, and after writing stdin. Waits briefly for the CLI
     * runtime to fully initialize.
     *
     * @return the baseline count, or -1 if measurement failed
     */
    static long measureBaselineDescendants(Process process) {
        try {
            // Brief delay to let the CLI runtime fully initialize.
            // Output reader threads should already be running before this is called.
            Thread.sleep(2_000);
            if (!process.isAlive()) {
                LOG.debug("Process exited before baseline measurement");
                return -1;
            }
            long count = process.toHandle().descendants().count();
            LOG.debug("Baseline descendant count for PID {}: {}", process.pid(), count);
            return count;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            LOG.debug("Could not measure baseline descendants: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Create and start a monitor thread that watches for stalls.
     * <p>
     * The monitor considers the process stalled only when ALL of:
     * <ol>
     *   <li>No stdout/stderr output for {@code stallTimeoutMs}</li>
     *   <li>No active tool subprocesses (descendant count &le; baseline)</li>
     *   <li>Multiple consecutive checks confirm the stall (prevents false positives)</li>
     * </ol>
     * <p>
     * The monitor also enforces an absolute maximum wall time
     * ({@link #MAX_WALL_TIME_MS}) as a safety net regardless of tool activity.
     *
     * @param process             the CLI process to monitor
     * @param stallTimeoutMs      how long (ms) without output before declaring a stall
     * @param lastActivityTime    shared atomic updated by output reader threads
     * @param outputStarted       shared atomic set to true when first output arrives
     * @param stalled             set to true if the monitor kills the process
     * @param shouldStop          set to true by the caller to stop the monitor
     * @param baselineDescendants the idle descendant count from {@link #measureBaselineDescendants}
     * @param clientName          name for log messages (e.g. "Claude CLI")
     * @return the monitor thread (already started)
     */
    static Thread startMonitorThread(
            Process process,
            long stallTimeoutMs,
            AtomicLong lastActivityTime,
            AtomicBoolean outputStarted,
            AtomicBoolean stalled,
            AtomicBoolean shouldStop,
            long baselineDescendants,
            String clientName) {

        long startTime = System.currentTimeMillis();

        return Thread.startVirtualThread(() -> {
            long lastToolActiveLogTime = 0;
            int consecutiveNoActivityCount = 0;
            // Tracks when tool subprocesses first became continuously active.
            // 0 means no tools currently active.
            long toolActiveStartTime = 0;
            try {
                while (!shouldStop.get() && process.isAlive()) {
                    Thread.sleep(POLL_INTERVAL_MS);

                    long now = System.currentTimeMillis();

                    // Absolute safety net — kill after MAX_WALL_TIME_MS no matter what
                    if (now - startTime > MAX_WALL_TIME_MS) {
                        LOG.warn("{} exceeded absolute max wall time ({}m), killing process",
                            clientName, MAX_WALL_TIME_MS / 60_000);
                        stalled.set(true);
                        process.destroyForcibly();
                        break;
                    }

                    // If tool subprocesses are active, reset the activity timer and stall counter
                    if (hasActiveToolProcesses(process, baselineDescendants)) {
                        lastActivityTime.set(now);
                        consecutiveNoActivityCount = 0;

                        // Track when tools first became continuously active
                        if (toolActiveStartTime == 0) {
                            toolActiveStartTime = now;
                        }

                        // Enforce tool process timeout — if tools have been running
                        // continuously for TOOL_PROCESS_TIMEOUT_MS, kill only the
                        // descendants so the CLI can detect the failure and resume.
                        if (now - toolActiveStartTime > TOOL_PROCESS_TIMEOUT_MS) {
                            LOG.warn("{} tool processes have been running continuously for {}m, terminating them",
                                clientName, (now - toolActiveStartTime) / 60_000);
                            killToolProcesses(process, clientName);
                            toolActiveStartTime = 0;
                            // Don't set stalled — let the main CLI process resume
                            continue;
                        }

                        // Throttle: log at DEBUG only every TOOL_ACTIVE_LOG_INTERVAL_MS
                        if (now - lastToolActiveLogTime > TOOL_ACTIVE_LOG_INTERVAL_MS) {
                            long elapsedMin = (now - startTime) / 60_000;
                            long toolMin = (now - toolActiveStartTime) / 60_000;
                            LOG.debug("{} has active tool processes after {}m (tools running {}m/{}m), stall timer paused",
                                clientName, elapsedMin, toolMin, TOOL_PROCESS_TIMEOUT_MS / 60_000);
                            lastToolActiveLogTime = now;
                        }
                        continue;
                    }

                    // No tool processes — reset the continuous tool timer
                    toolActiveStartTime = 0;

                    // No tool processes — check for stall
                    if (!outputStarted.get()) {
                        long timeSinceStart = now - startTime;
                        if (timeSinceStart > stallTimeoutMs) {
                            consecutiveNoActivityCount++;
                            if (consecutiveNoActivityCount >= STALL_CONFIRMATION_COUNT) {
                                LOG.warn("{} startup timeout — no output received after {}ms (confirmed after {} checks), killing process",
                                    clientName, timeSinceStart, consecutiveNoActivityCount);
                                stalled.set(true);
                                process.destroyForcibly();
                                break;
                            }
                            LOG.debug("{} startup timeout candidate {}/{}",
                                clientName, consecutiveNoActivityCount, STALL_CONFIRMATION_COUNT);
                        }
                    } else {
                        long timeSinceActivity = now - lastActivityTime.get();
                        if (timeSinceActivity > stallTimeoutMs) {
                            consecutiveNoActivityCount++;
                            if (consecutiveNoActivityCount >= STALL_CONFIRMATION_COUNT) {
                                LOG.warn("{} stalled — no output for {}ms and no active tool processes (confirmed after {} checks), killing",
                                    clientName, timeSinceActivity, consecutiveNoActivityCount);
                                stalled.set(true);
                                process.destroyForcibly();
                                break;
                            }
                            LOG.debug("{} stall candidate {}/{} (no output for {}ms)",
                                clientName, consecutiveNoActivityCount, STALL_CONFIRMATION_COUNT, timeSinceActivity);
                        } else {
                            // Activity detected recently — reset stall counter
                            consecutiveNoActivityCount = 0;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Wait for a process to complete with tool-aware timeout.
     * <p>
     * The idle timeout clock pauses while either:
     * <ul>
     *   <li>The process has active tool subprocesses (descendant count &gt; baseline)</li>
     *   <li>The process recently produced output ({@code lastActivityTime} was updated)</li>
     * </ul>
     * An absolute maximum wall time ({@link #MAX_WALL_TIME_MS}) prevents infinite
     * execution even if tools keep running.
     *
     * @param process             the process to wait for
     * @param idleTimeoutMs       timeout (ms) when no tools running and no output
     * @param lastActivityTime    shared atomic updated by output reader threads
     * @param baselineDescendants the idle descendant count
     * @param clientName          name for log messages
     * @return true if the process completed normally, false if timed out
     */
    static boolean waitForWithToolAwareness(
            Process process,
            long idleTimeoutMs,
            AtomicLong lastActivityTime,
            long baselineDescendants,
            String clientName) throws InterruptedException {

        long startTime = System.currentTimeMillis();
        long idleDeadline = startTime + idleTimeoutMs;
        int noActivityCheckCount = 0;
        // Tracks when tool subprocesses first became continuously active.
        long toolActiveStartTime = 0;

        while (process.isAlive()) {
            boolean exited = process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            if (exited) {
                return true;
            }

            long now = System.currentTimeMillis();

            // Absolute safety net
            if (now - startTime > MAX_WALL_TIME_MS) {
                LOG.warn("{} exceeded absolute max wall time ({}m) during waitFor",
                    clientName, MAX_WALL_TIME_MS / 60_000);
                return false;
            }

            // If tool processes are active, push the idle deadline forward
            if (hasActiveToolProcesses(process, baselineDescendants)) {
                // Track when tools first became continuously active
                if (toolActiveStartTime == 0) {
                    toolActiveStartTime = now;
                }

                // Enforce tool process timeout — kill only descendants
                if (now - toolActiveStartTime > TOOL_PROCESS_TIMEOUT_MS) {
                    LOG.warn("{} tool processes exceeded {}m timeout during waitFor, terminating them",
                        clientName, TOOL_PROCESS_TIMEOUT_MS / 60_000);
                    killToolProcesses(process, clientName);
                    toolActiveStartTime = 0;
                    // Reset the idle deadline since the CLI should produce
                    // output in response to the tool termination
                    idleDeadline = now + idleTimeoutMs;
                    continue;
                }

                idleDeadline = now + idleTimeoutMs;
                noActivityCheckCount = 0;
                LOG.trace("{} tool active ({}m/{}m), extending idle deadline",
                    clientName, (now - toolActiveStartTime) / 60_000, TOOL_PROCESS_TIMEOUT_MS / 60_000);
                continue;
            }

            // No tool processes — reset the continuous tool timer
            toolActiveStartTime = 0;

            // If output was recently produced, push the idle deadline forward
            long timeSinceOutput = now - lastActivityTime.get();
            if (timeSinceOutput < idleTimeoutMs) {
                // Activity is recent — recalculate deadline from last activity
                long newDeadline = lastActivityTime.get() + idleTimeoutMs;
                if (newDeadline > idleDeadline) {
                    idleDeadline = newDeadline;
                }
                noActivityCheckCount = 0;
                continue;
            }

            // If idle deadline passed, the process is hanging
            if (now > idleDeadline) {
                noActivityCheckCount++;
                if (noActivityCheckCount >= STALL_CONFIRMATION_COUNT) {
                    LOG.warn("{} idle timeout — no tool activity and no output for {}ms during waitFor (confirmed after {} checks)",
                        clientName, idleTimeoutMs, noActivityCheckCount);
                    return false;
                }
                LOG.debug("{} idle timeout candidate {}/{}",
                    clientName, noActivityCheckCount, STALL_CONFIRMATION_COUNT);
            }
        }

        return true;
    }

    /**
     * Build a structured failure output string combining stdout and stderr from a CLI run.
     * Preserves the full subprocess output for debugging via the dashboard.
     *
     * @param cliName     the CLI name (e.g. "OpenCode CLI", "Claude CLI")
     * @param cleanOutput ANSI-stripped stdout
     * @param cleanError  ANSI-stripped stderr
     * @param exitCode    the process exit code
     * @return a formatted string with labeled sections
     */
    static String buildFailureOutput(String cliName, String cleanOutput, String cleanError, int exitCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(cliName).append(" Failed (exit code ").append(exitCode).append(") ===\n\n");
        if (cleanOutput != null && !cleanOutput.isBlank()) {
            sb.append("--- STDOUT ---\n").append(cleanOutput).append("\n\n");
        }
        if (cleanError != null && !cleanError.isBlank()) {
            sb.append("--- STDERR ---\n").append(cleanError).append("\n\n");
        }
        if ((cleanOutput == null || cleanOutput.isBlank()) && (cleanError == null || cleanError.isBlank())) {
            sb.append("(no output captured)\n");
        }
        return sb.toString();
    }

    /**
     * Join a thread with a timeout, restoring the interrupt flag if interrupted.
     */
    static void joinThread(Thread thread, long timeoutMs) {
        if (thread != null) {
            try {
                thread.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
