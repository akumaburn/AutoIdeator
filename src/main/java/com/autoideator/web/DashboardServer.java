package com.autoideator.web;

import com.autoideator.checkpoint.CheckpointManager;
import com.autoideator.checkpoint.OrchestrationCheckpoint;
import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.model.Idea;
import com.autoideator.orchestrator.DirectorOrchestrator;
import com.autoideator.web.EventBroadcaster;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Web dashboard server for AutoIdeator.
 * Provides real-time visualization of agent activity and system metrics.
 */
public class DashboardServer {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardServer.class);
    private static final int MAX_PATH_LENGTH = 4096;
    // No length limit on idea or Overseer suggestions — the user knows best.

    private final int port;
    private final AtomicReference<AutoIdeatorConfig> liveConfig;
    private Javalin app;
    private final AtomicReference<DirectorOrchestrator> orchestrator = new AtomicReference<>();
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicReference<Idea> currentIdea = new AtomicReference<>();
    private final AtomicReference<String> pendingOverseerSuggestion = new AtomicReference<>();
    /** Guards mutual exclusion between start and config-update to prevent TOCTOU races. */
    private final ReentrantLock startConfigLock = new ReentrantLock();
    /** Minimum interval between pause/resume actions to prevent rapid toggling. */
    private static final long PAUSE_RESUME_COOLDOWN_MS = 3_000;
    private volatile long lastPauseResumeTime = 0;
    private static final Path STATE_FILE = Path.of(System.getProperty("user.home"), ".autoideator-state.json");
    private final CheckpointManager checkpointManager = new CheckpointManager();

    public DashboardServer(int port, AutoIdeatorConfig config) {
        this.port = port;
        this.liveConfig = new AtomicReference<>(config);
    }

    public void start() {
        if (app != null) {
            throw new IllegalStateException("DashboardServer is already started");
        }
        app = Javalin.create(javalinConfig -> {
            // Serve static files from filesystem if the source directory exists (dev mode),
            // otherwise fall back to classpath (production/jar mode). Filesystem serving
            // picks up HTML/CSS/JS changes on browser refresh without restarting the JVM.
            Path devWebDir = Path.of("src/main/resources/web");
            if (java.nio.file.Files.isDirectory(devWebDir)) {
                LOG.info("Dev mode: serving static files from {}", devWebDir.toAbsolutePath());
                javalinConfig.staticFiles.add(devWebDir.toAbsolutePath().toString(), Location.EXTERNAL);
            } else {
                javalinConfig.staticFiles.add("/web", Location.CLASSPATH);
            }
            javalinConfig.http.maxRequestSize = 10_000_000L; // 10MB
        });

        // Security headers — defense-in-depth against XSS and clickjacking
        app.before(ctx -> {
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("X-Frame-Options", "DENY");
            ctx.header("Referrer-Policy", "strict-origin-when-cross-origin");
            // CSP: allow self + Chart.js CDN; 'unsafe-inline' required for inline scripts/styles
            ctx.header("Content-Security-Policy",
                "default-src 'self'; "
                + "script-src 'self' https://cdn.jsdelivr.net 'unsafe-inline'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; "
                + "connect-src 'self' ws: wss: https://cdn.jsdelivr.net; "
                + "frame-ancestors 'none'");
        });

        // WebSocket for real-time updates
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                LOG.info("WebSocket client connected");
                EventBroadcaster.getInstance().addClient(ctx);
                // Immediately send orchestrator status so the client knows the
                // running state, idea, and workingDir without waiting for the
                // first request-status poll (which may be up to 2s later).
                sendOrchestratorStatusToClient(ctx);
            });

            ws.onClose(ctx -> {
                LOG.info("WebSocket client disconnected");
                EventBroadcaster.getInstance().removeClient(ctx);
            });

            ws.onError(ctx -> {
                LOG.warn("WebSocket error for client: {}",
                    ctx.error() != null ? ctx.error().getMessage() : "unknown");
                EventBroadcaster.getInstance().removeClient(ctx);
            });

            ws.onMessage(ctx -> {
                // Handle commands from client
                String message = ctx.message();
                handleClientCommand(ctx, message);
            });
        });

        // REST API endpoints
        app.get("/api/status", this::getStatus);
        app.get("/api/stats", this::getStats);
        app.get("/api/agents", this::getAgents);
        app.get("/api/config", this::getConfig);
        app.get("/api/overseer", this::getOverseerStatus);
        app.get("/api/agent-output", this::getAgentOutput);
        app.get("/api/agent-output-history", this::getAgentOutputHistory);
        app.get("/api/agent-outputs", this::getAgentOutputSummary);
        app.get("/api/coder-output", this::getCoderOutput);
        app.get("/api/coder-output-history", this::getCoderOutputHistory);
        app.get("/api/retry-attempts", this::getRetryAttempts);
        app.get("/api/retry-summary", this::getRetrySummary);
        app.get("/api/checkpoint", this::getCheckpointStatus);
        app.delete("/api/checkpoint", this::deleteCheckpoint);
        // CSRF mitigation: POST endpoints require JSON content type (blocks form-based CSRF)
        app.before("/api/*", ctx -> {
            if ("POST".equalsIgnoreCase(ctx.method().name())) {
                String contentType = ctx.contentType();
                if (contentType == null || !contentType.contains("application/json")) {
                    throw new HttpResponseException(415, "Content-Type must be application/json");
                }
            }
        });
        app.post("/api/overseer", this::submitOverseerSuggestion);
        app.post("/api/start", this::startOrchestration);
        app.post("/api/stop", this::stopOrchestration);
        app.post("/api/pause", this::pauseOrchestration);
        app.post("/api/resume", this::resumeOrchestration);
        app.post("/api/config", this::updateConfig);

        // Restore state from a previous run (if any)
        restoreState();

        // Start server — bind to localhost only to prevent network exposure
        app.start("127.0.0.1", port);
        LOG.info("Dashboard server started at http://127.0.0.1:{}", port);

        // Start periodic stats broadcast with exception handling to prevent silent failures
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stats-broadcaster");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, throwable) ->
                LOG.error("Uncaught exception in stats broadcaster: {}", throwable.getMessage(), throwable));
            return t;
        });
        scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    EventBroadcaster.getInstance().broadcastSystemStats();
                } catch (Throwable t) {
                    LOG.error("Error in stats broadcast task", t);
                }
            },
            1, 2, TimeUnit.SECONDS
        );
    }

    /**
     * Send current orchestrator status to a single WebSocket client.
     * Used both on initial connect and in response to "request-status" commands.
     */
    private void sendOrchestratorStatusToClient(WsContext ctx) {
        Idea idea = currentIdea.get();
        boolean running = isRunning.get();
        DirectorOrchestrator orch = orchestrator.get();
        boolean paused = orch != null && orch.isPaused();
        String statusStr = paused ? "paused" : (running ? "running" : "idle");
        String message = paused ? "Orchestration paused" : (running ? "Orchestration running" : "Ready");

        Map<String, Object> status = new HashMap<>();
        status.put("status", statusStr);
        status.put("message", message);
        status.put("running", running);
        status.put("paused", paused);
        status.put("cycleCount", EventBroadcaster.getInstance().getCycleCount());
        status.put("totalTokens", EventBroadcaster.getInstance().getTotalTokens());
        status.put("idea", idea != null ? idea.description() : null);
        status.put("workingDir", idea != null ? idea.workingDirectory().toString() : null);
        status.put("timestamp", java.time.Instant.now().toString());
        EventBroadcaster.getInstance().sendToClient(ctx, "orchestrator-status", status);
    }

    private void handleClientCommand(WsContext ctx, String message) {
        try {
            // Use exact matching to avoid "restart" matching "start", etc.
            if ("request-status".equals(message)) {
                // Send current status to the REQUESTING client only (not all clients).
                // Each client polls every 2s, so broadcasting would amplify traffic N-fold.
                sendOrchestratorStatusToClient(ctx);
            } else if ("start".equals(message)) {
                // Lock to prevent config updates from racing with orchestrator creation
                startConfigLock.lock();
                try {
                    // Guard against double-start — same protection as the REST endpoint
                    if (isRunning.compareAndSet(false, true)) {
                        Idea idea = currentIdea.get();
                        if (idea != null) {
                            try {
                                // Atomically clear the previous orchestrator — prevents the old
                                // async completion handler from interfering with the new run.
                                DirectorOrchestrator prev = orchestrator.getAndSet(null);
                                if (prev != null) {
                                    shutdownAsync(prev);
                                }
                                AutoIdeatorConfig updatedConfig = liveConfig.get()
                                    .withWorkingDir(idea.workingDirectory());
                                DirectorOrchestrator newOrchestrator = new DirectorOrchestrator(
                                    updatedConfig, pendingOverseerSuggestion);
                                orchestrator.set(newOrchestrator);

                                // Check for checkpoint to resume from
                                String normDir = idea.workingDirectory().toAbsolutePath()
                                    .normalize().toString();
                                java.util.Optional<OrchestrationCheckpoint> cp =
                                    checkpointManager.load(normDir);
                                boolean resuming = false;
                                if (cp.isPresent()) {
                                    newOrchestrator.setCheckpointToRestore(cp.get());
                                    resuming = true;
                                    String ovrSuggestion = cp.get().pendingOverseerSuggestion();
                                    if (ovrSuggestion != null && !ovrSuggestion.isBlank()) {
                                        if (!pendingOverseerSuggestion.compareAndSet(null, ovrSuggestion)) {
                                            LOG.info("Checkpoint overseer suggestion discarded — newer pending");
                                        }
                                    }
                                    LOG.info("WS start: resuming from checkpoint cycle {}",
                                        cp.get().cycleCount());
                                }

                                newOrchestrator.setCheckpointSaver(
                                    checkpoint -> checkpointManager.save(checkpoint));

                                EventBroadcaster.getInstance().setOrchestrator(newOrchestrator);
                                EventBroadcaster.getInstance().reset(newOrchestrator, false);

                                // Eagerly restore counters from checkpoint so clients
                                // see correct values immediately after state-reset.
                                if (resuming) {
                                    EventBroadcaster.getInstance().setCycleCount(cp.get().cycleCount());
                                    EventBroadcaster.getInstance().setTotalTokens(cp.get().totalTokens());
                                }

                                startOrchestrationAsync(idea);
                            } catch (Exception e) {
                                isRunning.set(false);
                                LOG.error("Failed to start orchestration via WebSocket", e);
                            }
                        } else {
                            isRunning.set(false);
                        }
                    }
                } finally {
                    startConfigLock.unlock();
                }
            } else if ("stop".equals(message)) {
                stopOrchestrationInternal();
            } else if ("pause".equals(message)) {
                if (System.currentTimeMillis() - lastPauseResumeTime >= PAUSE_RESUME_COOLDOWN_MS) {
                    pauseOrchestrationInternal();
                    lastPauseResumeTime = System.currentTimeMillis();
                }
            } else if ("resume".equals(message)) {
                if (System.currentTimeMillis() - lastPauseResumeTime >= PAUSE_RESUME_COOLDOWN_MS) {
                    resumeOrchestrationInternal();
                    lastPauseResumeTime = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            LOG.error("Error handling client command", e);
        }
    }

    private void getStatus(Context ctx) {
        Idea idea = currentIdea.get();
        DirectorOrchestrator orch = orchestrator.get();
        boolean paused = orch != null && orch.isPaused();
        Map<String, Object> status = new HashMap<>();
        status.put("running", isRunning.get());
        status.put("paused", paused);
        status.put("cycleCount", EventBroadcaster.getInstance().getCycleCount());
        status.put("totalTokens", EventBroadcaster.getInstance().getTotalTokens());
        status.put("idea", idea != null ? idea.description() : null);
        status.put("workingDir", idea != null ? idea.workingDirectory().toString() : null);
        // Include checkpoint availability so the frontend can show resume options
        boolean hasCheckpoint = false;
        if (idea != null) {
            String normDir = idea.workingDirectory().toAbsolutePath().normalize().toString();
            hasCheckpoint = checkpointManager.exists(normDir);
        }
        status.put("hasCheckpoint", hasCheckpoint);
        ctx.json(status);
    }

    private void getCheckpointStatus(Context ctx) {
        // Accept workingDir query param, or fall back to currentIdea
        String workingDir = ctx.queryParam("workingDir");
        String normDir;
        if (workingDir != null && !workingDir.isBlank()) {
            if (workingDir.length() > MAX_PATH_LENGTH) {
                ctx.status(400).json(Map.of("error", "Working directory path too long"));
                return;
            }
            try {
                normDir = Path.of(workingDir).toAbsolutePath().normalize().toString();
            } catch (java.nio.file.InvalidPathException e) {
                ctx.status(400).json(Map.of("error", "Invalid working directory path"));
                return;
            }
        } else {
            Idea idea = currentIdea.get();
            if (idea == null) {
                ctx.json(Map.of("hasCheckpoint", false));
                return;
            }
            normDir = idea.workingDirectory().toAbsolutePath().normalize().toString();
        }
        java.util.Optional<OrchestrationCheckpoint> cp = checkpointManager.load(normDir);
        if (cp.isPresent()) {
            OrchestrationCheckpoint checkpoint = cp.get();
            ctx.json(Map.of(
                "hasCheckpoint", true,
                "cycleCount", checkpoint.cycleCount(),
                "totalTokens", checkpoint.totalTokens(),
                "timestamp", checkpoint.timestamp().toString(),
                "workingDirectory", checkpoint.workingDirectory()
            ));
        } else {
            ctx.json(Map.of("hasCheckpoint", false));
        }
    }

    private void deleteCheckpoint(Context ctx) {
        Idea idea = currentIdea.get();
        if (idea == null) {
            ctx.status(404).json(Map.of("error", "No idea set — cannot determine checkpoint directory"));
            return;
        }
        String normDir = idea.workingDirectory().toAbsolutePath().normalize().toString();
        boolean deleted = checkpointManager.delete(normDir);
        ctx.json(Map.of("deleted", deleted));
    }

    private void getStats(Context ctx) {
        ctx.json(EventBroadcaster.getInstance().getCurrentStats());
    }

    private void getAgents(Context ctx) {
        ctx.json(EventBroadcaster.getInstance().getAgentStates());
    }

    @SuppressWarnings("unchecked")
    private void startOrchestration(Context ctx) {
        // Lock to prevent config updates from racing with orchestrator creation
        startConfigLock.lock();
        try {
            // Prevent double-start — atomic check-and-set to avoid race condition
            if (!isRunning.compareAndSet(false, true)) {
                ctx.status(409).json(Map.of(
                    "error", "Orchestration is already running",
                    "hint", "Stop the current orchestration before starting a new one"
                ));
                return;
            }

            Map<String, Object> rawBody = ctx.bodyAsClass(Map.class);
            String ideaText = rawBody.get("idea") != null ? rawBody.get("idea").toString() : null;
            String workingDir = rawBody.get("workingDir") != null ? rawBody.get("workingDir").toString() : null;
            boolean forceNew = Boolean.TRUE.equals(rawBody.get("forceNew"));

            // Validate idea input
            if (ideaText == null || ideaText.isBlank()) {
                isRunning.set(false);
                ctx.status(400).json(Map.of("error", "Idea is required"));
                return;
            }


            // Validate working directory - mandatory
            if (workingDir == null || workingDir.isBlank()) {
                isRunning.set(false);
                ctx.status(400).json(Map.of("error", "Working directory is required"));
                return;
            }
            if (workingDir.length() > MAX_PATH_LENGTH) {
                isRunning.set(false);
                ctx.status(400).json(Map.of("error", "Working directory path too long"));
                return;
            }

            Path workingDirPath = Path.of(workingDir).toAbsolutePath().normalize();

            // Validate directory exists — do NOT auto-create to prevent path traversal
            if (!java.nio.file.Files.exists(workingDirPath)) {
                isRunning.set(false);
                ctx.status(400).json(Map.of("error", "Working directory does not exist: " + workingDirPath));
                return;
            }

            if (!java.nio.file.Files.isDirectory(workingDirPath)) {
                isRunning.set(false);
                ctx.status(400).json(Map.of("error", "Working directory path is not a directory"));
                return;
            }

            Idea idea = new Idea(ideaText, workingDirPath);
            currentIdea.set(idea);

            // Update config with working directory
            AutoIdeatorConfig updatedConfig = liveConfig.get().withWorkingDir(idea.workingDirectory());
            DirectorOrchestrator newOrchestrator = new DirectorOrchestrator(
                updatedConfig, pendingOverseerSuggestion);
            // Atomically swap and shut down the previous orchestrator to prevent leaks
            // (mirrors the WebSocket "start" handler logic)
            DirectorOrchestrator prev = orchestrator.getAndSet(newOrchestrator);
            if (prev != null) {
                shutdownAsync(prev);
            }

            // Check for a checkpoint to resume from (unless user requested fresh start)
            String normalizedDir = workingDirPath.toAbsolutePath().normalize().toString();
            if (forceNew) {
                checkpointManager.delete(normalizedDir);
                LOG.info("User requested fresh start — checkpoint deleted for {}", normalizedDir);
            }
            java.util.Optional<OrchestrationCheckpoint> checkpoint =
                forceNew ? java.util.Optional.empty() : checkpointManager.load(normalizedDir);
            boolean resuming = false;
            if (checkpoint.isPresent()) {
                newOrchestrator.setCheckpointToRestore(checkpoint.get());
                resuming = true;
                // Restore pending overseer suggestion if any
                String overseerSuggestion = checkpoint.get().pendingOverseerSuggestion();
                if (overseerSuggestion != null && !overseerSuggestion.isBlank()) {
                    if (!pendingOverseerSuggestion.compareAndSet(null, overseerSuggestion)) {
                        LOG.info("Checkpoint overseer suggestion discarded — a newer suggestion is already pending");
                    }
                }
                LOG.info("Resuming from checkpoint: cycle={}", checkpoint.get().cycleCount());
            }

            // Register checkpoint auto-save callback
            newOrchestrator.setCheckpointSaver(cp -> checkpointManager.save(cp));

            EventBroadcaster.getInstance().setOrchestrator(newOrchestrator);
            // On resume, don't repopulate from cycle history (it's empty now —
            // checkpoint restore happens later inside orchestrate()). The cycle
            // history will be repopulated after restoreFromCheckpoint completes.
            EventBroadcaster.getInstance().reset(newOrchestrator, false);

            // Eagerly restore counters from checkpoint so that clients see the
            // correct values immediately after the state-reset broadcast, rather
            // than seeing 0 until restoreFromCheckpoint runs asynchronously.
            if (resuming) {
                EventBroadcaster.getInstance().setCycleCount(checkpoint.get().cycleCount());
                EventBroadcaster.getInstance().setTotalTokens(checkpoint.get().totalTokens());
            }

            startOrchestrationAsync(idea);

            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("status", resuming ? "resumed" : "started");
            response.put("idea", ideaText);
            if (resuming) {
                response.put("resumedFromCycle", checkpoint.get().cycleCount());
            }
            ctx.json(response);

        } catch (Exception e) {
            isRunning.set(false);
            LOG.error("Error starting orchestration", e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            ctx.status(500).json(Map.of("error", errorMsg));
        } finally {
            startConfigLock.unlock();
        }
    }

    private void startOrchestrationAsync(Idea idea) {
        DirectorOrchestrator orch = orchestrator.get();
        if (orch == null) {
            isRunning.set(false);
            EventBroadcaster.getInstance().broadcastStatus("error", "No orchestrator available");
            return;
        }

        EventBroadcaster.getInstance().broadcastStatus("running", "Processing: " + idea.description());

        // Capture the orchestrator reference so the completion handler can detect
        // whether a stop-then-start replaced it with a new orchestrator.
        // Without this guard, a stale handler would clobber isRunning for the new run.
        final DirectorOrchestrator capturedOrch = orch;

        // orchestrate() runs on its own virtual-thread executor — no blocking wrapper needed
        orch.orchestrate(idea)
            .thenAccept(result -> {
                if (orchestrator.get() == capturedOrch) {
                    isRunning.set(false);
                    EventBroadcaster.getInstance().broadcastStatus(
                        "completed",
                        "Completed in " + result.totalDuration()
                    );
                } else {
                    LOG.info("Stale orchestrator completed — suppressing status broadcast");
                }
            })
            .exceptionally(ex -> {
                if (orchestrator.get() == capturedOrch) {
                    isRunning.set(false);
                    LOG.error("Orchestration error", ex);
                    EventBroadcaster.getInstance().broadcastStatus(
                        "error",
                        "Failed: " + ex.getMessage()
                    );
                } else {
                    LOG.info("Stale orchestrator failed — suppressing status broadcast");
                }
                return null;
            });
    }

    private void stopOrchestration(Context ctx) {
        if (!isRunning.get()) {
            ctx.status(409).json(Map.of("error", "No orchestration is currently running"));
            return;
        }
        stopOrchestrationInternal();
        ctx.json(Map.of("status", "stopped"));
    }

    /**
     * Stop the current orchestration atomically.
     * <p>
     * Acquires {@code startConfigLock} to prevent a concurrent start from racing
     * between the {@code isRunning} transition and the orchestrator swap. The
     * blocking {@code shutdown()} is performed asynchronously so HTTP/WS handler
     * threads are not blocked for the 15s executor drain.
     */
    private void stopOrchestrationInternal() {
        startConfigLock.lock();
        try {
            // Atomically remove the orchestrator reference so stale completion
            // handlers (which check orchestrator.get() == capturedOrch) detect
            // the change and suppress their broadcasts.
            DirectorOrchestrator orch = orchestrator.getAndSet(null);
            if (orch == null) return;

            boolean wasRunning = isRunning.compareAndSet(true, false);

            // Disable checkpoint auto-save BEFORE stopping, so any in-flight
            // cycle completion doesn't re-create the checkpoint after we delete it.
            orch.setCheckpointSaver(null);

            // Signal the orchestration loop to exit
            orch.stop();

            // Explicit stop = clear checkpoint for this directory
            Idea idea = currentIdea.get();
            if (idea != null) {
                String normalizedDir = idea.workingDirectory().toAbsolutePath().normalize().toString();
                checkpointManager.delete(normalizedDir);
            }

            if (wasRunning) {
                EventBroadcaster.getInstance().broadcastStatus("stopped", "Orchestration stopped by user");
            }

            // Drain the executor asynchronously — don't block the caller thread.
            // shutdown() can take up to 15s waiting for in-flight tasks to complete.
            Thread.startVirtualThread(() -> {
                try {
                    orch.shutdown();
                } catch (Exception e) {
                    LOG.warn("Error shutting down orchestrator: {}", e.getMessage());
                }
            });
        } finally {
            startConfigLock.unlock();
        }
    }

    private void pauseOrchestration(Context ctx) {
        long elapsed = System.currentTimeMillis() - lastPauseResumeTime;
        if (elapsed < PAUSE_RESUME_COOLDOWN_MS) {
            long remaining = (PAUSE_RESUME_COOLDOWN_MS - elapsed + 999) / 1000; // ceiling seconds
            ctx.status(429).json(Map.of("error", "Please wait " + remaining + "s before pausing"));
            return;
        }
        DirectorOrchestrator orch = orchestrator.get();
        if (orch == null || !isRunning.get()) {
            ctx.status(409).json(Map.of("error", "No orchestration is currently running"));
            return;
        }
        if (orch.isPaused()) {
            ctx.status(409).json(Map.of("error", "Orchestration is already paused"));
            return;
        }
        pauseOrchestrationInternal();
        lastPauseResumeTime = System.currentTimeMillis();
        ctx.json(Map.of("status", "pausing"));
    }

    private void resumeOrchestration(Context ctx) {
        long elapsed = System.currentTimeMillis() - lastPauseResumeTime;
        if (elapsed < PAUSE_RESUME_COOLDOWN_MS) {
            long remaining = (PAUSE_RESUME_COOLDOWN_MS - elapsed + 999) / 1000;
            ctx.status(429).json(Map.of("error", "Please wait " + remaining + "s before resuming"));
            return;
        }
        DirectorOrchestrator orch = orchestrator.get();
        if (orch == null || !orch.isPaused()) {
            ctx.status(409).json(Map.of("error", "Orchestration is not paused"));
            return;
        }
        resumeOrchestrationInternal();
        lastPauseResumeTime = System.currentTimeMillis();
        ctx.json(Map.of("status", "resumed"));
    }

    private void pauseOrchestrationInternal() {
        startConfigLock.lock();
        try {
            DirectorOrchestrator orch = orchestrator.get();
            if (orch != null && isRunning.get()) {
                orch.pause();
                // Broadcast "pausing" — the system is still finishing current agent work.
                // The actual "paused" status is broadcast by checkAndPause() when the
                // orchestration thread reaches a checkpoint and fully enters PAUSED state.
                EventBroadcaster.getInstance().broadcastStatus("pausing",
                    "Pausing — waiting for current agents to finish");
            }
        } finally {
            startConfigLock.unlock();
        }
    }

    private void resumeOrchestrationInternal() {
        startConfigLock.lock();
        try {
            DirectorOrchestrator orch = orchestrator.get();
            if (orch != null && orch.isPaused()) {
                // Apply any config changes made while paused
                AutoIdeatorConfig newConfig = liveConfig.get();
                Idea idea = currentIdea.get();
                if (idea != null) {
                    newConfig = newConfig.withWorkingDir(idea.workingDirectory());
                }
                try {
                    orch.applyConfig(newConfig);
                } catch (Exception e) {
                    LOG.warn("Could not hot-apply config: {}", e.getMessage());
                }
                orch.resume();
                EventBroadcaster.getInstance().broadcastStatus("running", "Orchestration resumed");
            }
        } finally {
            startConfigLock.unlock();
        }
    }

    /**
     * Shut down an orchestrator on a background virtual thread.
     * Prevents blocking HTTP/WS handler threads for the 15s executor drain.
     */
    private void shutdownAsync(DirectorOrchestrator orch) {
        Thread.startVirtualThread(() -> {
            try {
                orch.shutdown();
            } catch (Exception e) {
                LOG.warn("Error shutting down orchestrator: {}", e.getMessage());
            }
        });
    }

    private void getOverseerStatus(Context ctx) {
        String suggestion = pendingOverseerSuggestion.get();
        boolean pending = suggestion != null;
        ctx.json(Map.of("pending", pending, "suggestion", pending ? suggestion : ""));
    }

    @SuppressWarnings("unchecked")
    private void submitOverseerSuggestion(Context ctx) {
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String suggestion = body.get("suggestion") != null ? body.get("suggestion").toString().trim() : "";
            if (suggestion.isBlank()) {
                ctx.status(400).json(Map.of("error", "Suggestion cannot be empty"));
                return;
            }
            if (suggestion.length() > 50_000) {
                ctx.status(400).json(Map.of("error", "Suggestion exceeds maximum length of 50000 characters"));
                return;
            }
            boolean accepted = pendingOverseerSuggestion.compareAndSet(null, suggestion);
            if (!accepted) {
                ctx.status(409).json(Map.of(
                    "error", "A suggestion is already pending",
                    "hint", "Wait for the current suggestion to be processed in the next cycle"
                ));
                return;
            }
            String message = isRunning.get()
                ? "Suggestion queued — Overseer will act in the next cycle"
                : "Suggestion queued — Overseer will act when orchestration starts";
            ctx.json(Map.of("status", "queued", "message", message));
        } catch (Exception e) {
            LOG.error("Error submitting overseer suggestion", e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            ctx.status(400).json(Map.of("error", errorMsg));
        }
    }

    /**
     * Returns the full (untruncated) output for a specific agent.
     * Query params:
     * - agent=dreamer (required, case-insensitive)
     * - cycle=54 (optional, defaults to latest)
     */
    private void getAgentOutput(Context ctx) {
        String agentName = ctx.queryParam("agent");
        if (agentName == null || agentName.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing 'agent' query parameter"));
            return;
        }
        if (agentName.length() > 50) {
            ctx.status(400).json(Map.of("error", "Agent name too long"));
            return;
        }
        Integer cycle = null;
        String cycleParam = ctx.queryParam("cycle");
        if (cycleParam != null && !cycleParam.isBlank()) {
            try {
                cycle = Integer.parseInt(cycleParam);
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid 'cycle' query parameter"));
                return;
            }
        }
        EventBroadcaster.AgentOutput output = EventBroadcaster.getInstance().getAgentOutput(agentName, cycle);
        if (output == null) {
            ctx.json(Map.of("agentName", agentName, "content", "", "hasOutput", false));
        } else {
            ctx.json(Map.of(
                "agentName", output.agentName(),
                "cycle", output.cycle(),
                "phase", output.phase() != null ? output.phase() : "",
                "content", output.content(),
                "timestamp", output.timestamp().toString(),
                "hasOutput", true
            ));
        }
    }

    /**
     * Returns a summary of all stored agent outputs: agent name to character count.
     */
    private void getAgentOutputSummary(Context ctx) {
        ctx.json(EventBroadcaster.getInstance().getAgentOutputSummary());
    }

    /**
     * Returns recent output history for a specific agent.
     * Query params:
     * - agent=dreamer (required)
     * - limit=20 (optional)
     */
    private void getAgentOutputHistory(Context ctx) {
        String agentName = ctx.queryParam("agent");
        if (agentName == null || agentName.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing 'agent' query parameter"));
            return;
        }
        if (agentName.length() > 50) {
            ctx.status(400).json(Map.of("error", "Agent name too long"));
            return;
        }

        int limit = 40;
        String limitParam = ctx.queryParam("limit");
        if (limitParam != null && !limitParam.isBlank()) {
            try {
                limit = Integer.parseInt(limitParam);
            } catch (NumberFormatException ignored) {
                limit = 40;
            }
        }

        List<EventBroadcaster.AgentOutput> history =
            EventBroadcaster.getInstance().getAgentOutputHistory(agentName, limit);

        List<Map<String, Object>> items = new ArrayList<>();
        for (EventBroadcaster.AgentOutput output : history) {
            items.add(Map.of(
                "agentName", output.agentName(),
                "cycle", output.cycle(),
                "phase", output.phase() != null ? output.phase() : "",
                "timestamp", output.timestamp().toString()
            ));
        }
        ctx.json(Map.of(
            "agentName", agentName,
            "count", items.size(),
            "items", items
        ));
    }

    /**
     * Returns full output for a specific coder.
     * Query params:
     * - coder=Coder-1 (required)
     * - cycle=54 (optional, defaults to latest)
     */
    private void getCoderOutput(Context ctx) {
        String coderName = ctx.queryParam("coder");
        if (coderName == null || coderName.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing 'coder' query parameter"));
            return;
        }
        if (coderName.length() > 50) {
            ctx.status(400).json(Map.of("error", "Coder name too long"));
            return;
        }
        Integer cycle = null;
        String cycleParam = ctx.queryParam("cycle");
        if (cycleParam != null && !cycleParam.isBlank()) {
            try {
                cycle = Integer.parseInt(cycleParam);
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid 'cycle' query parameter"));
                return;
            }
        }

        EventBroadcaster.CoderOutput output = EventBroadcaster.getInstance().getCoderOutput(coderName, cycle);
        if (output == null) {
            ctx.json(Map.of("coderName", coderName, "content", "", "hasOutput", false));
        } else {
            ctx.json(Map.of(
                "coderName", output.coderName(),
                "cycle", output.cycle(),
                "taskSummary", output.taskSummary() != null ? output.taskSummary() : "",
                "success", output.success(),
                "content", output.content(),
                "timestamp", output.timestamp().toString(),
                "hasOutput", true
            ));
        }
    }

    /**
     * Returns recent output history for a specific coder.
     * Query params:
     * - coder=Coder-1 (required)
     * - limit=20 (optional)
     */
    private void getCoderOutputHistory(Context ctx) {
        String coderName = ctx.queryParam("coder");
        if (coderName == null || coderName.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing 'coder' query parameter"));
            return;
        }
        if (coderName.length() > 50) {
            ctx.status(400).json(Map.of("error", "Coder name too long"));
            return;
        }

        int limit = 20;
        String limitParam = ctx.queryParam("limit");
        if (limitParam != null && !limitParam.isBlank()) {
            try {
                limit = Integer.parseInt(limitParam);
            } catch (NumberFormatException ignored) {
                limit = 20;
            }
        }

        List<EventBroadcaster.CoderOutput> history = EventBroadcaster.getInstance().getCoderOutputHistory(coderName, limit);

        // Manually build serializable maps to avoid Instant serialization issues
        // (Javalin's default ObjectMapper may not have the JSR310 module registered)
        List<Map<String, Object>> items = new ArrayList<>();
        for (EventBroadcaster.CoderOutput output : history) {
            items.add(Map.of(
                "coderName", output.coderName(),
                "cycle", output.cycle(),
                "taskSummary", output.taskSummary() != null ? output.taskSummary() : "",
                "success", output.success(),
                "timestamp", output.timestamp().toString()
            ));
        }
        ctx.json(Map.of(
            "coderName", coderName,
            "count", items.size(),
            "items", items
        ));
    }

    /**
     * Returns retry attempts for a specific agent, optionally filtered by cycle.
     * Query params:
     * - agent=dreamer (required, case-insensitive)
     * - cycle=54 (optional, filters by cycle)
     */
    private void getRetryAttempts(Context ctx) {
        String agentName = ctx.queryParam("agent");
        if (agentName == null || agentName.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing 'agent' query parameter"));
            return;
        }
        if (agentName.length() > 50) {
            ctx.status(400).json(Map.of("error", "Agent name too long"));
            return;
        }

        Integer cycle = null;
        String cycleParam = ctx.queryParam("cycle");
        if (cycleParam != null && !cycleParam.isBlank()) {
            try {
                cycle = Integer.parseInt(cycleParam);
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid 'cycle' query parameter"));
                return;
            }
        }

        List<EventBroadcaster.RetryAttempt> attempts = cycle != null
            ? EventBroadcaster.getInstance().getRetryAttemptsForCycle(agentName, cycle)
            : EventBroadcaster.getInstance().getRetryAttempts(agentName);

        List<Map<String, Object>> items = new ArrayList<>();
        for (EventBroadcaster.RetryAttempt attempt : attempts) {
            items.add(Map.of(
                "agentName", attempt.agentName(),
                "cycle", attempt.cycle(),
                "phase", attempt.phase() != null ? attempt.phase() : "",
                "attemptNumber", attempt.attemptNumber(),
                "error", attempt.error(),
                "partialOutput", attempt.partialOutput() != null ? attempt.partialOutput() : "",
                "timestamp", attempt.timestamp().toString()
            ));
        }
        ctx.json(Map.of(
            "agentName", agentName,
            "count", items.size(),
            "items", items
        ));
    }

    /**
     * Returns a summary of all retry attempts across all agents.
     */
    private void getRetrySummary(Context ctx) {
        Map<String, Object> summary = new LinkedHashMap<>();
        
        int totalRetries = EventBroadcaster.getInstance().getTotalRetryCount();
        summary.put("totalRetries", totalRetries);
        
        Map<String, Integer> byAgent = new LinkedHashMap<>();
        for (String agent : List.of("dreamer", "artist", "refiner", "hacker", "obsessor",
                                     "advancer", "scorer", "skeptic", "architect", "director",
                                     "coder", "reviewer", "qa", "testoptimizer", "verifier",
                                     "organizer", "cleaner", "documenter", "synthesizer",
                                     "overseer", "maestro")) {
            int count = EventBroadcaster.getInstance().getRetryAttempts(agent).size();
            if (count > 0) {
                byAgent.put(agent, count);
            }
        }
        summary.put("byAgent", byAgent);
        
        ctx.json(summary);
    }

    private void getConfig(Context ctx) {
        ctx.json(configToMap(liveConfig.get()));
    }

    @SuppressWarnings("unchecked")
    private void updateConfig(Context ctx) {
        // Lock to prevent a concurrent startOrchestration from racing between
        // our isRunning check and the actual config update (TOCTOU).
        startConfigLock.lock();
        try {
            if (isRunning.get()) {
                DirectorOrchestrator orch = orchestrator.get();
                boolean paused = orch != null && orch.isPaused();
                if (!paused) {
                    ctx.status(409).json(Map.of(
                        "error", "Cannot update config while orchestration is running",
                        "hint", "Pause or stop the orchestration first, then update config"
                    ));
                    return;
                }
            }
            Map<String, Object> updates = ctx.bodyAsClass(Map.class);
            AutoIdeatorConfig newConfig = applyUpdates(liveConfig.get(), updates);
            liveConfig.set(newConfig);
            ctx.json(configToMap(newConfig));
        } catch (Exception e) {
            LOG.error("Error updating config", e);
            ctx.status(400).json(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            startConfigLock.unlock();
        }
    }

    private Map<String, Object> configToMap(AutoIdeatorConfig cfg) {
        Map<String, Object> root = new LinkedHashMap<>();

        // llm
        AutoIdeatorConfig.LlmConfig llm = cfg.llm();
        Map<String, Object> llmMap = new LinkedHashMap<>();
        llmMap.put("backend", llm.backend());
        llmMap.put("model", llm.model());
        llmMap.put("timeoutSeconds", llm.timeout() != null ? llm.timeout().getSeconds() : 120);

        Map<String, Object> orMap = new LinkedHashMap<>();
        orMap.put("apiKey", maskSecret(llm.openRouter().apiKey()));
        orMap.put("baseUrl", llm.openRouter().baseUrl());
        orMap.put("model", llm.openRouter().model());
        llmMap.put("openRouter", orMap);

        Map<String, Object> ccMap = new LinkedHashMap<>();
        ccMap.put("path", llm.claudeCli().path());
        ccMap.put("args", Arrays.asList(llm.claudeCli().args()));
        llmMap.put("claudeCli", ccMap);

        Map<String, Object> ocMap = new LinkedHashMap<>();
        ocMap.put("path", llm.opencodeCli().path());
        ocMap.put("args", Arrays.asList(llm.opencodeCli().args()));
        llmMap.put("opencodeCli", ocMap);

        AutoIdeatorConfig.CustomClaudeCliConfig ccc = llm.customClaudeCli();
        Map<String, Object> cccMap = new LinkedHashMap<>();
        cccMap.put("path", ccc.path());
        cccMap.put("apiKey", maskSecret(ccc.apiKey()));
        cccMap.put("baseUrl", ccc.baseUrl());
        cccMap.put("model", ccc.model());
        cccMap.put("haikuModel", ccc.haikuModel());
        cccMap.put("sonnetModel", ccc.sonnetModel());
        cccMap.put("opusModel", ccc.opusModel());
        cccMap.put("dangerouslySkipPermissions", ccc.dangerouslySkipPermissions());
        cccMap.put("args", Arrays.asList(ccc.args()));
        llmMap.put("customClaudeCli", cccMap);

        root.put("llm", llmMap);

        // orchestration
        AutoIdeatorConfig.OrchestrationConfig orch = cfg.orchestration();
        Map<String, Object> orchMap = new LinkedHashMap<>();
        orchMap.put("planRefinementCycles", orch.planRefinementCycles());
        orchMap.put("maxConcurrentAgents", orch.maxConcurrentAgents());
        orchMap.put("maxConcurrentCoders", orch.maxConcurrentCoders());
        orchMap.put("parallelExecution", orch.parallelExecution());
        orchMap.put("commitIntervalMinutes", orch.commitInterval() != null ? orch.commitInterval().toMinutes() : 5);
        orchMap.put("hackerEnabled", orch.hackerEnabled());
        orchMap.put("synthesizeInterval", orch.synthesizeInterval());
        orchMap.put("minGoalAlignment", orch.minGoalAlignment());
        orchMap.put("minOverallScore", orch.minOverallScore());
        orchMap.put("sandboxEnabled", orch.sandboxEnabled());

        AutoIdeatorConfig.IdeaQueueWeights w = orch.ideaQueueWeights();
        Map<String, Object> weightsMap = new LinkedHashMap<>();
        weightsMap.put("dreamer",  w.dreamer());
        weightsMap.put("artist",   w.artist());
        weightsMap.put("refiner",  w.refiner());
        weightsMap.put("hacker",   w.hacker());
        weightsMap.put("obsessor", w.obsessor());
        weightsMap.put("advancer", w.advancer());
        orchMap.put("ideaQueueWeights", weightsMap);

        root.put("orchestration", orchMap);

        // agents
        AutoIdeatorConfig.AgentsConfig agents = cfg.agents();
        Map<String, Object> agentsMap = new LinkedHashMap<>();
        agentsMap.put("planner", agentConfigToMap(agents.planner()));
        agentsMap.put("coder", agentConfigToMap(agents.coder()));
        agentsMap.put("reviewer", agentConfigToMap(agents.reviewer()));
        agentsMap.put("tester", agentConfigToMap(agents.tester()));
        agentsMap.put("git", agentConfigToMap(agents.git()));
        root.put("agents", agentsMap);

        // git
        AutoIdeatorConfig.GitConfig git = cfg.git();
        Map<String, Object> gitMap = new LinkedHashMap<>();
        gitMap.put("autoCommit", git.autoCommit());
        gitMap.put("improvementBranchPrefix", git.improvementBranchPrefix());
        gitMap.put("commitFormat", git.commitFormat());
        root.put("git", gitMap);

        // selfImprovement
        AutoIdeatorConfig.SelfImprovementConfig si = cfg.selfImprovement();
        Map<String, Object> siMap = new LinkedHashMap<>();
        siMap.put("enabled", si.enabled());
        siMap.put("scanIntervalMinutes", si.scanInterval() != null ? si.scanInterval().toMinutes() : 30);
        siMap.put("maxImprovementsPerCycle", si.maxImprovementsPerCycle());
        root.put("selfImprovement", siMap);

        // logging
        AutoIdeatorConfig.LoggingConfig log = cfg.logging();
        Map<String, Object> logMap = new LinkedHashMap<>();
        logMap.put("level", log.level());
        logMap.put("file", log.file());
        logMap.put("maxSizeMb", log.maxSizeMb());
        logMap.put("backupCount", log.backupCount());
        root.put("logging", logMap);

        root.put("dryRun", cfg.dryRun());

        return root;
    }

    private Map<String, Object> agentConfigToMap(AutoIdeatorConfig.AgentConfig ac) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", ac.enabled());
        m.put("maxIterations", ac.maxIterations());
        return m;
    }

    @SuppressWarnings("unchecked")
    private AutoIdeatorConfig applyUpdates(AutoIdeatorConfig current, Map<String, Object> updates) {
        AutoIdeatorConfig.LlmConfig currentLlm = current.llm();

        // Parse llm section
        AutoIdeatorConfig.LlmConfig newLlm = currentLlm;
        if (updates.containsKey("llm")) {
            Map<String, Object> llmUpdates = (Map<String, Object>) updates.get("llm");

            String backend = getString(llmUpdates, "backend", currentLlm.backend());
            String model = getString(llmUpdates, "model", currentLlm.model());
            long timeoutSec = getLong(llmUpdates, "timeoutSeconds",
                currentLlm.timeout() != null ? currentLlm.timeout().getSeconds() : 120);

            // OpenRouter
            AutoIdeatorConfig.OpenRouterConfig newOr = currentLlm.openRouter();
            if (llmUpdates.containsKey("openRouter")) {
                Map<String, Object> orUpd = (Map<String, Object>) llmUpdates.get("openRouter");
                String orApiKey = getString(orUpd, "apiKey", newOr.apiKey());
                // Ignore masked values — don't overwrite real key with "sk-1****abcd"
                if (orApiKey != null && orApiKey.contains("****")) {
                    orApiKey = newOr.apiKey();
                }
                newOr = new AutoIdeatorConfig.OpenRouterConfig(
                    orApiKey,
                    getString(orUpd, "baseUrl", newOr.baseUrl()),
                    getString(orUpd, "model", newOr.model())
                );
            }

            // ClaudeCli
            AutoIdeatorConfig.CliConfig newCc = currentLlm.claudeCli();
            if (llmUpdates.containsKey("claudeCli")) {
                Map<String, Object> ccUpd = (Map<String, Object>) llmUpdates.get("claudeCli");
                newCc = new AutoIdeatorConfig.CliConfig(
                    getString(ccUpd, "path", newCc.path()),
                    toStringArray(ccUpd.getOrDefault("args", Arrays.asList(newCc.args())))
                );
            }

            // OpencodeCli
            AutoIdeatorConfig.CliConfig newOc = currentLlm.opencodeCli();
            if (llmUpdates.containsKey("opencodeCli")) {
                Map<String, Object> ocUpd = (Map<String, Object>) llmUpdates.get("opencodeCli");
                newOc = new AutoIdeatorConfig.CliConfig(
                    getString(ocUpd, "path", newOc.path()),
                    toStringArray(ocUpd.getOrDefault("args", Arrays.asList(newOc.args())))
                );
            }

            // CustomClaudeCli
            AutoIdeatorConfig.CustomClaudeCliConfig newCcc = currentLlm.customClaudeCli();
            if (llmUpdates.containsKey("customClaudeCli")) {
                Map<String, Object> cccUpd = (Map<String, Object>) llmUpdates.get("customClaudeCli");
                String cccApiKey = nullIfBlank(getString(cccUpd, "apiKey", newCcc.apiKey()));
                // Ignore masked values — don't overwrite real key with "****"
                if (cccApiKey != null && cccApiKey.contains("****")) {
                    cccApiKey = newCcc.apiKey();
                }
                newCcc = new AutoIdeatorConfig.CustomClaudeCliConfig(
                    getString(cccUpd, "path", newCcc.path()),
                    cccApiKey,
                    nullIfBlank(getString(cccUpd, "baseUrl", newCcc.baseUrl())),
                    nullIfBlank(getString(cccUpd, "model", newCcc.model())),
                    nullIfBlank(getString(cccUpd, "haikuModel", newCcc.haikuModel())),
                    nullIfBlank(getString(cccUpd, "sonnetModel", newCcc.sonnetModel())),
                    nullIfBlank(getString(cccUpd, "opusModel", newCcc.opusModel())),
                    getBool(cccUpd, "dangerouslySkipPermissions", newCcc.dangerouslySkipPermissions()),
                    toStringArray(cccUpd.getOrDefault("args", Arrays.asList(newCcc.args())))
                );
            }

            newLlm = new AutoIdeatorConfig.LlmConfig(
                backend, model, Duration.ofSeconds(timeoutSec),
                newOr, newCc, newOc, newCcc
            );
        }

        // Parse orchestration section
        AutoIdeatorConfig.OrchestrationConfig currentOrch = current.orchestration();
        AutoIdeatorConfig.OrchestrationConfig newOrch = currentOrch;
        if (updates.containsKey("orchestration")) {
            Map<String, Object> orchUpd = (Map<String, Object>) updates.get("orchestration");

            AutoIdeatorConfig.IdeaQueueWeights currentWeights = currentOrch.ideaQueueWeights();
            AutoIdeatorConfig.IdeaQueueWeights newWeights = currentWeights;
            if (orchUpd.containsKey("ideaQueueWeights")) {
                Map<String, Object> wUpd = (Map<String, Object>) orchUpd.get("ideaQueueWeights");
                newWeights = new AutoIdeatorConfig.IdeaQueueWeights(
                    getInt(wUpd, "dreamer",  currentWeights.dreamer()),
                    getInt(wUpd, "artist",   currentWeights.artist()),
                    getInt(wUpd, "refiner",  currentWeights.refiner()),
                    getInt(wUpd, "hacker",   currentWeights.hacker()),
                    getInt(wUpd, "obsessor", currentWeights.obsessor()),
                    getInt(wUpd, "advancer", currentWeights.advancer())
                );
            }

            long commitIntervalMin = currentOrch.commitInterval() != null ? currentOrch.commitInterval().toMinutes() : 5;
            Duration newCommitInterval = Duration.ofMinutes(getLong(orchUpd, "commitIntervalMinutes", commitIntervalMin));

            newOrch = new AutoIdeatorConfig.OrchestrationConfig(
                getInt(orchUpd, "planRefinementCycles", currentOrch.planRefinementCycles()),
                getInt(orchUpd, "maxConcurrentAgents", currentOrch.maxConcurrentAgents()),
                getInt(orchUpd, "maxConcurrentCoders", currentOrch.maxConcurrentCoders()),
                getBool(orchUpd, "parallelExecution", currentOrch.parallelExecution()),
                newCommitInterval,
                getBool(orchUpd, "hackerEnabled", currentOrch.hackerEnabled()),
                getInt(orchUpd, "synthesizeInterval", currentOrch.synthesizeInterval()),
                getInt(orchUpd, "minGoalAlignment", currentOrch.minGoalAlignment()),
                getInt(orchUpd, "minOverallScore", currentOrch.minOverallScore()),
                getBool(orchUpd, "sandboxEnabled", currentOrch.sandboxEnabled()),
                newWeights
            );
        }

        // Parse agents section
        AutoIdeatorConfig.AgentsConfig currentAgents = current.agents();
        AutoIdeatorConfig.AgentsConfig newAgents = currentAgents;
        if (updates.containsKey("agents")) {
            Map<String, Object> agUpd = (Map<String, Object>) updates.get("agents");
            newAgents = new AutoIdeatorConfig.AgentsConfig(
                parseAgentConfig(agUpd, "planner", currentAgents.planner()),
                parseAgentConfig(agUpd, "coder", currentAgents.coder()),
                parseAgentConfig(agUpd, "reviewer", currentAgents.reviewer()),
                parseAgentConfig(agUpd, "tester", currentAgents.tester()),
                parseAgentConfig(agUpd, "git", currentAgents.git())
            );
        }

        // Parse git section
        AutoIdeatorConfig.GitConfig currentGit = current.git();
        AutoIdeatorConfig.GitConfig newGit = currentGit;
        if (updates.containsKey("git")) {
            Map<String, Object> gitUpd = (Map<String, Object>) updates.get("git");
            newGit = new AutoIdeatorConfig.GitConfig(
                getBool(gitUpd, "autoCommit", currentGit.autoCommit()),
                getString(gitUpd, "improvementBranchPrefix", currentGit.improvementBranchPrefix()),
                getString(gitUpd, "commitFormat", currentGit.commitFormat())
            );
        }

        // Parse selfImprovement section
        AutoIdeatorConfig.SelfImprovementConfig currentSi = current.selfImprovement();
        AutoIdeatorConfig.SelfImprovementConfig newSi = currentSi;
        if (updates.containsKey("selfImprovement")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> siUpd = (Map<String, Object>) updates.get("selfImprovement");
            long scanMin = currentSi.scanInterval() != null ? currentSi.scanInterval().toMinutes() : 30;
            newSi = new AutoIdeatorConfig.SelfImprovementConfig(
                getBool(siUpd, "enabled", currentSi.enabled()),
                Duration.ofMinutes(getLong(siUpd, "scanIntervalMinutes", scanMin)),
                getInt(siUpd, "maxImprovementsPerCycle", currentSi.maxImprovementsPerCycle())
            );
        }

        // Parse logging section
        AutoIdeatorConfig.LoggingConfig currentLog = current.logging();
        AutoIdeatorConfig.LoggingConfig newLog = currentLog;
        if (updates.containsKey("logging")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> logUpd = (Map<String, Object>) updates.get("logging");
            newLog = new AutoIdeatorConfig.LoggingConfig(
                getString(logUpd, "level", currentLog.level()),
                getString(logUpd, "file", currentLog.file()),
                getInt(logUpd, "maxSizeMb", currentLog.maxSizeMb()),
                getInt(logUpd, "backupCount", currentLog.backupCount())
            );
        }

        return new AutoIdeatorConfig(
            newLlm, newOrch, newSi, newAgents, newGit, newLog,
            current.dryRun(), current.workingDir()
        );
    }

    @SuppressWarnings("unchecked")
    private AutoIdeatorConfig.AgentConfig parseAgentConfig(
            Map<String, Object> parent, String key, AutoIdeatorConfig.AgentConfig current) {
        if (!parent.containsKey(key)) return current;
        Map<String, Object> m = (Map<String, Object>) parent.get(key);
        return new AutoIdeatorConfig.AgentConfig(
            getBool(m, "enabled", current.enabled()),
            getInt(m, "maxIterations", current.maxIterations())
        );
    }

    private String getString(Map<String, Object> m, String key, String defaultVal) {
        Object v = m.get(key);
        return v != null ? v.toString() : defaultVal;
    }

    private int getInt(Map<String, Object> m, String key, int defaultVal) {
        Object v = m.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return defaultVal; }
    }

    private long getLong(Map<String, Object> m, String key, long defaultVal) {
        Object v = m.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return defaultVal; }
    }

    private boolean getBool(Map<String, Object> m, String key, boolean defaultVal) {
        Object v = m.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private String maskSecret(String secret) {
        if (secret == null) return null;
        if (secret.length() <= 20) return "****";
        return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    @SuppressWarnings("unchecked")
    private String[] toStringArray(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(Object::toString).toArray(String[]::new);
        }
        return new String[0];
    }

    public void stop() {
        // Persist state before tearing down so a restart can restore it
        saveState();

        // Save checkpoint if orchestration is running — program exit is a pause
        saveCheckpointOnExit();

        startConfigLock.lock();
        try {
            // Nullify the orchestrator reference first so stale async completion
            // handlers detect the change and skip their broadcasts.
            DirectorOrchestrator orch = orchestrator.getAndSet(null);
            isRunning.set(false);

            if (orch != null) {
                orch.stop();
            }

            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            if (orch != null) {
                orch.shutdown();
            }
        } finally {
            startConfigLock.unlock();
        }

        if (app != null) {
            app.stop();
        }
    }

    public int getPort() {
        return port;
    }

    public String getUrl() {
        return "http://127.0.0.1:" + port;
    }

    // ── State persistence for live-reload ────────────────────────────────────

    /**
     * Save an orchestration checkpoint when the program is exiting.
     * Unlike an explicit stop (which clears the checkpoint), a program exit
     * preserves the checkpoint so orchestration can resume on next start.
     *
     * <p>If a checkpoint was already auto-saved at the end of the last completed
     * cycle, this is a redundant (but harmless) overwrite with the same state.
     * If the orchestrator is mid-cycle, {@code cycleCount} still reflects the
     * last <em>completed</em> cycle (it is only promoted after all phases finish),
     * so the incomplete cycle will be re-run from Phase 1 on next start.
     */
    private void saveCheckpointOnExit() {
        DirectorOrchestrator orch = orchestrator.get();
        Idea idea = currentIdea.get();
        if (orch != null && idea != null && isRunning.get()) {
            try {
                OrchestrationCheckpoint checkpoint = orch.captureCheckpoint(idea);
                checkpointManager.save(checkpoint);
                LOG.info("Checkpoint saved on exit for {} (cycle {})",
                    idea.workingDirectory(), checkpoint.cycleCount());
            } catch (Exception e) {
                LOG.warn("Failed to save checkpoint on exit: {}", e.getMessage());
            }
        }
    }

    public void saveState() {
        Path tmp = null;
        try {
            Idea idea = currentIdea.get();
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("idea", idea != null ? idea.description() : null);
            state.put("workingDir", idea != null ? idea.workingDirectory().toString() : null);
            state.put("cycleCount", EventBroadcaster.getInstance().getCycleCount());
            state.put("totalTokens", EventBroadcaster.getInstance().getTotalTokens());
            state.put("config", configToMap(liveConfig.get()));

            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(state);

            // Write to temp file then atomic rename to prevent corruption on crash
            tmp = STATE_FILE.resolveSibling(STATE_FILE.getFileName() + ".tmp");
            java.nio.file.Files.writeString(tmp, json, java.nio.charset.StandardCharsets.UTF_8);
            try {
                java.nio.file.Files.move(tmp, STATE_FILE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp, STATE_FILE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null; // rename succeeded, don't delete in finally
            LOG.info("Dashboard state saved to {}", STATE_FILE);
        } catch (Exception e) {
            LOG.warn("Failed to save dashboard state: {}", e.getMessage());
        } finally {
            if (tmp != null) {
                try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Restore dashboard state from {@code ~/.autoideator-state.json}.
     * Restores the idea, working directory, and dashboard counters.
     * Does NOT restart orchestration — the user clicks Start/Resume manually.
     */
    public void restoreState() {
        if (!java.nio.file.Files.exists(STATE_FILE)) return;
        try {
            String json = java.nio.file.Files.readString(STATE_FILE);
            @SuppressWarnings("unchecked")
            Map<String, Object> state = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, Map.class);

            String ideaText = (String) state.get("idea");
            String workingDir = (String) state.get("workingDir");
            if (ideaText != null && workingDir != null) {
                currentIdea.set(new Idea(ideaText, Path.of(workingDir)));
                LOG.info("Restored idea: {}", ideaText);
            }

            Object cycleObj = state.get("cycleCount");
            Object tokensObj = state.get("totalTokens");
            if (cycleObj instanceof Number n) {
                EventBroadcaster.getInstance().setCycleCount(n.intValue());
            }
            if (tokensObj instanceof Number n) {
                EventBroadcaster.getInstance().setTotalTokens(n.longValue());
            }

            LOG.info("Dashboard state restored from {}", STATE_FILE);
        } catch (Exception e) {
            LOG.warn("Failed to restore dashboard state: {}", e.getMessage());
        }
    }
}
