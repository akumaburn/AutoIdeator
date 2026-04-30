package com.autoideator.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.autoideator.orchestrator.DirectorOrchestrator;
import com.autoideator.orchestrator.DirectorOrchestrator.CycleResult;

/**
 * Broadcasts events to connected WebSocket clients.
 * Manages real-time updates for the dashboard.
 * Uses eager singleton initialization - thread-safe by design.
 */
public class EventBroadcaster {

    private static final Logger LOG = LoggerFactory.getLogger(EventBroadcaster.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final int MAX_HISTORY = 100;

    /** Maximum size (in characters) for a single agent's stored output. */
    private static final int MAX_OUTPUT_LENGTH = 500_000;
    private static final int MAX_CODER_OUTPUT_HISTORY = 100;
    private static final int MAX_AGENT_OUTPUT_HISTORY = 100;
    private static final int MAX_RETRY_ATTEMPTS_PER_AGENT = 50;

    // Eager singleton - thread-safe by JVM class loading guarantees
    private static final EventBroadcaster INSTANCE = new EventBroadcaster();

    private final List<WsContext> clients = new CopyOnWriteArrayList<>();
    private final Map<String, AgentState> agentStates = new ConcurrentHashMap<>();

    /**
     * Reference to the orchestrator that manages this broadcaster.
     * Used for re-populating event history from cycle results after a reset.
     */
    private volatile DirectorOrchestrator orchestrator;

    /**
     * Generation counter incremented on each {@link #reset()}.
     * Used to discard stale events that arrive after a reset (e.g., from a
     * previous orchestrator's in-flight agents completing during shutdown).
     */
    private final AtomicLong generation = new AtomicLong(0);

    /**
     * Per-cycle output history per agent, keyed by lowercase agent name.
     * Stores up to {@link #MAX_AGENT_OUTPUT_HISTORY} entries per agent (FIFO eviction).
     * Cleared on {@link #reset()}.
     */
    private final Map<String, Deque<AgentOutput>> agentFullOutputs = new ConcurrentHashMap<>();
    private final Map<String, Deque<CoderOutput>> coderOutputs = new ConcurrentHashMap<>();
    
    /**
     * Retry attempt history per agent, keyed by lowercase agent name.
     * Stores up to {@link #MAX_RETRY_ATTEMPTS_PER_AGENT} entries per agent.
     * Cleared on {@link #reset()}.
     */
    private final Map<String, Deque<RetryAttempt>> retryAttempts = new ConcurrentHashMap<>();

    /**
     * Pending stream chunks per agent, accumulated between flush ticks.
     * Each LLM output line/token triggers a chunk; batching reduces WebSocket messages.
     */
    private final ConcurrentHashMap<String, StringBuffer> pendingStreamChunks = new ConcurrentHashMap<>();

    /** Periodic flusher that batches accumulated stream chunks into single WebSocket messages. */
    private final ScheduledExecutorService chunkFlusher;

    private final AtomicInteger activeAgents = new AtomicInteger(0);
    private final AtomicInteger cycleCount = new AtomicInteger(0);
    private final AtomicLong totalTokens = new AtomicLong(0);
    private volatile Instant startTime = Instant.now();

    // Protects multi-field reset() from interleaving with broadcastSystemStats()
    private final ReentrantReadWriteLock statsLock = new ReentrantReadWriteLock();

    // Event history for new clients - using synchronized access
    private final LinkedList<AgentEvent> recentEvents = new LinkedList<>();

    public static EventBroadcaster getInstance() {
        return INSTANCE;
    }

    /**
     * Set the orchestrator reference.
     * This allows re-populating event history from cycle results after a reset.
     * 
     * @param orchestrator the orchestrator managing this broadcaster
     */
    public void setOrchestrator(DirectorOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Re-populate the event history from cycle results.
     * Called after reset() when a new orchestrator is assigned.
     * 
     * @param orchestrator the orchestrator to read cycle history from
     */
    public void repopulateEventHistory(DirectorOrchestrator orchestrator) {
        if (orchestrator == null) {
            LOG.debug("repopulateEventHistory called with null orchestrator - skipping");
            return;
        }
        
        synchronized (recentEvents) {
            try {
                // Capture the current generation before reading cycles
                long currentGen = generation.get();
                
                // Read all cycle results and convert to AgentEvents
                List<CycleResult> cycles = orchestrator.getCycleHistory();
                if (cycles == null || cycles.isEmpty()) {
                    LOG.debug("No cycle history found to repopulate");
                    return;
                }
                
                LOG.info("Repopulating event history from {} cycles", cycles.size());

                int totalEvents = 0;
                for (CycleResult cycle : cycles) {
                    List<AgentEvent> cycleEvents = cycleToAgentEvents(cycle);
                    totalEvents += cycleEvents.size();
                    for (AgentEvent event : cycleEvents) {
                        EventBroadcaster.getInstance().broadcastEvent(event, currentGen);
                    }
                }

                broadcast("event-history-repopulated", Map.of(
                    "timestamp", Instant.now().toString(),
                    "count", cycles.size()
                ));

                LOG.info("Repopulated {} events from {} cycles", totalEvents, cycles.size());
                    
            } catch (Exception e) {
                LOG.error("Error repopulating event history", e);
            }
        }
    }

    /**
     * Convert a CycleResult to a list of AgentEvents.
     * Reconstructs the events that would have occurred during that cycle.
     * 
     * @param cycle the cycle result to convert
     * @return list of AgentEvents representing the cycle
     */
    private List<AgentEvent> cycleToAgentEvents(CycleResult cycle) {
        List<AgentEvent> events = new ArrayList<>();
        
        // STARTED event for the whole cycle
        events.add(AgentEvent.builder()
            .agentName("Orchestrator").type(AgentEvent.EventType.STARTED)
            .phase("Cycle " + cycle.cycleNumber())
            .message("Starting cycle " + cycle.cycleNumber())
            .timestamp(Instant.now())
            .build());
        
        // Idea generation event
        if (cycle.ideaContent() != null && !cycle.ideaContent().isEmpty()) {
            events.add(AgentEvent.builder()
                .agentName(cycle.ideaAgentName() != null ? cycle.ideaAgentName() : "Unknown")
                .type(AgentEvent.EventType.COMPLETED)
                .phase("Idea Generation")
                .message("Generated idea from " + cycle.ideaAgentName())
                .details(cycle.ideaContent())
                .timestamp(Instant.now())
                .build());
        }
        
        // Idea scoring event
        events.add(AgentEvent.builder()
            .agentName("Scorer").type(AgentEvent.EventType.COMPLETED)
            .phase("Idea Scoring")
            .message("Evaluated idea quality")
            .timestamp(Instant.now())
            .build());
        
        // Skeptic critique event
        if (cycle.skepticCritique() != null && !cycle.skepticCritique().isEmpty()) {
            events.add(AgentEvent.builder()
                .agentName("Skeptic").type(AgentEvent.EventType.COMPLETED)
                .phase("Critique")
                .message("Analyzed idea and provided mitigations")
                .details(cycle.skepticCritique())
                .timestamp(Instant.now())
                .build());
        }
        
        // Architect strategic evaluation event
        events.add(AgentEvent.builder()
            .agentName("Architect").type(AgentEvent.EventType.COMPLETED)
            .phase("Strategic Evaluation")
            .message("Evaluated strategic alignment")
            .timestamp(Instant.now())
            .build());
        
        // Director decision event
        if (cycle.directorPlan() != null && !cycle.directorPlan().isEmpty()) {
            events.add(AgentEvent.builder()
                .agentName("Director").type(AgentEvent.EventType.COMPLETED)
                .phase("Decision")
                .message("Created implementation plan")
                .details(cycle.directorPlan())
                .timestamp(Instant.now())
                .build());
        }
        
        // Coder execution events
        int tasksCompleted = cycle.tasksCompleted();
        int tasksAttempted = cycle.tasksAttempted();
        if (tasksCompleted > 0 || tasksAttempted > 0) {
            events.add(AgentEvent.builder()
                .agentName("Coders").type(AgentEvent.EventType.COMPLETED)
                .phase("Implementation")
                .message("Completed " + tasksCompleted + "/" + tasksAttempted + " tasks")
                .details("Coder work completed for cycle " + cycle.cycleNumber())
                .timestamp(Instant.now())
                .build());
        }
        
        // Review and commit event
        events.add(AgentEvent.builder()
            .agentName("Reviewer").type(AgentEvent.EventType.COMPLETED)
            .phase("Review & Commit")
            .message("Reviewed and committed changes")
            .timestamp(Instant.now())
            .build());
        
        // QA verification event
        events.add(AgentEvent.builder()
            .agentName("QA").type(AgentEvent.EventType.COMPLETED)
            .phase("QA Verification")
            .message("Verified build and tests")
            .timestamp(Instant.now())
            .build());
        
        // Goal verification event
        events.add(AgentEvent.builder()
            .agentName("Verifier").type(AgentEvent.EventType.COMPLETED)
            .phase("Goal Verification")
            .message("Verified project goals")
            .timestamp(Instant.now())
            .build());

        // Organizer event (runs every other cycle)
        if (cycle.cycleNumber() % 2 == 0) {
            events.add(AgentEvent.builder()
                .agentName("Organizer").type(AgentEvent.EventType.COMPLETED)
                .phase("Organize")
                .message("Checked source file sizes")
                .timestamp(Instant.now())
                .build());
        }

        // CLEANUP event for the cycle
        events.add(AgentEvent.builder()
            .agentName("Cleaner").type(AgentEvent.EventType.COMPLETED)
            .phase("Cleanup")
            .message("Cleaned working directory")
            .timestamp(Instant.now())
            .build());
        
        // COMPLETED event for the whole cycle
        events.add(AgentEvent.builder()
            .agentName("Orchestrator").type(AgentEvent.EventType.COMPLETED)
            .phase("Cycle " + cycle.cycleNumber())
            .message("Cycle completed in " + cycle.duration().toSeconds() + "s")
            .details("Cycle " + cycle.cycleNumber() + " finished successfully")
            .timestamp(Instant.now())
            .build());
        
        return events;
    }

    /**
     * Private constructor for eager singleton.
     * This is intentionally eager initialization for thread-safety.
     * Starts a daemon scheduled executor that flushes accumulated stream chunks
     * every 50ms, batching many small LLM output chunks into fewer WebSocket messages.
     */
    private EventBroadcaster() {
        chunkFlusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stream-chunk-flusher");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, throwable) ->
                LOG.error("Uncaught exception in stream chunk flusher", throwable));
            return t;
        });
        chunkFlusher.scheduleAtFixedRate(() -> {
            try {
                flushPendingStreamChunks();
            } catch (Throwable t) {
                LOG.error("Error flushing stream chunks", t);
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
    }

    /**
     * Reset all accumulated state for a fresh orchestration run.
     * Call this when starting a new orchestration to clear stale data from previous runs.
     * 
     * @param orchestrator Optional orchestrator reference - if provided, event history
     *                     will be re-populated from cycle results after clearing
     */
    public void reset(DirectorOrchestrator orchestrator) {
        reset(orchestrator, false);
    }

    /**
     * Reset all accumulated state for a fresh orchestration run.
     * Call this when starting a new orchestration to clear stale data from previous runs.
     * 
     * @param orchestrator Optional orchestrator reference - if provided, event history
     *                     will be re-populated from cycle results after clearing
     * @param repopulate Whether to re-populate event history from cycle results
     */
    public void reset(DirectorOrchestrator orchestrator, boolean repopulate) {
        statsLock.writeLock().lock();
        try {
            // Increment generation FIRST so any in-flight broadcastEvent calls
            // that captured the old generation will detect the change and skip
            // their state updates (preventing stale data from repopulating).
            generation.incrementAndGet();
            agentStates.clear();
            agentFullOutputs.clear();
            coderOutputs.clear();
            retryAttempts.clear();
            pendingStreamChunks.clear();
            activeAgents.set(0);
            cycleCount.set(0);
            totalTokens.set(0);
            startTime = Instant.now();
            synchronized (recentEvents) {
                recentEvents.clear();
            }
            LOG.debug("EventBroadcaster state reset (generation={})", generation.get());
        } finally {
            statsLock.writeLock().unlock();
        }

        // Re-populate event history if orchestrator provided and requested
        if (orchestrator != null && repopulate) {
            repopulateEventHistory(orchestrator);
        }

        // Notify all connected clients so they reset their local state
        broadcast("state-reset", Map.of("timestamp", Instant.now().toString()));
    }

    public void addClient(WsContext client) {
        clients.add(client);
        LOG.debug("WebSocket client connected. Total clients: {}", clients.size());

        // Send current state to new client
        sendInitialState(client);
    }

    public void removeClient(WsContext client) {
        clients.remove(client);
        LOG.debug("WebSocket client disconnected. Total clients: {}", clients.size());
    }

    private void sendInitialState(WsContext client) {
        try {
            // Acquire read lock to get a consistent snapshot of stats
            // (prevents torn reads if reset() is running concurrently)
            statsLock.readLock().lock();
            SystemStats stats;
            int cycle;
            long tokens;
            try {
                stats = SystemStats.current(
                    activeAgents.get(),
                    cycleCount.get(),
                    totalTokens.get(),
                    startTime
                );
                cycle = cycleCount.get();
                tokens = totalTokens.get();
            } finally {
                statsLock.readLock().unlock();
            }
            sendToClient(client, "system-stats", stats);

            // Send agent states
            sendToClient(client, "agent-states", Map.of("agents", new HashMap<>(agentStates)));

            // Send recent events
            synchronized (recentEvents) {
                sendToClient(client, "event-history", Map.of("events", new ArrayList<>(recentEvents)));
            }

            // Send current cycle count
            sendToClient(client, "cycle-complete", Map.of("cycle", cycle));

            // Send total tokens
            sendToClient(client, "token-total", Map.of("total", tokens));
        } catch (Exception e) {
            LOG.warn("Error sending initial state to client: {}", e.getMessage());
        }
    }

    public void sendToClient(WsContext client, String eventType, Object data) {
        try {
            Map<String, Object> message = Map.of("type", eventType, "payload", data);
            client.send(MAPPER.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            LOG.warn("Error serializing message for client: {}", e.getMessage());
        }
    }

    /**
     * Broadcast an event, checking that the expected generation still matches.
     * If a {@link #reset()} occurred after {@code expectedGeneration} was captured,
     * the event is discarded to prevent stale data from a previous run leaking in.
     *
     * @param event               the event to broadcast
     * @param expectedGeneration  the generation captured at cycle/phase start
     */
    public void broadcastEvent(AgentEvent event, long expectedGeneration) {
        if (generation.get() != expectedGeneration) {
            LOG.debug("Discarding stale event for {} (generation {} != current {})",
                event.agentName(), expectedGeneration, generation.get());
            return;
        }
        doBroadcastEvent(event);
    }

    /**
     * Broadcast an event without generation checking.
     * Prefer {@link #broadcastEvent(AgentEvent, long)} when a generation is available.
     */
    public void broadcastEvent(AgentEvent event) {
        doBroadcastEvent(event);
    }

    private void doBroadcastEvent(AgentEvent event) {
        // Update token count
        if (event.tokenUsage() != null) {
            totalTokens.addAndGet(event.tokenUsage().totalTokens());
        }

        // Update agent state
        updateAgentState(event);

        // Store in history - use addLast/removeFirst for O(1) with LinkedList
        synchronized (recentEvents) {
            recentEvents.addLast(event);
            while (recentEvents.size() > MAX_HISTORY) {
                recentEvents.removeFirst();
            }
        }

        // Broadcast to all clients
        broadcast("agent-event", event);
    }

    private void updateAgentState(AgentEvent event) {
        String agentName = event.agentName();
        if (agentName == null || event.type() == null) return;

        AgentState.Status newStatus = switch (event.type()) {
            case STARTED, IN_PROGRESS, THINKING, TOOL_USE -> AgentState.Status.ACTIVE;
            case COMPLETED -> AgentState.Status.IDLE;
            case FAILED -> AgentState.Status.ERROR;
            case WAITING -> AgentState.Status.WAITING;
            case RETRY -> AgentState.Status.ACTIVE;
            case PAUSED -> AgentState.Status.WAITING;
        };

        // Use compute for atomic update to avoid race condition
        AgentState newState = new AgentState(
            agentName,
            newStatus,
            event.phase(),
            event.message(),
            event.durationMs(),
            event.timestamp()
        );
        agentStates.put(agentName, newState);

        // Update active count
        long active = agentStates.values().stream()
            .filter(s -> s.status() == AgentState.Status.ACTIVE)
            .count();
        activeAgents.set((int) active);
    }

    public void incrementCycle() {
        int newCycle = cycleCount.incrementAndGet();
        broadcast("cycle-complete", Map.of("cycle", newCycle));
    }

    /**
     * Set the absolute current cycle number and broadcast it.
     * Uses monotonic update to avoid accidental counter regression.
     */
    public void setCycleCount(int cycle) {
        int updated = cycleCount.updateAndGet(existing -> Math.max(existing, cycle));
        broadcast("cycle-complete", Map.of("cycle", updated));
    }

    public void broadcastSystemStats() {
        SystemStats stats;
        statsLock.readLock().lock();
        try {
            stats = SystemStats.current(
                activeAgents.get(),
                cycleCount.get(),
                totalTokens.get(),
                startTime
            );
        } finally {
            statsLock.readLock().unlock();
        }
        broadcast("system-stats", stats);
    }

    /**
     * Accumulate a streaming output chunk for an agent.
     * Chunks are batched and flushed every ~50ms by the chunk flusher,
     * reducing WebSocket message count during high-frequency streaming.
     * Called from LLM client output reader threads as lines are produced.
     *
     * @param agentName          the agent that produced the chunk
     * @param chunk              the output chunk (typically one line)
     * @param expectedGeneration generation guard — stale chunks are discarded
     */
    public void broadcastStreamChunk(String agentName, String chunk, long expectedGeneration) {
        if (agentName == null || chunk == null) return;
        if (generation.get() != expectedGeneration) return;
        pendingStreamChunks.computeIfAbsent(agentName, k -> new StringBuffer()).append(chunk);
    }

    /**
     * Flush accumulated stream chunks for all agents.
     * Called periodically by the chunk flusher to batch multiple small chunks
     * into a single WebSocket message per agent, reducing message overhead.
     *
     * <p>Captures the generation at the start and checks again after draining each
     * buffer. If a {@link #reset()} occurred in between (generation changed), the
     * drained chunk is discarded to prevent stale data from reaching clients.
     */
    private void flushPendingStreamChunks() {
        long genSnapshot = generation.get();
        pendingStreamChunks.forEach((agentName, buf) -> {
            String accumulated;
            synchronized (buf) {
                if (buf.length() == 0) return;
                accumulated = buf.toString();
                buf.setLength(0);
            }
            // If a reset() occurred after the chunk was accumulated, discard it
            if (generation.get() != genSnapshot) {
                return;
            }
            broadcast("agent-stream", Map.of(
                "agentName", agentName,
                "chunk", accumulated,
                "timestamp", Instant.now().toString()
            ));
        });
    }

    public void broadcastStatus(String status, String message) {
        boolean running = "running".equalsIgnoreCase(status) || "starting".equalsIgnoreCase(status);
        broadcast("orchestrator-status", Map.of(
            "status", status,
            "message", message,
            "running", running,
            "cycleCount", cycleCount.get(),
            "totalTokens", totalTokens.get(),
            "timestamp", Instant.now().toString()
        ));
    }

    public void broadcast(String eventType, Object data) {
        // Serialize once for all clients instead of per-client
        String serialized;
        try {
            serialized = MAPPER.writeValueAsString(Map.of("type", eventType, "payload", data));
        } catch (JsonProcessingException e) {
            LOG.warn("Error serializing broadcast message: {}", e.getMessage());
            return;
        }

        List<WsContext> deadClients = new ArrayList<>();
        for (WsContext client : clients) {
            try {
                client.send(serialized);
            } catch (Exception e) {
                LOG.warn("Error broadcasting to client, removing: {}", e.getMessage());
                deadClients.add(client);
            }
        }
        if (!deadClients.isEmpty()) {
            clients.removeAll(deadClients);
        }
    }

    public SystemStats getCurrentStats() {
        statsLock.readLock().lock();
        try {
            return SystemStats.current(
                activeAgents.get(),
                cycleCount.get(),
                totalTokens.get(),
                startTime
            );
        } finally {
            statsLock.readLock().unlock();
        }
    }

    public Map<String, AgentState> getAgentStates() {
        return new HashMap<>(agentStates);
    }

    public int getCycleCount() {
        return cycleCount.get();
    }

    public long getTotalTokens() {
        return totalTokens.get();
    }

    public void setTotalTokens(long tokens) {
        totalTokens.set(tokens);
    }

    /**
     * Returns the current generation counter.
     * Callers should capture this at the start of a logical operation and pass
     * it to {@link #broadcastEvent(AgentEvent, long)} etc. to guard against stale writes.
     */
    public long getGeneration() {
        return generation.get();
    }

    /**
     * Store per-cycle output for an agent, with generation guard.
     *
     * @param agentName        the agent name (stored lowercase)
     * @param cycle            the cycle number when the agent ran
     * @param phase            the phase label (e.g. "Critique", "Score")
     * @param fullOutput       the complete LLM response text
     * @param expectedGeneration generation guard — stale writes are discarded
     */
    public void storeAgentOutput(String agentName, int cycle, String phase,
                                  String fullOutput, long expectedGeneration) {
        if (agentName == null || fullOutput == null) return;
        if (generation.get() != expectedGeneration) return;
        doStoreAgentOutput(agentName, cycle, phase, fullOutput);
    }

    private void doStoreAgentOutput(String agentName, int cycle, String phase, String fullOutput) {
        String key = agentName.toLowerCase();
        String capped;
        if (fullOutput.length() > MAX_OUTPUT_LENGTH) {
            String suffix = "\n\n... (output capped at " + MAX_OUTPUT_LENGTH + " characters)";
            int contentLength = Math.max(0, MAX_OUTPUT_LENGTH - suffix.length());
            capped = fullOutput.substring(0, contentLength) + suffix;
        } else {
            capped = fullOutput;
        }
        Deque<AgentOutput> history = agentFullOutputs.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        history.addLast(new AgentOutput(agentName, cycle, phase != null ? phase : "", capped, Instant.now()));
        while (history.size() > MAX_AGENT_OUTPUT_HISTORY) {
            history.removeFirst();
        }
    }

    /**
     * Store cycle-aware full output for an individual coder, with generation guard.
     */
    public void storeCoderOutput(String coderName, int cycle, String taskSummary, String fullOutput, boolean success, long expectedGeneration) {
        if (coderName == null || fullOutput == null) return;
        if (generation.get() != expectedGeneration) return;
        doStoreCoderOutput(coderName, cycle, taskSummary, fullOutput, success);
    }

    /**
     * Store cycle-aware full output for an individual coder without generation checking.
     * 
     * WARNING: This overload does NOT check generation. Use withGeneration() overload
     * when calling from orchestrator to prevent storing stale data after reset.
     */
    public void storeCoderOutput(String coderName, int cycle, String taskSummary, String fullOutput, boolean success) {
        if (coderName == null || fullOutput == null) return;
        doStoreCoderOutput(coderName, cycle, taskSummary, fullOutput, success);
    }

    private void doStoreCoderOutput(String coderName, int cycle, String taskSummary, String fullOutput, boolean success) {
        String key = coderName.toLowerCase();
        String capped;
        if (fullOutput.length() > MAX_OUTPUT_LENGTH) {
            String suffix = "\n\n... (output capped at " + MAX_OUTPUT_LENGTH + " characters)";
            int contentLength = Math.max(0, MAX_OUTPUT_LENGTH - suffix.length());
            capped = fullOutput.substring(0, contentLength) + suffix;
        } else {
            capped = fullOutput;
        }
        Deque<CoderOutput> history = coderOutputs.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        history.addLast(new CoderOutput(coderName, cycle, taskSummary, capped, success, Instant.now()));
        while (history.size() > MAX_CODER_OUTPUT_HISTORY) {
            history.removeFirst();
        }
    }

    /**
     * Retrieve coder output for a specific cycle, or latest if cycle is null.
     */
    public CoderOutput getCoderOutput(String coderName, Integer cycle) {
        if (coderName == null) return null;
        Deque<CoderOutput> history = coderOutputs.get(coderName.toLowerCase());
        if (history == null || history.isEmpty()) return null;

        if (cycle == null) {
            return history.peekLast();
        }

        Iterator<CoderOutput> it = history.descendingIterator();
        while (it.hasNext()) {
            CoderOutput out = it.next();
            if (out.cycle() == cycle) return out;
        }
        return null;
    }

    /**
     * Retrieve recent coder output history (newest first).
     */
    public List<CoderOutput> getCoderOutputHistory(String coderName, int limit) {
        if (coderName == null) return List.of();
        Deque<CoderOutput> history = coderOutputs.get(coderName.toLowerCase());
        if (history == null || history.isEmpty()) return List.of();

        List<CoderOutput> out = new ArrayList<>();
        Iterator<CoderOutput> it = history.descendingIterator();
        while (it.hasNext() && out.size() < Math.max(0, limit)) {
            out.add(it.next());
        }
        return out;
    }

    /**
     * Retrieve agent output for a specific cycle, or the latest if {@code cycle} is null.
     *
     * @param agentName the agent name (case-insensitive)
     * @param cycle     the cycle to retrieve, or null for the most recent
     * @return the stored output, or null if none exists
     */
    public AgentOutput getAgentOutput(String agentName, Integer cycle) {
        if (agentName == null) return null;
        Deque<AgentOutput> history = agentFullOutputs.get(agentName.toLowerCase());
        if (history == null || history.isEmpty()) return null;

        if (cycle == null) {
            return history.peekLast();
        }
        Iterator<AgentOutput> it = history.descendingIterator();
        while (it.hasNext()) {
            AgentOutput out = it.next();
            if (out.cycle() == cycle) return out;
        }
        return null;
    }

    /**
     * Convenience overload — returns the latest output for an agent.
     */
    public AgentOutput getAgentOutput(String agentName) {
        return getAgentOutput(agentName, null);
    }

    /**
     * Retrieve recent output history for an agent (newest first).
     */
    public List<AgentOutput> getAgentOutputHistory(String agentName, int limit) {
        if (agentName == null) return List.of();
        Deque<AgentOutput> history = agentFullOutputs.get(agentName.toLowerCase());
        if (history == null || history.isEmpty()) return List.of();

        List<AgentOutput> out = new ArrayList<>();
        Iterator<AgentOutput> it = history.descendingIterator();
        while (it.hasNext() && out.size() < Math.max(0, limit)) {
            out.add(it.next());
        }
        return out;
    }

    /**
     * Returns a summary of all stored agent outputs: agent name to character count (latest output).
     */
    public Map<String, Integer> getAgentOutputSummary() {
        Map<String, Integer> summary = new LinkedHashMap<>();
        agentFullOutputs.forEach((key, deque) -> {
            AgentOutput latest = deque.peekLast();
            if (latest != null) {
                summary.put(latest.agentName(), latest.content().length());
            }
        });
        return summary;
    }

    /**
     * Store a retry attempt for an agent, with generation guard.
     *
     * @param agentName        the agent name
     * @param cycle            the cycle number when the retry occurred
     * @param phase            the phase label (e.g. "Critique", "Implement")
     * @param attemptNumber    which retry attempt (1, 2, 3, ...)
     * @param error            the error message that triggered the retry
     * @param partialOutput    any partial output before failure (may be null)
     * @param expectedGeneration generation guard — stale writes are discarded
     */
    public void storeRetryAttempt(String agentName, int cycle, String phase,
                                   int attemptNumber, String error, String partialOutput,
                                   long expectedGeneration) {
        if (agentName == null || error == null) return;
        if (generation.get() != expectedGeneration) return;
        doStoreRetryAttempt(agentName, cycle, phase, attemptNumber, error, partialOutput);
    }

    private void doStoreRetryAttempt(String agentName, int cycle, String phase,
                                      int attemptNumber, String error, String partialOutput) {
        String key = agentName.toLowerCase();
        String cappedError = error.length() > 5000 ? error.substring(0, 5000) + "..." : error;
        String cappedOutput = partialOutput != null && partialOutput.length() > 50_000
            ? partialOutput.substring(0, 50_000) + "..."
            : partialOutput;
        
        Deque<RetryAttempt> history = retryAttempts.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        history.addLast(new RetryAttempt(agentName, cycle, phase, attemptNumber, cappedError, cappedOutput, Instant.now()));
        while (history.size() > MAX_RETRY_ATTEMPTS_PER_AGENT) {
            history.removeFirst();
        }
    }

    /**
     * Retrieve all retry attempts for an agent.
     *
     * @param agentName the agent name (case-insensitive)
     * @return list of retry attempts (oldest first)
     */
    public List<RetryAttempt> getRetryAttempts(String agentName) {
        if (agentName == null) return List.of();
        Deque<RetryAttempt> history = retryAttempts.get(agentName.toLowerCase());
        if (history == null || history.isEmpty()) return List.of();
        return new ArrayList<>(history);
    }

    /**
     * Retrieve retry attempts for an agent in a specific cycle.
     *
     * @param agentName the agent name (case-insensitive)
     * @param cycle     the cycle number
     * @return list of retry attempts for that cycle
     */
    public List<RetryAttempt> getRetryAttemptsForCycle(String agentName, int cycle) {
        if (agentName == null) return List.of();
        Deque<RetryAttempt> history = retryAttempts.get(agentName.toLowerCase());
        if (history == null || history.isEmpty()) return List.of();
        
        List<RetryAttempt> result = new ArrayList<>();
        for (RetryAttempt attempt : history) {
            if (attempt.cycle() == cycle) {
                result.add(attempt);
            }
        }
        return result;
    }

    /**
     * Get total retry count across all agents.
     */
    public int getTotalRetryCount() {
        int total = 0;
        for (Deque<RetryAttempt> attempts : retryAttempts.values()) {
            total += attempts.size();
        }
        return total;
    }

    /**
     * Full output stored for an agent after it completes a cycle phase.
     */
    public record AgentOutput(
        String agentName,
        int cycle,
        String phase,
        String content,
        Instant timestamp
    ) {}

    /**
     * Records a single retry attempt for an agent.
     */
    public record RetryAttempt(
        String agentName,
        int cycle,
        String phase,
        int attemptNumber,
        String error,
        String partialOutput,
        Instant timestamp
    ) {}

    /**
     * Full output for a specific coder run in a specific cycle.
     */
    public record CoderOutput(
        String coderName,
        int cycle,
        String taskSummary,
        String content,
        boolean success,
        Instant timestamp
    ) {}

    /**
     * Represents the current state of an agent.
     */
    public record AgentState(
        String name,
        Status status,
        String currentPhase,
        String currentTask,
        long lastDurationMs,
        Instant lastUpdate
    ) {
        public enum Status {
            IDLE("idle"),
            ACTIVE("active"),
            WAITING("waiting"),
            ERROR("error");

            private final String value;

            Status(String value) {
                this.value = value;
            }

            public String getValue() {
                return value;
            }
        }
    }
}
