package com.autoideator.orchestrator;

import com.autoideator.agent.*;
import com.autoideator.checkpoint.OrchestrationCheckpoint;
import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.git.GitOperations;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.*;
import com.autoideator.web.AgentEvent;
import com.autoideator.web.EventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Director-based orchestration engine that coordinates the multi-agent system.
 *
 * <p>Each cycle runs multiple phases:
 *
 * <ol>
 *   <li><b>Idea Generation</b> — If the Overseer has a pending user suggestion it runs
 *       instead of the IdeaQueue. Otherwise the IdeaQueue selects the next idea agent
 *       via weighted round-robin (Dreamer, Artist, Refiner, Hacker, Obsessor, Advancer).
 *       Weights are configurable — default D=1, A=2, R=1, H=1, O=5, Adv=5.
 *       When Artist's turn comes, the Maestro evaluates whether the project has a frontend;
 *       if not, Artist is skipped and the next agent runs instead.</li>
 *   <li><b>Idea Scoring</b> — Scorer evaluates idea quality (goal alignment, novelty, feasibility).
 *       Ideas below threshold are reshaped into goal-aligned alternatives; cycles are never aborted.</li>
 *   <li><b>Critique</b> — Skeptic analyzes the ideas for risks and weaknesses, suggests mitigations.
 *       Skipped on the first cycle only if the working directory has fewer than
 *       2 git commits (no meaningful prior work to critique).</li>
 *   <li><b>Strategic Evaluation</b> — Architect evaluates strategic alignment, dependencies, 
 *       and priority based on project phase and long-term goals.</li>
 *   <li><b>Decision</b> — Director synthesizes ideas, critique, and strategic input into a concrete
 *       implementation plan aligned with the project goal.</li>
 *   <li><b>Implementation</b> — Parallel Coders execute the plan (limited by
 *       {@code maxConcurrentCoders}).</li>
 *   <li><b>Review &amp; Commit</b> — Reviewer examines changes, fixes issues, and
 *       commits to git.</li>
 *   <li><b>QA (Build &amp; Test)</b> — QA agent verifies the project builds and tests pass.</li>
 *   <li><b>Test Optimization</b> — TestOptimizer identifies slow tests and optimizes or removes them.</li>
 *   <li><b>Goal Verification</b> — Three-step systematic check: (10a) Verifier inventories all
 *       project features into a checklist, (10b) each feature is individually verified in parallel,
 *       (10c) Coders are spawned to fix every feature that failed verification.</li>
 *   <li><b>Cleanup</b> — Cleaner removes temporary files, test output, build garbage,
 *       and other detritus from the working directory.</li>
 *   <li><b>Documentation</b> — Documenter regenerates README.md and project docs from source code.</li>
 *   <li><b>Outcome Recording</b> — Cycle outcome is recorded for learning and deduplication.</li>
 * </ol>
 *
 * <p>Every N cycles (configurable), the Synthesizer agent reviews recent cycles and
 * proposes merged/synthesized ideas that combine complementary improvements.
 *
 * <p>The cycle repeats infinitely until stopped via {@link #stop()} or a shutdown hook.
 */
public class DirectorOrchestrator {

    public enum OrchestratorState {
        IDLE, RUNNING, PAUSING, PAUSED
    }

    private static final Logger LOG = LoggerFactory.getLogger(DirectorOrchestrator.class);

    // Configuration constants
    private static final long CYCLE_PAUSE_MS = 1000;
    private static final long ERROR_RETRY_DELAY_MS = 5000;
    private static final int MAX_ERROR_COUNT_BEFORE_BACKOFF = 3;
    private static final long MAX_BACKOFF_DELAY_MS = 60000;
    private static final int MAX_CYCLE_HISTORY = 100;
    // Synthesizer interval is read from config.orchestration().synthesizeInterval()

    private volatile AutoIdeatorConfig config;
    private volatile LlmInterface llm;
    private final GitOperations gitOperations;
    private final ExecutorService executor;
    private final AtomicReference<OrchestratorState> state;
    private final java.util.concurrent.locks.ReentrantLock pauseLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition resumeCondition = pauseLock.newCondition();

    // Specialized agents
    private final OverseerAgent overseer;
    private final MaestroAgent maestro;
    private final DreamerAgent dreamer;
    private final ArtistAgent artist;
    private final RefinerAgent refiner;
    private final HackerAgent hacker;
    private final ObsessorAgent obsessor;
    private final AdvancerAgent advancer;
    private final GoalVerifierAgent goalVerifier;
    private final ScorerAgent scorer;
    private final SkepticAgent skeptic;
    private final ArchitectAgent architect;
    private final DirectorAgent director;
    private final CoderAgent coder;
    private final ReviewerAgent reviewer;
    private final QAAgent qaAgent;
    private final TestOptimizerAgent testOptimizer;
    private final CleanerAgent cleaner;
    private final OrganizerAgent organizer;
    private final DocumenterAgent documenter;
    private final SynthesizerAgent synthesizer;

    // Idea queue — round-robin selector for Phase 1 idea generation
    private final IdeaQueue ideaQueue;
    
    // Cycle history manager for learning and deduplication
    private final CycleHistoryManager historyManager;

    // Cycle tracking
    private final AtomicInteger cycleCount = new AtomicInteger(0);
    private final Deque<CycleResult> cycleHistory = new ConcurrentLinkedDeque<>();
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private volatile String latestSynthesisInsights;

    // The cycle number currently being executed (for logging, events, retry tracking).
    // Distinct from cycleCount, which only reflects completed cycles for checkpoint correctness.
    private volatile int activeCycle;

    // Project state — written on executor thread, potentially read by other threads
    private volatile ProjectPhase currentPhase;
    private volatile PhaseDetector.ProjectType projectType;

    // Generation counter captured at cycle start, used to discard stale events after reset()
    private volatile long currentGeneration;

    // Dreamer self-disable: set to true when the Dreamer determines all goals are met.
    // When true, the Dreamer's IdeaQueue slots are skipped and other agents continue.
    private volatile boolean dreamerSelfDisabled;

    // Checkpoint support
    private volatile OrchestrationCheckpoint pendingCheckpointRestore;
    private volatile java.util.function.Consumer<OrchestrationCheckpoint> checkpointSaver;

    public DirectorOrchestrator(AutoIdeatorConfig config, AtomicReference<String> overseerSuggestionRef) {
        this.config = config;
        this.llm = LlmInterface.create(config);
        try {
            this.gitOperations = new GitOperations(config);
            Thread.UncaughtExceptionHandler handler = (thread, throwable) -> 
                LOG.error("Uncaught exception in orchestrator virtual thread '{}': {}", 
                    thread.getName(), throwable.getMessage(), throwable);
            this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                    .uncaughtExceptionHandler(handler)
                    .factory());
            this.state = new AtomicReference<>(OrchestratorState.IDLE);
            this.historyManager = new CycleHistoryManager();

            // Initialize agents
            this.overseer  = new OverseerAgent(config, llm, overseerSuggestionRef);
            this.maestro   = new MaestroAgent(config, llm);
            this.dreamer   = new DreamerAgent(config, llm);
            this.artist    = new ArtistAgent(config, llm);
            this.refiner   = new RefinerAgent(config, llm);
            this.hacker    = new HackerAgent(config, llm);
            this.obsessor      = new ObsessorAgent(config, llm);
            this.advancer      = new AdvancerAgent(config, llm);
            this.goalVerifier  = new GoalVerifierAgent(config, llm);
            this.scorer        = new ScorerAgent(config, llm);
            this.skeptic   = new SkepticAgent(config, llm);
            this.architect = new ArchitectAgent(config, llm);
            this.director  = new DirectorAgent(config, llm);
            this.coder     = new CoderAgent(config, llm);
            this.reviewer  = new ReviewerAgent(config, llm);
            this.qaAgent        = new QAAgent(config, llm);
            this.testOptimizer  = new TestOptimizerAgent(config, llm);
            this.cleaner    = new CleanerAgent(config, llm);
            this.organizer  = new OrganizerAgent(config, llm);
            this.documenter = new DocumenterAgent(config, llm);
            this.synthesizer = new SynthesizerAgent(config, llm);

            // Idea queue — weighted round-robin over Dreamer, Artist, Refiner, Hacker, Obsessor, Advancer
            this.ideaQueue = new IdeaQueue(dreamer, artist, refiner, hacker, obsessor, advancer,
                config.orchestration().ideaQueueWeights());

            // Project phase will be detected when orchestration starts
            this.currentPhase = ProjectPhase.BOOTSTRAP;
            this.projectType = PhaseDetector.ProjectType.GENERIC;
        } catch (Exception e) {
            try { llm.close(); } catch (Exception suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
    }

    /**
     * Main orchestration entry point.
     * Runs the multi-phase cycle infinitely.
     */
    public CompletableFuture<Result> orchestrate(Idea idea) {
        if (!state.compareAndSet(OrchestratorState.IDLE, OrchestratorState.RUNNING)) {
            LOG.warn("Orchestration already running — ignoring duplicate start request");
            return CompletableFuture.completedFuture(
                Result.failure("Orchestration already running", null, List.of(), Duration.ZERO));
        }

        // Restore from checkpoint or reset per-run state for a fresh start
        OrchestrationCheckpoint checkpoint = pendingCheckpointRestore;
        pendingCheckpointRestore = null;
        if (checkpoint != null) {
            restoreFromCheckpoint(checkpoint);
            LOG.info("Resuming from checkpoint at cycle {}", cycleCount.get());
        } else {
            cycleCount.set(0);
            cycleHistory.clear();
            consecutiveErrors.set(0);
            latestSynthesisInsights = null;
        }

        Instant startTime = Instant.now();
        List<Result.TaskResult> taskResults = Collections.synchronizedList(new ArrayList<>());

        LOG.info("Starting Director orchestration for idea: {}", idea.description());
        LOG.info("Working directory: {}", idea.workingDirectory());
        
        final boolean restoredFromCheckpoint = checkpoint != null;

        return CompletableFuture.supplyAsync(() -> {
            // Detect project phase and type inside the async task so exceptions
            // are captured in the CompletableFuture rather than thrown synchronously.
            // Skip if restored from checkpoint — phase/type were already set.
            if (!restoredFromCheckpoint) {
                try {
                    this.currentPhase = PhaseDetector.detectPhase(gitOperations.getCommitCount());
                    this.projectType = PhaseDetector.detectProjectType(idea.workingDirectory());
                    LOG.info("Project phase: {}, type: {}", currentPhase.getDisplayName(), projectType.getDisplayName());
                } catch (Exception e) {
                    LOG.warn("Failed to detect project phase/type, using defaults", e);
                    this.currentPhase = ProjectPhase.EARLY;
                    this.projectType = PhaseDetector.ProjectType.GENERIC;
                }
            } else {
                LOG.info("Restored project phase: {}, type: {}", currentPhase.getDisplayName(), projectType.getDisplayName());
            }
            try {
                // Initial setup if needed
                if (config.dryRun()) {
                    LOG.info("Dry run mode - running one cycle only");
                    runSingleCycle(idea, taskResults);
                } else {
                    // Run infinite cycle.
                    // Loop condition includes PAUSING/PAUSED so the body gets a
                    // chance to call checkAndPause() and block rather than exiting.
                    if (restoredFromCheckpoint) {
                        LOG.info("Resuming infinite improvement cycle from cycle {}...", cycleCount.get());
                    } else {
                        LOG.info("Starting infinite improvement cycle...");
                    }
                    while (isActive() || state.get() == OrchestratorState.PAUSING
                                      || state.get() == OrchestratorState.PAUSED) {
                        // Gate: if we're pausing/paused (e.g., from a previous iteration
                        // that ended while PAUSING), block here until resumed or stopped.
                        if (!checkAndPause()) {
                            LOG.info("Orchestration loop exiting: checkAndPause returned false (pre-cycle), state={}",
                                state.get());
                            break;
                        }

                        try {
                            runSingleCycle(idea, taskResults);

                            // Reset error count on successful cycle
                            consecutiveErrors.set(0);

                            // Brief pause between cycles
                            interruptResilientSleep(CYCLE_PAUSE_MS);

                            // Check for pause between cycles
                            if (!checkAndPause()) {
                                LOG.info("Orchestration loop exiting: checkAndPause returned false (post-cycle), state={}",
                                    state.get());
                                break;
                            }

                        } catch (InterruptedException e) {
                            // InterruptedException can come from process cleanup, executor
                            // shutdown, or other background threads — NOT necessarily from
                            // a deliberate stop. Only exit if the state is actually IDLE.
                            Thread.interrupted(); // clear the interrupt flag
                            if (state.get() == OrchestratorState.IDLE) {
                                LOG.info("Orchestration loop exiting: interrupted and state is IDLE (cycle {})",
                                    activeCycle);
                                break;
                            }
                            LOG.warn("Spurious interrupt during cycle {} (state={}) — continuing",
                                activeCycle, state.get());
                        } catch (Exception e) {
                            int errorCount = consecutiveErrors.incrementAndGet();
                            LOG.error("Error in cycle {}, consecutive error count: {}",
                                activeCycle, errorCount, e);

                            // Exponential backoff for repeated errors
                            long delay = Math.min(
                                ERROR_RETRY_DELAY_MS * (1L << Math.min(errorCount - 1, 4)),
                                MAX_BACKOFF_DELAY_MS
                            );
                            LOG.warn("Waiting {}ms before retry due to error", delay);
                            interruptResilientSleep(delay);

                            // Check for pause after error backoff (otherwise the loop
                            // condition sees !isActive() and exits instead of pausing)
                            if (!checkAndPause()) {
                                LOG.info("Orchestration loop exiting: checkAndPause returned false (post-error), state={}",
                                    state.get());
                                break;
                            }
                        }
                    }
                    // Log why the while loop condition evaluated to false
                    if (!isActive() && state.get() != OrchestratorState.PAUSING
                                    && state.get() != OrchestratorState.PAUSED) {
                        LOG.info("Orchestration loop ended: state={}, cycleCount={}, consecutiveErrors={}",
                            state.get(), cycleCount.get(), consecutiveErrors.get());
                    }
                }

                Duration totalDuration = Duration.between(startTime, Instant.now());
                LOG.info("Orchestration completed after {} cycles in {} (state={})",
                    cycleCount.get(), totalDuration, state.get());

                return Result.success(
                    new Plan("Director-led development"),
                    taskResults,
                    cycleCount.get(),
                    cycleHistory.size(),
                    totalDuration
                );

            } catch (Throwable e) {
                // Catch Throwable (not just Exception) to handle OutOfMemoryError,
                // StackOverflowError, etc. Without this, an Error would silently
                // kill the orchestration future and appear as a "random stop."
                LOG.error("Orchestration failed with {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
                Duration totalDuration = Duration.between(startTime, Instant.now());
                String errorMsg = e.getClass().getSimpleName() + ": "
                    + (e.getMessage() != null ? e.getMessage() : "no message");
                return Result.failure(errorMsg, null, taskResults, totalDuration);
            } finally {
                // Ensure we always return to IDLE, regardless of which state
                // we're in (RUNNING, PAUSING, or PAUSED after an exception).
                OrchestratorState prev = state.getAndSet(OrchestratorState.IDLE);
                if (prev == OrchestratorState.PAUSED) {
                    // Wake any thread blocked in checkAndPause so it can exit
                    pauseLock.lock();
                    try { resumeCondition.signalAll(); } finally { pauseLock.unlock(); }
                }
            }
        }, executor);
    }

    /**
     * Run a single multi-phase cycle.
     *
     * <p>Phases:
     * <ol>
     *   <li>Idea Generation (via Overseer or IdeaQueue)</li>
     *   <li>Idea Scoring (reshape low-quality ideas into goal-aligned alternatives)</li>
     *   <li>Skeptic Critique (with mitigations)</li>
     *   <li>Strategic Evaluation (Architect)</li>
     *   <li>Director Decision</li>
     *   <li>Coder Execution</li>
     *   <li>Review & Commit</li>
     *   <li>QA (Build & Test Verification)</li>
     *   <li>Test Optimization (identify and fix slow tests)</li>
     *   <li>Goal Verification (10a: feature inventory, 10b: per-feature checks, 10c: remediation)</li>
     *   <li>Cleanup (temp files, test output, build garbage)</li>
     *   <li>Documentation</li>
     *   <li>Outcome Recording</li>
     * </ol>
     *
     * <p>Every N cycles, run Synthesizer to merge ideas from recent cycles.
     */
    private void runSingleCycle(Idea idea, List<Result.TaskResult> taskResults) throws Exception {
        // Clear per-cycle accumulator to prevent unbounded memory growth across cycles.
        // The Result returned by orchestrate() will contain only the final cycle's results.
        taskResults.clear();

        // Use a local variable — cycleCount is only set after all phases complete.
        // If the program exits mid-cycle, cycleCount still reflects the last completed
        // cycle, so the incomplete cycle will be re-run from Phase 1 on resume.
        int currentCycle = cycleCount.get() + 1;
        this.activeCycle = currentCycle;
        Instant cycleStart = Instant.now();
        boolean skipSkeptic = (currentCycle == 1) && gitOperations.getCommitCount() < 2;

        // Capture generation into a local final so that coder threads running on
        // separate virtual threads always see the generation from *this* cycle,
        // not a stale value from a subsequent cycle after stop/reset.
        final long cycleGeneration = EventBroadcaster.getInstance().getGeneration();
        this.currentGeneration = cycleGeneration;

        // Keep dashboard cycle counter aligned with the active cycle number,
        // even when a cycle fails before completion.
        EventBroadcaster.getInstance().setCycleCount(currentCycle);
        
        // Update project phase periodically
        if (currentCycle % 10 == 0) {
            this.currentPhase = PhaseDetector.detectPhase(gitOperations.getCommitCount());
            LOG.info("Project phase updated to: {}", currentPhase.getDisplayName());
        }
        
        LOG.info("=== Starting Cycle {} (Phase: {}) ===", currentCycle, currentPhase.getDisplayName());

        EventBroadcaster.getInstance().broadcastStatus("running", "Starting cycle " + currentCycle);

        Agent.ExecutionContext baseContext = Agent.ExecutionContext.create(config, llm)
            .withProjectGoal(idea.description())
            .withProjectContext(getProjectContext(idea.workingDirectory()));

        // Phase 0: Run Synthesizer every N cycles (before idea generation)
        if (currentCycle > 1 && currentCycle % config.orchestration().synthesizeInterval() == 0) {
            runSynthesizerPhase(currentCycle, baseContext, taskResults);
        }
        if (!checkAndPause()) return;

        // Phase 1: Idea Generation
        Optional<IdeaResult> ideaOpt = runIdeaGeneration(currentCycle, baseContext, taskResults);
        if (ideaOpt.isEmpty()) return;
        IdeaResult ideaResult = ideaOpt.get();

        // Phase 2: Idea Scoring
        Optional<ScoredIdea> scoredOpt = runScoringPhase(currentCycle, ideaResult, baseContext, taskResults);
        if (scoredOpt.isEmpty()) return;
        ScoredIdea scoredIdea = scoredOpt.get();

        // Phase 3: Skeptic Critique (with mitigations)
        Optional<String> critiqueOpt = runSkepticPhase(currentCycle, skipSkeptic, scoredIdea, baseContext, taskResults);
        if (critiqueOpt.isEmpty()) return;
        String critique = critiqueOpt.get();

        // Phase 4: Strategic Evaluation (Architect)
        Optional<ArchitectInput> architectOpt = runArchitectPhase(currentCycle, scoredIdea, critique, baseContext, taskResults);
        if (architectOpt.isEmpty()) return;
        ArchitectInput architectInput = architectOpt.get();

        // Phase 5: Director Decision
        Optional<String> planOpt = runDirectorPhase(currentCycle, scoredIdea, critique, architectInput, baseContext, taskResults);
        if (planOpt.isEmpty()) return;
        String plan = planOpt.get();

        if (config.dryRun()) {
            LOG.info("Dry run - skipping implementation");
            return;
        }
        if (!checkAndPause()) return;

        // Phase 6: Coder Execution (pause-retry handled internally per batch)
        CoderPhaseResult coderResult = runCoderPhase(currentCycle, plan, baseContext, taskResults);
        if (!checkAndPause()) return;

        // Phase 7+8: Review & Commit
        ReviewResult reviewResult = runReviewAndCommit(currentCycle, plan, coderResult, baseContext, taskResults);
        AgentResponse reviewResponse = reviewResult.response();
        if (!checkAndPause()) return;

        // Phase 9: QA (Build + Test Verification)
        AgentResponse qaResponse = runQAPhase(currentCycle, coderResult, reviewResponse, baseContext, taskResults);
        if (!checkAndPause()) return;

        // Phase 9b: Test Optimization (identify and fix slow tests)
        runTestOptimizerPhase(currentCycle, qaResponse, baseContext, taskResults);
        if (!checkAndPause()) return;

        // Phase 10: Goal Verification (mandatory every cycle — runs after QA)
        // Phase 10c: If critical gaps found, spawn coders to fix them immediately
        CoderPhaseResult verifierCoderResult = runVerifierPhase(
            currentCycle, coderResult, qaResponse, baseContext, taskResults);
        // If remediation ran, update coderResult so the cycle outcome reflects all work
        if (verifierCoderResult != coderResult) {
            coderResult = mergeCoderResults(coderResult, verifierCoderResult);
        }
        if (!checkAndPause()) return;

        // Phase 10d: Organizer — refactor oversized source files (every other cycle)
        if (currentCycle % 2 == 0) {
            runOrganizerPhase(currentCycle, idea.workingDirectory(), baseContext, taskResults);
            if (!checkAndPause()) return;
        }

        // Phase 11: Cleanup (remove temp files, test output, build garbage)
        runCleanerPhase(currentCycle, baseContext, taskResults);
        if (!checkAndPause()) return;

        // Phase 12: Documentation
        runDocumentation(currentCycle, coderResult, reviewResponse, baseContext, taskResults);
        if (!checkAndPause()) return;

        // Phase 13: Record Cycle Outcome (for learning)
        Duration cycleDuration = Duration.between(cycleStart, Instant.now());
        recordCycleOutcome(currentCycle, ideaResult, scoredIdea, coderResult, reviewResponse,
            reviewResult.commitSuccessful(), cycleDuration);

        // Legacy cycle result tracking
        while (cycleHistory.size() >= MAX_CYCLE_HISTORY) {
            cycleHistory.removeFirst();
        }
        cycleHistory.addLast(new CycleResult(
            currentCycle, ideaResult.agentName(), ideaResult.ideas(),
            critique, plan, coderResult.totalTasks(), coderResult.responses().size(), cycleDuration
        ));

        LOG.info("=== Cycle {} completed in {} ===", currentCycle, cycleDuration);

        // All phases succeeded — promote the cycle counter so that captureCheckpoint()
        // reflects a fully completed cycle. If the program exited before reaching this
        // point, cycleCount would still be N-1 and the incomplete cycle would be re-run.
        cycleCount.set(currentCycle);

        // Auto-save checkpoint after each completed cycle
        java.util.function.Consumer<OrchestrationCheckpoint> saver = this.checkpointSaver;
        if (saver != null) {
            try {
                saver.accept(captureCheckpoint(idea));
            } catch (Exception e) {
                LOG.warn("Failed to save checkpoint after cycle {}: {}", currentCycle, e.getMessage());
            }
        }
    }

    // ── Phase 1: Idea Generation ────────────────────────────────────────────────

    /**
     * Phase 1: Generate ideas via Overseer (if pending) or IdeaQueue round-robin.
     *
     * @return idea result, or empty if the phase failed and the cycle should abort
     */
    private Optional<IdeaResult> runIdeaGeneration(
            int cycle, Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        // Single atomic getAndSet(null) avoids TOCTOU race between check and consume
        String suggestion = overseer.consumeSuggestion();
        if (suggestion != null && !suggestion.isBlank()) {
            return runOverseerPath(cycle, ctx, results, suggestion);
        }
        return runIdeaQueuePath(cycle, ctx, results);
    }

    private Optional<IdeaResult> runOverseerPath(
            int cycle, Agent.ExecutionContext ctx, List<Result.TaskResult> results, String suggestion) {
        // An Overseer suggestion implies new direction — re-enable the Dreamer
        // so it can assess whether the new suggestion changes the goal landscape.
        if (dreamerSelfDisabled) {
            LOG.info("Overseer suggestion received — re-enabling Dreamer for goal re-assessment");
            dreamerSelfDisabled = false;
        }

        LOG.info("Phase 1: Overseer is formalizing user suggestion...");
        broadcastStarted("Overseer", "Oversee", "Formalizing user suggestion...");

        for (String idle : new String[]{"Dreamer", "Artist", "Refiner", "Hacker", "Obsessor", "Advancer"}) {
            broadcastCompleted(idle, "Idle", "Skipped \u2014 Overseer active this cycle");
        }
        broadcastCompleted("Maestro", "Idle", "Idle");

        Task task = new Task(
            "Formalize user suggestion for cycle " + cycle,
            Task.TaskType.OVERSEE, Task.TaskPriority.CRITICAL
        );
        AgentResponse response = executeWithRetry("Overseer", "Oversee", overseer, task,
            ctx.withPreviousResults("## USER SUGGESTION\n" + suggestion));

        if (!response.success()) {
            broadcastFailed("Overseer", "Oversee", response.error());
            return Optional.empty();
        }

        String ideas = response.content();
        LOG.debug("Overseer ideas: {}", truncate(ideas, 200));
        broadcastSuccess("Overseer", "Oversee", "Suggestion formalized", ideas, response.durationMs());
        results.add(createTaskResult("oversee-" + cycle, "Overseer (user suggestion)", true, ideas, null));
        return Optional.of(new IdeaResult(ideas, "Overseer"));
    }

    private Optional<IdeaResult> runIdeaQueuePath(
            int cycle, Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        broadcastCompleted("Overseer", "Oversee", "Idle");

        // Atomic peek-and-consume: avoids TOCTOU race if weights change between
        // the peek (isArtistNext) and consume calls on separate synchronized entries.
        boolean hackerEnabled = config.orchestration().hackerEnabled();
        boolean dreamerEnabled = !dreamerSelfDisabled;
        IdeaQueue.ConsumeResult consumeResult = ideaQueue.peekAndConsume(
            hackerEnabled,
            dreamerEnabled,
            () -> evaluateArtistEligibility(cycle, ctx)  // only called if Artist is next
        );

        if (consumeResult.isDreamerNext() && !dreamerEnabled) {
            LOG.info("Phase 1: Dreamer self-disabled (goals met) \u2014 skipping");
            broadcastCompleted("Dreamer", "Dream",
                "Self-disabled \u2014 all goals met, no new capabilities needed");
        }
        if (consumeResult.isArtistNext() && !consumeResult.agent().getName().equals("Artist")) {
            // Maestro ran and disabled Artist
            broadcastCompleted("Artist", "Paint", "Skipped \u2014 Maestro found no frontend");
        }
        if (!consumeResult.isArtistNext()) {
            broadcastCompleted("Maestro", "Idle", "Idle");
        }
        if (consumeResult.isHackerNext() && !hackerEnabled) {
            LOG.info("Phase 1: Hacker agent is disabled \u2014 skipping");
            broadcastCompleted("Hacker", "Hack", "Disabled via config");
        }

        Agent ideaAgent = consumeResult.agent();
        String agentName = ideaAgent.getName();
        LOG.info("Phase 1: {} is generating ideas...", agentName);

        // Signal all other idea agents as idle
        for (String other : new String[]{"Dreamer", "Artist", "Refiner", "Hacker", "Obsessor", "Advancer"}) {
            if (!other.equals(agentName)) {
                broadcastCompleted(other, "Idle", "Idle this cycle");
            }
        }

        broadcastStarted(agentName, "Ideate", "Generating ideas...");

        Task.TaskType taskType = switch (agentName) {
            case "Artist"   -> Task.TaskType.PAINT;
            case "Refiner"  -> Task.TaskType.REFINE;
            case "Hacker"   -> Task.TaskType.HACK;
            case "Obsessor" -> Task.TaskType.OBSESS;
            case "Advancer" -> Task.TaskType.ADVANCE;
            default         -> Task.TaskType.DREAM;
        };

        Task task = new Task(
            "Generate improvement ideas for cycle " + cycle, taskType, Task.TaskPriority.HIGH
        );

        String ideaContext = getRecentHistory();
        if (latestSynthesisInsights != null && !latestSynthesisInsights.isBlank()) {
            ideaContext = ideaContext + "\n\nLATEST SYNTHESIZER INSIGHTS:\n" + latestSynthesisInsights;
        }

        AgentResponse response = executeWithRetry(agentName, "Ideate", ideaAgent, task,
            ctx.withPreviousResults(ideaContext));

        if (!response.success()) {
            broadcastFailed(agentName, "Ideate", response.error());
            return Optional.empty();
        }

        String ideas = response.content();
        LOG.debug("{} ideas: {}", agentName, truncate(ideas, 200));

        // Check if the Dreamer declared all goals met
        if ("Dreamer".equals(agentName) && DreamerAgent.isGoalsMet(ideas)) {
            LOG.info("Dreamer declared GOALS_MET — disabling Dreamer for future cycles");
            dreamerSelfDisabled = true;
            broadcastSuccess("Dreamer", "Ideate",
                "Goals met \u2014 Dreamer self-disabled; other agents continue",
                ideas, response.durationMs());
            results.add(createTaskResult("idea-" + cycle, "Dreamer (goals met)", true, ideas, null));
            // Return empty so this cycle does not proceed with no actionable ideas.
            // The other agents will take over on the next cycle.
            return Optional.empty();
        }

        broadcastSuccess(agentName, "Ideate", "Generated ideas", ideas, response.durationMs());
        results.add(createTaskResult("idea-" + cycle, agentName + " ideas", true, ideas, null));
        return Optional.of(new IdeaResult(ideas, agentName));
    }

    private boolean evaluateArtistEligibility(int cycle, Agent.ExecutionContext ctx) {
        LOG.info("Phase 1: Maestro is evaluating Artist eligibility...");
        broadcastStarted("Maestro", "Curate", "Evaluating Artist eligibility...");

        Task task = new Task(
            "Evaluate Artist eligibility for cycle " + cycle,
            Task.TaskType.CURATE, Task.TaskPriority.HIGH
        );
        AgentResponse response = executeWithRetry("Maestro", "Curate", maestro, task, ctx);

        if (response.success()) {
            boolean enabled = MaestroAgent.parseVerdict(response.content());
            String verdict = enabled ? "Artist ENABLED" : "Artist DISABLED";
            LOG.info("Maestro verdict: {}", verdict);
            broadcastSuccess("Maestro", "Curate", verdict, response.content(), response.durationMs());
            return enabled;
        }

        // Only reachable if orchestration was stopped during retry
        broadcastFailed("Maestro", "Curate", "Failed \u2014 Artist disabled by default");
        return false;
    }

    // ── Phase 3: Skeptic Critique ───────────────────────────────────────────────

    /**
     * Phase 3: Skeptic critiques ideas (skipped when no prior work exists).
     *
     * @return critique text, or empty if the phase failed and the cycle should abort
     */
    private Optional<String> runSkepticPhase(
            int cycle, boolean skip, ScoredIdea idea,
            Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        if (skip) {
            LOG.info("Phase 3: Skipping Skeptic (first cycle, repo has < 2 commits)");
            broadcastCompleted("Skeptic", "Critique", "Skipped (no prior work)");
            return Optional.of("First cycle with no prior work - no critique available. Proceed with ideas as the initial plan.");
        }

        LOG.info("Phase 3: Skeptic is critiquing ideas...");
        broadcastStarted("Skeptic", "Critique", "Analyzing ideas and suggesting mitigations...");

        Task task = new Task("Critique ideas for cycle " + cycle, Task.TaskType.CRITIQUE, Task.TaskPriority.HIGH);
        AgentResponse response = executeWithRetry("Skeptic", "Critique", skeptic, task,
            ctx.withPreviousResults(idea.original().agentName().toUpperCase() + "'S IDEAS:\n" + idea.original().ideas()));

        if (!response.success()) {
            broadcastFailed("Skeptic", "Critique", response.error());
            return Optional.empty();
        }

        String critique = response.content();
        LOG.debug("Skeptic critique: {}", truncate(critique, 200));
        broadcastSuccess("Skeptic", "Critique", "Analysis complete with mitigations", critique, response.durationMs());
        results.add(createTaskResult("critique-" + cycle, "Skeptic critique", true, critique, null));
        return Optional.of(critique);
    }

    // ── Phase 5: Director Decision ──────────────────────────────────────────────

    /**
     * Phase 5: Director synthesizes ideas, critique, and strategic input into an implementation plan.
     *
     * @return director plan text, or empty if the phase failed and the cycle should abort
     */
    private Optional<String> runDirectorPhase(
            int cycle, ScoredIdea idea, String critique, ArchitectInput architect,
            Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        LOG.info("Phase 5: Director is making decisions...");
        broadcastStarted("Director", "Decide", "Making decision...");

        Task task = new Task(
            "Decide implementation plan for cycle " + cycle,
            Task.TaskType.DECIDE, Task.TaskPriority.CRITICAL
        );

        // When the Scorer reshaped ideas, the score reflects the ORIGINAL ideas
        // (which were below threshold), not the reshaped ones. Label accordingly
        // so the Director doesn't misjudge the reshaped ideas as low quality.
        String scoreLabel = idea.score().wasReshaped()
            ? "(original score: %d/10, reshaped for better alignment)".formatted(idea.score().overallScore())
            : "(Score: %d/10)".formatted(idea.score().overallScore());

        String agentInputs = """
            %s'S IDEAS %s:
            %s

            SKEPTIC'S CRITIQUE (with mitigations):
            %s

            ARCHITECT'S STRATEGIC INPUT:
            Alignment: %d/10
            Priority: %s
            Dependencies: %s
            Concerns: %s
            Recommendation: %s

            PROJECT PHASE: %s
            """.formatted(
                idea.original().agentName().toUpperCase(),
                scoreLabel,
                idea.original().ideas(),
                critique,
                architect.alignmentScore(),
                architect.priority(),
                architect.dependencies(),
                architect.concerns(),
                architect.recommendation(),
                currentPhase.getDisplayName()
            );

        AgentResponse response = executeWithRetry("Director", "Decide", director, task,
            ctx.withPreviousResults(agentInputs));

        if (!response.success()) {
            broadcastFailed("Director", "Decide", response.error());
            return Optional.empty();
        }

        String plan = response.content();
        LOG.info("Director's plan: {}", truncate(plan, 300));
        broadcastSuccess("Director", "Decide", "Plan created", plan, response.durationMs());
        results.add(createTaskResult("decide-" + cycle, "Director decision", true, plan, null));
        return Optional.of(plan);
    }

    // ── Phase 6: Coder Execution ────────────────────────────────────────────────

    /**
     * Phase 6: Execute the Director's implementation tasks.
     * <p>
     * All tasks parsed from the plan are executed. When the number of tasks
     * exceeds {@code maxConcurrentCoders}, they are processed in sequential
     * batches — each batch launches up to {@code maxConcurrentCoders} in
     * parallel, waits for the batch to complete, then starts the next batch.
     * This ensures ALL tasks are completed even when workers are limited.
     */
    /**
     * Build a task-specific assignment for an individual coder.
     * The coder's own task is presented as the primary directive, while the full
     * Director plan is provided as background context so the coder understands
     * how its work fits into the cycle without being confused about scope.
     */
    private String buildCoderAssignment(Task task, int coderIndex, int totalTasks, String fullPlan) {
        StringBuilder sb = new StringBuilder();

        // Background plan first (middle of the overall prompt = lowest attention zone).
        // This gives the LLM framing context before it encounters the task.
        sb.append("## BACKGROUND: Director's Full Plan (for context only)\n");
        sb.append("The following is the Director's complete plan for this cycle.\n");
        sb.append("Use it to understand how your task fits into the bigger picture,\n");
        sb.append("but implement ONLY your assigned task below.\n\n");
        sb.append(summarizeIfNeeded(fullPlan, 4000, "Director's plan for coder context"));
        sb.append("\n\n---\n\n");

        // Specific task assignment last (recency = highest attention).
        // This is what the coder should actually do.
        sb.append("## YOUR ASSIGNMENT (Task ").append(coderIndex).append(" of ").append(totalTasks).append(")\n");
        sb.append("You are responsible for implementing ONLY this task:\n\n");
        sb.append("**").append(task.description()).append("**\n\n");
        sb.append("Priority: ").append(task.priority()).append("\n\n");
        sb.append("Do NOT implement other tasks from the plan — other coders are handling those.\n");
        sb.append("Focus exclusively on the task above.");

        return sb.toString();
    }

    /**
     * Build a task-specific assignment for a remediation coder fixing a Verifier gap.
     * Full verifier output goes first as background, specific gap assignment last.
     */
    private String buildRemediationAssignment(Task gap, int fixIndex, int totalGaps, String fullVerifierOutput) {
        StringBuilder sb = new StringBuilder();

        // Background: full verifier output (context for understanding the overall state)
        sb.append("## BACKGROUND: Verifier's Full Report (for context only)\n");
        sb.append("The Verifier found ").append(totalGaps).append(" critical gap(s) total.\n");
        sb.append("Other coders are fixing the other gaps. Use this report only to\n");
        sb.append("understand the broader context — fix ONLY your assigned gap below.\n\n");
        sb.append(summarizeIfNeeded(fullVerifierOutput, 4000, "verifier gap report"));
        sb.append("\n\n---\n\n");

        // Specific gap assignment last (recency = highest attention)
        sb.append("## YOUR ASSIGNMENT (Fix ").append(fixIndex).append(" of ").append(totalGaps).append(")\n");
        sb.append("You MUST fix this critical gap:\n\n");
        sb.append("**").append(gap.description()).append("**\n\n");
        sb.append("Priority: CRITICAL\n\n");
        sb.append("Do NOT fix other gaps — other coders are handling those.\n");
        sb.append("Focus exclusively on the gap above.");

        return sb.toString();
    }

    private CoderPhaseResult runCoderPhase(
            int cycle, String plan, Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        List<Task> tasks = CyclePlanParser.parseImplementationTasks(plan, cycle);
        int totalTasks = tasks.size();
        int maxCoders = effectiveConcurrency();
        long blockingCount = tasks.stream().filter(Task::blocking).count();

        LOG.info("Phase 6: {} task(s), {} blocking, {} concurrent coders{}",
            totalTasks, blockingCount, maxCoders,
            config.orchestration().parallelExecution() ? "" : " (serial mode)");

        if (!isNotStopped()) {
            LOG.info("Orchestration stopped, skipping coder execution");
            return new CoderPhaseResult(List.of(), "", 0, totalTasks);
        }

        broadcastStarted("Coders", "Implement",
            totalTasks + " task(s)" + (blockingCount > 0 ? " (" + blockingCount + " blocking)" : ""));

        List<AgentResponse> allResponses = new ArrayList<>();
        StringBuilder allCoderWork = new StringBuilder();
        int totalSuccess = 0;
        int globalCoderIndex = 0;

        // Partition tasks into execution groups split on blocking boundaries.
        // Consecutive non-blocking tasks form a parallel group; each blocking
        // task forms its own single-task group that runs alone.
        List<List<Task>> groups = partitionByBlocking(tasks);
        int groupNum = 0;

        for (List<Task> group : groups) {
            if (!isNotStopped()) break;
            groupNum++;
            boolean isBlockingGroup = group.size() == 1 && group.get(0).blocking();

            if (isBlockingGroup) {
                LOG.info("Phase 6: Group {}/{} — BLOCKING task (runs alone)",
                    groupNum, groups.size());
                EventBroadcaster.getInstance().broadcastEvent(AgentEvent.builder()
                    .agentName("Coders").type(AgentEvent.EventType.IN_PROGRESS)
                    .phase("Implement")
                    .message("Blocking task (group " + groupNum + "/" + groups.size() + ")")
                    .build(), currentGeneration);
            } else if (groups.size() > 1) {
                LOG.info("Phase 6: Group {}/{} — {} parallel task(s)",
                    groupNum, groups.size(), group.size());
            }

            // Execute group in batches of maxCoders
            int groupSize = group.size();
            int batchCount = (groupSize + maxCoders - 1) / maxCoders;

            for (int batch = 0; batch < batchCount && isNotStopped(); batch++) {
                int batchStart = batch * maxCoders;
                int batchEnd = Math.min(batchStart + maxCoders, groupSize);
                List<Task> batchTasks = group.subList(batchStart, batchEnd);

                List<CompletableFuture<CoderResult>> futures = new ArrayList<>();
                for (Task implTask : batchTasks) {
                    if (!isNotStopped()) break;

                    globalCoderIndex++;
                    String coderName = "Coder-" + globalCoderIndex;
                    LOG.info("{} starting{}: {}", coderName,
                        implTask.blocking() ? " (BLOCKING)" : "",
                        truncate(implTask.description(), 80));
                    broadcastStarted(coderName, "Implement", truncate(implTask.description(), 200));

                    String taskAssignment = buildCoderAssignment(implTask, globalCoderIndex, totalTasks, plan);
                    Agent.ExecutionContext coderCtx = ctx.withPreviousResults(taskAssignment);
                    futures.add(executeCoderWithRetry(coderName, globalCoderIndex, implTask, coderCtx));
                }

                List<AgentResponse> batchResponses = collectCoderResults(cycle, futures, results, currentGeneration);
                accumulateBatchResults(batchResponses, allResponses, allCoderWork);
                totalSuccess += (int) batchResponses.stream().filter(AgentResponse::success).count();

                if (!checkAndPause()) break;
            }
        }

        EventBroadcaster.getInstance().broadcastEvent(AgentEvent.builder()
            .agentName("Coders").type(AgentEvent.EventType.COMPLETED)
            .phase("Implement").message("Completed " + totalSuccess + "/" + totalTasks + " tasks")
            .details("All coder tasks finished").build(), currentGeneration);

        return new CoderPhaseResult(allResponses, allCoderWork.toString(), totalSuccess, totalTasks);
    }

    /**
     * Partition tasks into execution groups split on blocking boundaries.
     * Consecutive non-blocking tasks are grouped together (run in parallel batches).
     * Each blocking task becomes its own single-element group (runs alone).
     *
     * <p>Example: [T1, T2, B3, T4, T5] → [[T1,T2], [B3], [T4,T5]]
     */
    private static List<List<Task>> partitionByBlocking(List<Task> tasks) {
        List<List<Task>> groups = new ArrayList<>();
        List<Task> currentGroup = new ArrayList<>();

        for (Task task : tasks) {
            if (task.blocking()) {
                // Flush any accumulated non-blocking tasks as a group
                if (!currentGroup.isEmpty()) {
                    groups.add(currentGroup);
                    currentGroup = new ArrayList<>();
                }
                // Blocking task is its own group
                groups.add(List.of(task));
            } else {
                currentGroup.add(task);
            }
        }
        // Flush remaining non-blocking tasks
        if (!currentGroup.isEmpty()) {
            groups.add(currentGroup);
        }
        return groups;
    }

    /**
     * Returns the effective concurrency for coder/verifier batches.
     * When {@code parallelExecution} is disabled, returns 1 (serial execution).
     * Otherwise returns {@code maxConcurrentCoders}.
     */
    private int effectiveConcurrency() {
        if (!config.orchestration().parallelExecution()) {
            return 1;
        }
        return Math.max(1, config.orchestration().maxConcurrentCoders());
    }

    private static final long AGENT_RETRY_DELAY_MS = 5_000;
    // After AGENT_RETRY_LINEAR_ATTEMPTS constant-delay retries, switch to
    // exponential backoff: AGENT_RETRY_DELAY_MS * 2^(attempt - LINEAR_ATTEMPTS),
    // capped at MAX_AGENT_RETRY_DELAY_MS (30 minutes).
    private static final int AGENT_RETRY_LINEAR_ATTEMPTS = 3;
    private static final long MAX_AGENT_RETRY_DELAY_MS = 30 * 60 * 1_000L; // 30 minutes

    /**
     * Executes any agent with indefinite retry on failure.
     * Uses the current volatile {@code currentGeneration} for event broadcasts.
     * For coder threads, prefer the overload that accepts an explicit generation.
     */
    private AgentResponse executeWithRetry(
            String agentName, String phase, Agent agent, Task task, Agent.ExecutionContext ctx) {
        return executeWithRetry(agentName, phase, agent, task, ctx, currentGeneration);
    }

    /**
     * Executes any agent with indefinite retry on failure.
     * If the LLM call times out, stalls, or errors, the agent retries after a
     * short delay until it succeeds or orchestration is stopped.
     *
     * @param agentName  display name for logging and events (e.g. "Skeptic")
     * @param phase      event phase label (e.g. "Critique")
     * @param agent      the agent to execute
     * @param task       the task to pass to the agent
     * @param ctx        execution context
     * @param generation the generation captured at cycle start, used for event broadcasts
     * @return a successful AgentResponse (never a failure — retries until success or stop)
     */
    private AgentResponse executeWithRetry(
            String agentName, String phase, Agent agent, Task task,
            Agent.ExecutionContext ctx, long generation) {
        int attempt = 0;
        int currentCycle = this.activeCycle;
        String lastErrorMsg = "";
        // Create streaming callback that broadcasts output chunks in real-time
        java.util.function.Consumer<String> onChunk = chunk ->
            EventBroadcaster.getInstance().broadcastStreamChunk(agentName, chunk, generation);
        while (isActive() || state.get() == OrchestratorState.PAUSING || state.get() == OrchestratorState.PAUSED) {
            // Gate: if pausing/paused, block here before launching a new LLM call.
            // This handles the case where retryDelay() or the loop condition brings
            // us back while in PAUSING state — we pause before starting a new attempt.
            if (state.get() == OrchestratorState.PAUSING || state.get() == OrchestratorState.PAUSED) {
                LOG.info("{} pausing before next attempt — waiting for resume", agentName);
                if (!checkAndPause()) {
                    return AgentResponse.failure(agentName + " stopped during pause");
                }
                ctx = ctx.withConfigAndLlm(config, llm);
                onChunk = chunk ->
                    EventBroadcaster.getInstance().broadcastStreamChunk(agentName, chunk, generation);
                LOG.info("{} resuming", agentName);
                broadcastStarted(agentName, phase, "Resuming...");
            }

            attempt++;

            // Always use the current config/LLM from the volatile fields.
            // The ctx passed in may hold a stale LLM reference if applyConfig()
            // swapped the backend during a pause/resume cycle.
            Agent.ExecutionContext currentCtx = ctx.withConfigAndLlm(config, llm);

            // Inject retry seed into context so the LLM gets a different prompt each attempt
            Agent.ExecutionContext retryCtx = attempt > 1
                ? currentCtx.withRetryAttempt(attempt - 1)
                : currentCtx;

            try {
                AgentResponse response = agent.execute(task, retryCtx, onChunk).join();

                if (response.success()) {
                    if (attempt > 1) {
                        LOG.info("{} succeeded on attempt {}", agentName, attempt);
                    }
                    return response;
                }

                // Failed — log and retry
                lastErrorMsg = response.error() != null ? response.error() : "unknown error";
                long retryDelayMs = computeRetryDelay(attempt);
                LOG.warn("{} failed on attempt {}: {} — retrying in {}s",
                    agentName, attempt, lastErrorMsg, retryDelayMs / 1000);

                // Store retry attempt for later viewing
                EventBroadcaster.getInstance().storeRetryAttempt(
                    agentName, currentCycle, phase, attempt, lastErrorMsg,
                    response.content(), generation);

                // Broadcast retry event
                EventBroadcaster.getInstance().broadcastEvent(AgentEvent.builder()
                    .agentName(agentName).type(AgentEvent.EventType.RETRY)
                    .phase(phase)
                    .message("Retry #" + attempt + " (backoff " + retryDelayMs / 1000 + "s): " + truncate(lastErrorMsg, 80))
                    .details(lastErrorMsg)
                    .build(), generation);
            } catch (Exception e) {
                Throwable cause = (e instanceof java.util.concurrent.CompletionException && e.getCause() != null)
                    ? e.getCause() : e;
                lastErrorMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
                long retryDelayMs = computeRetryDelay(attempt);
                LOG.warn("{} threw exception on attempt {}: {} — retrying in {}s",
                    agentName, attempt, lastErrorMsg, retryDelayMs / 1000);

                // Store retry attempt for later viewing
                EventBroadcaster.getInstance().storeRetryAttempt(
                    agentName, currentCycle, phase, attempt, lastErrorMsg, null, generation);

                // Broadcast retry event
                EventBroadcaster.getInstance().broadcastEvent(AgentEvent.builder()
                    .agentName(agentName).type(AgentEvent.EventType.RETRY)
                    .phase(phase)
                    .message("Retry #" + attempt + " (backoff " + retryDelayMs / 1000 + "s): " + truncate(lastErrorMsg, 80))
                    .details(lastErrorMsg)
                    .build(), generation);
            }

            // If pausing, block until resumed then retry the failed attempt.
            // Note: we only reach here after a FAILED attempt (success returns at line 1066).
            // The previous attempt failed on its own, so a retry is the correct action.
            if (state.get() == OrchestratorState.PAUSING || state.get() == OrchestratorState.PAUSED) {
                LOG.info("{} failed during pause — waiting for resume before retrying", agentName);
                if (!checkAndPause()) {
                    return AgentResponse.failure(agentName + " stopped during pause");
                }
                // Refresh context with current config/LLM in case they changed while paused
                ctx = ctx.withConfigAndLlm(config, llm);
                onChunk = chunk ->
                    EventBroadcaster.getInstance().broadcastStreamChunk(agentName, chunk, generation);
                LOG.info("{} resuming — retrying after previous failure", agentName);
                broadcastStarted(agentName, phase, "Resuming...");
                continue; // retry the failed attempt
            }

            if (!retryDelay(computeRetryDelay(attempt))) {
                // retryDelay returns false when isActive() is false — could be stop OR pause.
                // Check if it's a pause; if so, handle it and continue retrying.
                OrchestratorState delayState = state.get();
                if (delayState == OrchestratorState.PAUSING || delayState == OrchestratorState.PAUSED) {
                    continue; // loop back to the pause-handling check at the top
                }
                return AgentResponse.failure(agentName + " stopped during retry");
            }
        }

        LOG.info("{} stopped by user after {} attempts", agentName, attempt);
        return AgentResponse.failure(agentName + " stopped during retry");
    }

    /**
     * Executes a coder task with indefinite retry on failure.
     * Wraps {@link #executeWithRetry} and runs asynchronously for parallel coder execution.
     */
    private CompletableFuture<CoderResult> executeCoderWithRetry(
            String coderName, int coderIndex, Task task, Agent.ExecutionContext ctx) {
        // Capture generation before launching async — coder threads may outlive the current cycle
        final long gen = this.currentGeneration;
        return CompletableFuture.supplyAsync(() -> {
            AgentResponse response = executeWithRetry(coderName, "Implement", coder, task, ctx, gen);
            return new CoderResult(coderName, coderIndex, task, response);
        }, executor);
    }

    /**
     * Computes the retry delay for a given attempt number.
     * <ul>
     *   <li>Attempts 1..{@link #AGENT_RETRY_LINEAR_ATTEMPTS}: constant {@link #AGENT_RETRY_DELAY_MS}</li>
     *   <li>Beyond that: exponential backoff capped at {@link #MAX_AGENT_RETRY_DELAY_MS}</li>
     * </ul>
     */
    private static long computeRetryDelay(int attempt) {
        if (attempt <= AGENT_RETRY_LINEAR_ATTEMPTS) {
            return AGENT_RETRY_DELAY_MS;
        }
        int exponent = attempt - AGENT_RETRY_LINEAR_ATTEMPTS;
        // Cap the shift to avoid overflow (2^30 ≈ 1 billion — safely within long range)
        long delay = AGENT_RETRY_DELAY_MS * (1L << Math.min(exponent, 30));
        return Math.min(delay, MAX_AGENT_RETRY_DELAY_MS);
    }

    private boolean retryDelay(long delayMs) {
        long deadline = System.currentTimeMillis() + delayMs;
        while (System.currentTimeMillis() < deadline && isActive()) {
            try {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                Thread.sleep(Math.min(1000, remaining));
            } catch (InterruptedException ie) {
                Thread.interrupted(); // clear interrupt flag
                // Only honour the interrupt if state changed to IDLE (explicit stop).
                // Spurious interrupts from process cleanup must not abort the retry.
                if (state.get() == OrchestratorState.IDLE) {
                    return false;
                }
                LOG.debug("Spurious interrupt in retryDelay (state={}) — continuing", state.get());
            }
        }
        return isActive();
    }

    /**
     * Sleeps for the requested duration, absorbing spurious interrupts.
     * <p>
     * The main orchestration thread can receive interrupts from background
     * activity (CLI process cleanup, monitor threads, etc.) that are NOT
     * intended to stop orchestration. This method clears the interrupt flag
     * and retries the sleep unless the orchestrator state has changed to IDLE
     * (meaning an explicit stop was requested).
     */
    private void interruptResilientSleep(long ms) {
        long remaining = ms;
        long deadline = System.currentTimeMillis() + ms;
        // Only continue sleeping while RUNNING. Break immediately on PAUSING
        // (so checkAndPause can transition to PAUSED promptly) or IDLE (stop).
        while (remaining > 0 && state.get() == OrchestratorState.RUNNING) {
            try {
                Thread.sleep(Math.min(remaining, 1000));
            } catch (InterruptedException e) {
                Thread.interrupted(); // clear the flag
                if (state.get() != OrchestratorState.RUNNING) {
                    return; // stop or pause requested
                }
                LOG.debug("Spurious interrupt during sleep (state={}) — continuing", state.get());
            }
            remaining = deadline - System.currentTimeMillis();
        }
    }

    /**
     * Returns true when the orchestrator is actively running (not pausing/paused/stopped).
     * Use this for tight loops that need to detect pause quickly (e.g., retryDelay).
     */
    private boolean isActive() {
        return state.get() == OrchestratorState.RUNNING;
    }

    /**
     * Returns true unless the orchestrator has been explicitly stopped (IDLE).
     * <p>
     * Unlike {@link #isActive()}, this returns true during PAUSING and PAUSED states.
     * Use this for work-continuation guards (e.g., coder loops, verifier batches)
     * where in-progress work should complete before the pause takes effect.
     */
    private boolean isNotStopped() {
        return state.get() != OrchestratorState.IDLE;
    }

    /**
     * Check-and-pause gate. Called between phases and between batches.
     * <p>
     * If the state is PAUSING, transitions to PAUSED, broadcasts status, and
     * blocks until resumed or stopped. If the state is already PAUSED (another
     * thread transitioned), blocks on the same condition.
     * <p>
     * Handles three race conditions:
     * <ul>
     *   <li>{@code resume()} called during PAUSING before CAS — detected, returns true</li>
     *   <li>{@code stop()} called during PAUSING before CAS — detected, returns false</li>
     *   <li>Spurious {@code InterruptedException} while awaiting — re-enters wait loop</li>
     * </ul>
     *
     * @return true if execution should continue (RUNNING), false if stopped (IDLE)
     */
    private boolean checkAndPause() {
        // Outer loop handles re-evaluation after state changes or interrupts.
        while (true) {
            OrchestratorState s = state.get();
            if (s == OrchestratorState.RUNNING) return true;
            if (s == OrchestratorState.IDLE)    return false;

            // State is PAUSING or PAUSED.
            if (s == OrchestratorState.PAUSING) {
                if (!state.compareAndSet(OrchestratorState.PAUSING, OrchestratorState.PAUSED)) {
                    // CAS failed — state changed concurrently.
                    // Could be RUNNING (resume cancelled the pause), IDLE (stop),
                    // or PAUSED (another thread won the CAS). Loop back to re-evaluate.
                    continue;
                }
                // We successfully transitioned PAUSING → PAUSED.
                LOG.info("Orchestration paused — waiting for resume or stop");
                EventBroadcaster.getInstance().broadcastStatus("paused", "Orchestration paused");
            }

            // State is PAUSED — block until resume() or stop() changes it.
            pauseLock.lock();
            try {
                while (state.get() == OrchestratorState.PAUSED) {
                    try {
                        resumeCondition.await();
                    } catch (InterruptedException e) {
                        Thread.interrupted(); // clear flag
                        // On interrupt, re-check state. If IDLE, exit.
                        // Otherwise, loop back to await (spurious interrupt).
                        if (state.get() == OrchestratorState.IDLE) {
                            return false;
                        }
                        LOG.debug("Spurious interrupt in checkAndPause (state={}) — re-waiting", state.get());
                    }
                }
            } finally {
                pauseLock.unlock();
            }
            // State changed from PAUSED to something — loop back to re-evaluate
            // (could be RUNNING from resume, or IDLE from stop).
        }
    }

    private List<AgentResponse> collectCoderResults(
            int cycle, List<CompletableFuture<CoderResult>> futures, List<Result.TaskResult> results,
            long generation) {
        List<AgentResponse> responses = new ArrayList<>();
        if (futures.isEmpty()) return responses;

        try {
            // Wait indefinitely for all coders to finish. Each coder's
            // executeWithRetry retries until success or isActive() == false,
            // so futures are guaranteed to complete when the user stops orchestration.
            // A finite timeout here would cause the orchestrator to skip still-retrying
            // coders and proceed to the Reviewer prematurely.
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
            try {
                allFutures.join();
            } catch (java.util.concurrent.CompletionException ce) {
                LOG.warn("Coder phase completed with errors — collecting results");
            }
            for (CompletableFuture<CoderResult> f : futures) {
                if (!f.isDone()) continue; // Skip coders that haven't finished
                try {
                    CoderResult cr = f.join();
                    responses.add(cr.response());
                    if (cr.response().success()) {
                        LOG.info("{} completed successfully", cr.coderName());
                        EventBroadcaster.getInstance().storeCoderOutput(
                            cr.coderName(),
                            cycle,
                            cr.task().description(),
                            cr.response().content(),
                            true,
                            generation
                        );
                        results.add(createTaskResult(
                            "coder-" + cycle + "-" + truncate(cr.task().id(), 8),
                            cr.task().description(), true, cr.response().content(), null));
                        broadcastSuccess(cr.coderName(), "Implement",
                            "Completed: " + truncate(cr.task().description(), 200),
                            cr.response().content(), cr.response().durationMs());
                    } else {
                        LOG.warn("{} failed: {}", cr.coderName(), cr.response().error());
                        // Include partial subprocess output for debugging if available
                        String failedOutput;
                        if (cr.response().content() != null && !cr.response().content().isBlank()) {
                            failedOutput = "FAILED: " + cr.response().error()
                                + "\n\n=== Subprocess Output ===\n" + cr.response().content();
                        } else {
                            failedOutput = "FAILED: " + cr.response().error();
                        }
                        EventBroadcaster.getInstance().storeCoderOutput(
                            cr.coderName(),
                            cycle,
                            cr.task().description(),
                            failedOutput,
                            false,
                            generation
                        );
                        results.add(createTaskResult(
                            "coder-" + cycle + "-" + truncate(cr.task().id(), 8),
                            cr.task().description(), false, null, cr.response().error()));
                        broadcastFailed(cr.coderName(), "Implement", cr.response().error());
                    }
                } catch (Exception e) {
                    LOG.warn("Error processing coder result: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("Error waiting for coders to complete: {}", e.getMessage());
        }
        return responses;
    }

    /**
     * Accumulate successful responses and their work content from a batch.
     */
    private void accumulateBatchResults(
            List<AgentResponse> batchResponses,
            List<AgentResponse> allResponses,
            StringBuilder allWork) {
        allResponses.addAll(batchResponses);
        String work = batchResponses.stream()
            .filter(AgentResponse::success)
            .map(AgentResponse::content)
            .collect(java.util.stream.Collectors.joining("\n\n---\n\n"));
        if (!work.isEmpty()) {
            if (!allWork.isEmpty()) allWork.append("\n\n---\n\n");
            allWork.append(work);
        }
    }

    // ── Phase 7+8: Review & Commit ──────────────────────────────────────────────

    /**
     * Phase 7: Review all coder work. Phase 8: Commit if review succeeds.
     */
    private ReviewResult runReviewAndCommit(
            int cycle, String directorPlan, CoderPhaseResult coders,
            Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        LOG.info("Phase 7: Reviewer is reviewing work...");
        broadcastStarted("Reviewer", "Review", "Reviewing implementation...");

        String reviewerContext = "DIRECTOR'S PLAN:\n"
            + summarizeIfNeeded(directorPlan, 2000, "Director's implementation plan")
            + "\n\nCODER WORK:\n"
            + summarizeIfNeeded(coders.coderWork(), 4000, "coder implementation work");

        Task task = new Task("Review and finalize cycle " + cycle, Task.TaskType.REVIEW, Task.TaskPriority.HIGH);
        AgentResponse response = executeWithRetry("Reviewer", "Review", reviewer, task,
            ctx.withPreviousResults(reviewerContext));

        boolean committed = false;
        if (response.success()) {
            LOG.info("Reviewer completed: {}", truncate(response.content(), 200));
            broadcastSuccess("Reviewer", "Review", "Review complete", response.content(), response.durationMs());
            results.add(createTaskResult("review-" + cycle, "Review", true, response.content(), null));

            // Phase 8: Commit if review succeeded
            if (config.git().autoCommit()) {
                String commitMsg = CyclePlanParser.extractCommitMessage(response.content(), cycle);
                committed = commitChanges(commitMsg);
                broadcastCompleted("Reviewer", "Commit", "Committed: " + truncate(commitMsg, 50));
            }
        } else {
            LOG.warn("Reviewer failed: {}", response.error());
            broadcastFailed("Reviewer", "Review", response.error());
        }

        return new ReviewResult(response, committed);
    }

    // ── Phase 9: QA (Build + Test Verification) ────────────────────────────────

    /**
     * Phase 9: QA agent verifies the project builds and tests pass.
     *
     * @return the QA agent response (used as context for the Verifier phase)
     */
    private AgentResponse runQAPhase(
            int cycle, CoderPhaseResult coders, AgentResponse reviewResponse,
            Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        LOG.info("Phase 9: QA is verifying build and tests...");
        broadcastStarted("QA", "QA", "Running build and tests...");

        String qaContext = String.format("""
            CYCLE %d SUMMARY:
            - Tasks completed: %d/%d
            - Review summary: %s

            CODER WORK SUMMARY:
            %s
            """,
            cycle, coders.successCount(), coders.totalTasks(),
            summarizeIfNeeded(reviewResponse.content(), 2000, "code review results"),
            summarizeIfNeeded(coders.coderWork(), 4000, "coder implementation work")
        );

        Task task = new Task("Verify build and tests for cycle " + cycle, Task.TaskType.QA, Task.TaskPriority.HIGH);
        AgentResponse response = executeWithRetry("QA", "QA", qaAgent, task,
            ctx.withPreviousResults(qaContext));

        if (response.success()) {
            LOG.info("QA completed: {}", truncate(response.content(), 200));
            broadcastSuccess("QA", "QA", "Verification complete", response.content(), response.durationMs());
            results.add(createTaskResult("qa-" + cycle, "QA Verification", true, response.content(), null));
        } else {
            LOG.warn("QA failed: {}", response.error());
            broadcastFailed("QA", "QA", response.error());
        }
        return response;
    }

    // ── Phase 9b: Test Optimization ─────────────────────────────────────────────

    /**
     * Phase 9b: TestOptimizer identifies and fixes tests that take too long to run.
     * Runs every cycle after QA; the agent itself determines if action is needed.
     */
    private void runTestOptimizerPhase(
            int cycle, AgentResponse qaResponse,
            Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        LOG.info("Phase 9b: TestOptimizer is analyzing test execution times...");
        broadcastStarted("TestOptimizer", "TestOptimize", "Analyzing test performance...");

        String testContext = String.format("""
            CYCLE %d — QA TEST RESULTS:
            %s

            Analyze the test suite for slow tests. If QA output above includes timing
            information, use that as a starting point. Otherwise, run the tests yourself
            with verbose/timing output to measure individual test durations.
            """,
            cycle, summarizeIfNeeded(qaResponse.content(), 4000, "QA test results and timing")
        );

        Task task = new Task("Optimize slow tests for cycle " + cycle,
            Task.TaskType.TEST_OPTIMIZE, Task.TaskPriority.MEDIUM);
        AgentResponse response = executeWithRetry("TestOptimizer", "TestOptimize", testOptimizer, task,
            ctx.withPreviousResults(testContext));

        if (response.success()) {
            LOG.info("TestOptimizer completed: {}", truncate(response.content(), 200));
            broadcastSuccess("TestOptimizer", "TestOptimize", "Test optimization complete",
                response.content(), response.durationMs());
            results.add(createTaskResult("test-optimize-" + cycle, "Test Optimization",
                true, response.content(), null));
        } else {
            LOG.warn("TestOptimizer failed: {}", response.error());
            broadcastFailed("TestOptimizer", "TestOptimize", response.error());
        }
    }

    // ── Phase 10: Goal Verification ──────────────────────────────────────────────

    /**
     * Phase 10: GoalVerifier checks that what was implemented advances the project goal.
     * Runs every cycle as a mandatory step after QA, before Documentation.
     *
     * <p>When the Verifier finds [CRITICAL] gaps, Phase 10c spawns Coders to fix them
     * immediately rather than deferring to the next cycle. This ensures blocking issues
     * are resolved in the same cycle they're detected.
     *
     * @return the coders-phase result from remediation (if fixes were applied), or the
     *         original coderResult if no remediation was needed
     */
    /**
     * Phase 10: Systematic feature-by-feature verification.
     *
     * <p>Three-step process:
     * <ol>
     *   <li><b>10a — Inventory</b>: Verifier enumerates every project feature into a checklist.</li>
     *   <li><b>10b — Per-feature checks</b>: For each feature, Verifier runs a focused check
     *       (parallelized up to {@code maxConcurrentCoders}). Each check produces PASS or FAIL.</li>
     *   <li><b>10c — Remediation</b>: For every FAIL, a Coder is spawned to fix it.</li>
     * </ol>
     */
    private CoderPhaseResult runVerifierPhase(
            int cycle, CoderPhaseResult coders, AgentResponse qaResponse,
            Agent.ExecutionContext ctx, List<Result.TaskResult> results) {

        // ── Phase 10a: Feature Inventory ────────────────────────────────────────
        LOG.info("Phase 10a: Verifier is inventorying project features...");
        broadcastStarted("Verifier", "Inventory", "Building feature checklist...");

        String inventoryContext = String.format("""
            CYCLE %d IMPLEMENTATION SUMMARY:
            - Tasks completed: %d/%d

            CODER WORK SUMMARY:
            %s

            QA RESULTS:
            %s
            """,
            cycle, coders.successCount(), coders.totalTasks(),
            summarizeIfNeeded(coders.coderWork(), 4000, "coder implementation work"),
            summarizeIfNeeded(qaResponse.content(), 2000, "QA test results")
        );

        Task inventoryTask = new Task(
            "Inventory all project features for cycle " + cycle,
            Task.TaskType.VERIFY_INVENTORY, Task.TaskPriority.HIGH);
        AgentResponse inventoryResponse = executeWithRetry(
            "Verifier", "Inventory", goalVerifier, inventoryTask,
            ctx.withPreviousResults(inventoryContext));

        if (!inventoryResponse.success()) {
            LOG.warn("Verifier inventory failed: {}", inventoryResponse.error());
            broadcastFailed("Verifier", "Inventory", inventoryResponse.error());
            return coders;
        }

        List<FeatureItem> features = parseFeatureInventory(inventoryResponse.content());
        LOG.info("Phase 10a: Inventoried {} feature(s)", features.size());
        broadcastSuccess("Verifier", "Inventory",
            "Found " + features.size() + " features to verify",
            inventoryResponse.content(), inventoryResponse.durationMs());
        results.add(createTaskResult("verify-inventory-" + cycle, "Feature Inventory",
            true, inventoryResponse.content(), null));

        if (features.isEmpty() || !isNotStopped()) {
            LOG.warn("No features parsed from inventory — falling back to legacy verification");
            return runLegacyVerifierPhase(cycle, coders, qaResponse, ctx, results);
        }

        // ── Phase 10b: Per-feature verification ─────────────────────────────────
        LOG.info("Phase 10b: Verifying {} features individually...", features.size());
        broadcastStarted("Verifier", "Verify",
            "Checking " + features.size() + " features individually...");

        List<FeatureCheckResult> checkResults = runFeatureChecks(
            cycle, features, inventoryResponse.content(), ctx, results);

        int passCount = (int) checkResults.stream().filter(FeatureCheckResult::passed).count();
        int failCount = checkResults.size() - passCount;
        LOG.info("Phase 10b: {} PASS, {} FAIL out of {} features", passCount, failCount, checkResults.size());

        String checkSummary = buildFeatureCheckSummary(checkResults);
        broadcastSuccess("Verifier", "Verify",
            passCount + " passed, " + failCount + " failed out of " + checkResults.size(),
            checkSummary, 0);
        results.add(createTaskResult("verify-features-" + cycle, "Feature Verification",
            true, checkSummary, null));

        // ── Phase 10c: Remediation for failed features ──────────────────────────
        if (!isNotStopped()) return coders;

        List<Task> criticalGaps = checkResults.stream()
            .filter(cr -> !cr.passed())
            .map(cr -> new Task(
                cr.failOutput(),
                Task.TaskType.IMPLEMENT,
                Task.TaskPriority.CRITICAL))
            .toList();

        if (criticalGaps.isEmpty()) {
            LOG.info("Phase 10c: All features passed — no remediation needed");
            EventBroadcaster.getInstance().broadcastEvent(AgentEvent.builder()
                .agentName("Verifier").type(AgentEvent.EventType.COMPLETED)
                .phase("Verify")
                .message("All " + features.size() + " features verified OK")
                .details(checkSummary).build(), currentGeneration);
            return coders;
        }

        return runVerifierRemediation(cycle, criticalGaps, checkSummary, ctx, results);
    }

    /**
     * Fallback: runs the legacy single-pass verification when the inventory produces
     * no parseable features (e.g., the LLM ignored the format).
     */
    private CoderPhaseResult runLegacyVerifierPhase(
            int cycle, CoderPhaseResult coders, AgentResponse qaResponse,
            Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        LOG.info("Phase 10 (legacy): Verifier is checking goal alignment...");
        broadcastStarted("Verifier", "Verify", "Checking goal alignment (legacy mode)...");

        String verifierContext = String.format("""
            CYCLE %d IMPLEMENTATION SUMMARY:
            - Tasks completed: %d/%d

            CODER WORK SUMMARY:
            %s

            QA RESULTS:
            %s
            """,
            cycle, coders.successCount(), coders.totalTasks(),
            summarizeIfNeeded(coders.coderWork(), 4000, "coder implementation work"),
            summarizeIfNeeded(qaResponse.content(), 2000, "QA test results")
        );

        Task task = new Task("Verify goal alignment for cycle " + cycle,
            Task.TaskType.VERIFY, Task.TaskPriority.HIGH);
        AgentResponse response = executeWithRetry("Verifier", "Verify", goalVerifier, task,
            ctx.withPreviousResults(verifierContext));

        if (!response.success()) {
            broadcastFailed("Verifier", "Verify", response.error());
            return coders;
        }

        broadcastSuccess("Verifier", "Verify", "Goal verification complete",
            response.content(), response.durationMs());
        results.add(createTaskResult("verify-" + cycle, "Goal Verification",
            true, response.content(), null));

        if (!isNotStopped()) return coders;

        List<Task> criticalGaps = parseVerifierCriticalGaps(response.content(), cycle);
        if (criticalGaps.isEmpty()) return coders;

        return runVerifierRemediation(cycle, criticalGaps, response.content(), ctx, results);
    }

    /**
     * Run per-feature verification checks in parallel batches.
     *
     * <p>Each feature gets its own Verifier call with a VERIFY_FEATURE task.
     * Parallelism is bounded by {@code maxConcurrentCoders}.
     */
    private List<FeatureCheckResult> runFeatureChecks(
            int cycle, List<FeatureItem> features, String inventoryOutput,
            Agent.ExecutionContext ctx, List<Result.TaskResult> results) {

        int maxConcurrent = effectiveConcurrency();
        int totalFeatures = features.size();
        int batchCount = (totalFeatures + maxConcurrent - 1) / maxConcurrent;
        List<FeatureCheckResult> allResults = new ArrayList<>();

        for (int batch = 0; batch < batchCount && isNotStopped(); batch++) {
            int batchStart = batch * maxConcurrent;
            int batchEnd = Math.min(batchStart + maxConcurrent, totalFeatures);
            List<FeatureItem> batchFeatures = features.subList(batchStart, batchEnd);

            if (batchCount > 1) {
                LOG.info("Phase 10b: Feature check batch {}/{} ({} features)",
                    batch + 1, batchCount, batchFeatures.size());
                EventBroadcaster.getInstance().broadcastEvent(AgentEvent.builder()
                    .agentName("Verifier").type(AgentEvent.EventType.IN_PROGRESS)
                    .phase("Verify")
                    .message("Checking batch " + (batch + 1) + "/" + batchCount)
                    .build(), currentGeneration);
            }

            // Launch parallel verification for this batch
            List<CompletableFuture<FeatureCheckResult>> futures = new ArrayList<>();
            final long gen = this.currentGeneration;

            for (FeatureItem feature : batchFeatures) {
                if (!isNotStopped()) break;

                String checkName = "Check-" + feature.number();
                broadcastStarted(checkName, "Verify", "Checking: " + truncate(feature.title(), 80));

                // Background inventory first (middle = lowest attention), then the
                // specific feature assignment last (recency = highest attention).
                String featureContext = String.format("""
                    BACKGROUND — Full Feature Inventory (for context only):
                    %s

                    ---

                    YOUR ASSIGNMENT — Verify this specific feature:
                    %s

                    Focus ONLY on verifying the feature above. Do NOT verify other features.
                    """,
                    summarizeIfNeeded(inventoryOutput, 4000, "feature inventory checklist"),
                    feature.fullText()
                );

                Task checkTask = new Task(
                    "Verify feature: " + feature.title(),
                    Task.TaskType.VERIFY_FEATURE, Task.TaskPriority.HIGH);

                final FeatureItem featureFinal = feature;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    AgentResponse resp = executeWithRetry(
                        checkName, "Verify", goalVerifier, checkTask,
                        ctx.withPreviousResults(featureContext), gen);

                    if (!resp.success()) {
                        LOG.warn("{} failed: {}", checkName, resp.error());
                        broadcastFailed(checkName, "Verify", resp.error());
                        // Treat agent failure as a FAIL — we couldn't verify it
                        return new FeatureCheckResult(featureFinal, false,
                            "FAIL: " + featureFinal.title() + "\nWhat: Verification agent failed\n"
                            + "Why: Could not verify this feature\n"
                            + "Fix: Manual inspection required — agent error: " + resp.error());
                    }

                    // Search the first few lines for PASS:/FAIL: verdict — LLMs sometimes
                    // add preamble before the verdict despite instructions not to.
                    boolean passed = detectPassVerdict(resp.content());
                    String content = resp.content();

                    if (passed) {
                        broadcastSuccess(checkName, "Verify",
                            "PASS: " + featureFinal.title(), content, resp.durationMs());
                    } else {
                        broadcastCompleted(checkName, "Verify",
                            "FAIL: " + featureFinal.title());
                    }

                    return new FeatureCheckResult(featureFinal, passed, content);
                }, executor));
            }

            // Collect batch results
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
            try {
                allFutures.join();
            } catch (java.util.concurrent.CompletionException ce) {
                LOG.warn("Feature check batch completed with errors — collecting results");
            }
            for (CompletableFuture<FeatureCheckResult> f : futures) {
                try {
                    allResults.add(f.join());
                } catch (Exception e) {
                    LOG.warn("Error collecting feature check result: {}", e.getMessage());
                }
            }
        }

        return allResults;
    }

    /**
     * Parse the feature inventory output into structured items.
     *
     * <p>Expects lines starting with {@code FEATURE N:} followed by Description and Location lines.
     */
    /**
     * Detect whether a feature verification response is a PASS or FAIL.
     * Searches the first 10 lines for a line starting with "PASS:" or "FAIL:"
     * (case-insensitive) to handle LLM preamble before the verdict.
     * If "PASS:" is found before "FAIL:", returns true. Otherwise false.
     */
    private boolean detectPassVerdict(String content) {
        if (content == null || content.isBlank()) return false;
        String[] lines = content.split("\n");
        int linesToCheck = Math.min(lines.length, 10);
        for (int i = 0; i < linesToCheck; i++) {
            String trimmed = lines[i].trim().toUpperCase();
            if (trimmed.startsWith("PASS:")) return true;
            if (trimmed.startsWith("FAIL:")) return false;
        }
        // No explicit verdict found — treat as FAIL (safer to verify than to skip)
        return false;
    }

    private List<FeatureItem> parseFeatureInventory(String inventoryOutput) {
        if (inventoryOutput == null || inventoryOutput.isBlank()) return List.of();

        List<FeatureItem> features = new ArrayList<>();
        String[] lines = inventoryOutput.split("\n");
        int currentNumber = 0;
        String currentTitle = null;
        StringBuilder currentBlock = null;

        // Matches FEATURE N: Title with optional markdown decoration:
        //   FEATURE 1: Title
        //   **FEATURE 1:** Title
        //   ### FEATURE 1: Title
        //   **FEATURE 1**: Title
        java.util.regex.Pattern featurePattern = java.util.regex.Pattern
            .compile("^(?:#{1,4}\\s+)?\\*{0,2}FEATURE\\s+(\\d+)\\*{0,2}:\\*{0,2}\\s*(.+)$",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        for (String line : lines) {
            String trimmed = line.trim();
            java.util.regex.Matcher matcher = featurePattern.matcher(trimmed);

            if (matcher.matches()) {
                // Save previous feature if any
                if (currentTitle != null && currentBlock != null) {
                    features.add(new FeatureItem(currentNumber, currentTitle, currentBlock.toString().trim()));
                }
                currentNumber = Integer.parseInt(matcher.group(1));
                currentTitle = matcher.group(2).trim();
                currentBlock = new StringBuilder(trimmed);
            } else if (currentBlock != null) {
                currentBlock.append("\n").append(trimmed);
            }
        }

        // Don't forget the last feature
        if (currentTitle != null && currentBlock != null) {
            features.add(new FeatureItem(currentNumber, currentTitle, currentBlock.toString().trim()));
        }

        LOG.info("Parsed {} features from inventory output", features.size());
        return features;
    }

    /**
     * Build a human-readable summary of all feature check results.
     */
    private String buildFeatureCheckSummary(List<FeatureCheckResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("FEATURE VERIFICATION RESULTS:\n\n");

        for (FeatureCheckResult cr : results) {
            sb.append(cr.passed() ? "✓ PASS" : "✗ FAIL");
            sb.append(": Feature ").append(cr.feature().number())
              .append(" — ").append(cr.feature().title()).append("\n");
        }

        sb.append("\n");
        int passed = (int) results.stream().filter(FeatureCheckResult::passed).count();
        sb.append("TOTAL: ").append(passed).append("/").append(results.size()).append(" passed\n");

        // Append full FAIL details for remediation context
        List<FeatureCheckResult> failures = results.stream()
            .filter(cr -> !cr.passed()).toList();
        if (!failures.isEmpty()) {
            sb.append("\n── FAILURE DETAILS ──\n\n");
            for (FeatureCheckResult cr : failures) {
                sb.append("Feature ").append(cr.feature().number())
                  .append(": ").append(cr.feature().title()).append("\n");
                sb.append(cr.output()).append("\n\n");
            }
        }

        return sb.toString();
    }

    /**
     * Phase 10c: Spawn Coders to fix ALL critical gaps identified by the Verifier,
     * then review and commit the fixes.
     *
     * <p>Gaps are processed in batches of {@code maxConcurrentCoders} to respect
     * resource limits while ensuring every critical gap is addressed within the
     * same cycle. After all batches complete, a single review-and-commit pass
     * covers all remediation work.
     */
    private CoderPhaseResult runVerifierRemediation(
            int cycle, List<Task> gaps, String verifierOutput,
            Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        int gapCount = gaps.size();
        int maxCoders = effectiveConcurrency();
        int batchCount = (gapCount + maxCoders - 1) / maxCoders; // ceiling division

        LOG.info("Phase 10c: Verifier found {} critical gap(s) — spawning remediation coders in {} batch(es) (max {} concurrent)",
            gapCount, batchCount, maxCoders);
        broadcastStarted("Verifier", "Remediate",
            "Fixing " + gapCount + " critical gap(s) in " + batchCount + " batch(es)...");

        // Process all gaps in batches of maxConcurrentCoders
        List<AgentResponse> allResponses = new ArrayList<>();
        StringBuilder allFixWork = new StringBuilder();
        int totalSuccess = 0;
        int globalCoderIndex = 0;

        for (int batch = 0; batch < batchCount && isNotStopped(); batch++) {
            int batchStart = batch * maxCoders;
            int batchEnd = Math.min(batchStart + maxCoders, gapCount);
            List<Task> batchTasks = gaps.subList(batchStart, batchEnd);

            if (batchCount > 1) {
                LOG.info("Phase 10c: Starting batch {}/{} ({} gap(s))", batch + 1, batchCount, batchTasks.size());
                EventBroadcaster.getInstance().broadcastEvent(AgentEvent.builder()
                    .agentName("Verifier").type(AgentEvent.EventType.IN_PROGRESS)
                    .phase("Remediate")
                    .message("Batch " + (batch + 1) + "/" + batchCount + ": fixing " + batchTasks.size() + " gap(s)")
                    .build(), currentGeneration);
            }

            // Spawn coders for this batch
            List<CompletableFuture<CoderResult>> futures = new ArrayList<>();
            for (int i = 0; i < batchTasks.size(); i++) {
                if (!isNotStopped()) break;

                globalCoderIndex++;
                Task fixTask = batchTasks.get(i);
                String coderName = "Fix-" + globalCoderIndex;
                LOG.info("{} starting: {}", coderName, truncate(fixTask.description(), 80));
                broadcastStarted(coderName, "Remediate", truncate(fixTask.description(), 200));

                // Background: full verifier output first (context), then the specific gap last (recency).
                String fixAssignment = buildRemediationAssignment(fixTask, globalCoderIndex, gapCount, verifierOutput);
                Agent.ExecutionContext fixCtx = ctx.withPreviousResults(fixAssignment);
                futures.add(executeCoderWithRetry(coderName, globalCoderIndex, fixTask, fixCtx));
            }

            // Collect batch results — each coder handles pause/resume internally
            List<AgentResponse> batchResponses = collectCoderResults(cycle, futures, results, currentGeneration);
            accumulateBatchResults(batchResponses, allResponses, allFixWork);
            totalSuccess += (int) batchResponses.stream().filter(AgentResponse::success).count();

            if (batchCount > 1) {
                int batchSuccess = (int) batchResponses.stream().filter(AgentResponse::success).count();
                LOG.info("Phase 10c: Batch {}/{} complete — {}/{} gaps fixed",
                    batch + 1, batchCount, batchSuccess, batchTasks.size());
            }
        }

        CoderPhaseResult fixResult = new CoderPhaseResult(
            allResponses, allFixWork.toString(), totalSuccess, gapCount);

        EventBroadcaster.getInstance().broadcastEvent(AgentEvent.builder()
            .agentName("Verifier").type(AgentEvent.EventType.COMPLETED)
            .phase("Remediate")
            .message("Fixed " + totalSuccess + "/" + gapCount + " critical gaps")
            .details("Remediation complete").build(), currentGeneration);

        // Review and commit all fixes in one pass
        if (isNotStopped() && totalSuccess > 0) {
            LOG.info("Phase 10c: Reviewing and committing verifier fixes...");
            ReviewResult fixReview = runReviewAndCommit(cycle, verifierOutput, fixResult, ctx, results);
            if (fixReview.commitSuccessful()) {
                LOG.info("Verifier remediation committed successfully");
            }
        }

        LOG.info("Phase 10c: Remediation complete — {} of {} gaps fixed", totalSuccess, gapCount);
        return fixResult;
    }

    /**
     * Parse [CRITICAL] gaps from Verifier output into implementation tasks.
     *
     * <p>Each numbered {@code [CRITICAL]} item becomes a task whose description includes
     * the full gap text (What/Why/Fix/How) so the Coder has complete context.
     */
    private List<Task> parseVerifierCriticalGaps(String verifierOutput, int cycle) {
        if (verifierOutput == null || verifierOutput.isBlank()) {
            return List.of();
        }

        // If verifier said VERIFIED, no gaps (case-insensitive)
        if (verifierOutput.toUpperCase().contains("VERIFIED:")) {
            return List.of();
        }

        List<Task> gaps = new ArrayList<>();
        String[] lines = verifierOutput.split("\n");
        StringBuilder currentGap = null;

        for (String line : lines) {
            String trimmed = line.trim();

            // Start of a new [CRITICAL] item (case-insensitive)
            if (trimmed.matches("(?i)^\\d+\\.\\s*\\[CRITICAL].*")) {
                // Save previous gap if any
                if (currentGap != null) {
                    gaps.add(new Task(
                        currentGap.toString().trim(),
                        Task.TaskType.IMPLEMENT,
                        Task.TaskPriority.CRITICAL
                    ));
                }
                currentGap = new StringBuilder(trimmed);
            } else if (currentGap != null) {
                // Check if we hit a new numbered item (non-critical) or end marker
                if (trimmed.matches("(?i)^\\d+\\.\\s+(?!\\[CRITICAL]).*") || trimmed.toUpperCase().startsWith("VERIFIED:")) {
                    gaps.add(new Task(
                        currentGap.toString().trim(),
                        Task.TaskType.IMPLEMENT,
                        Task.TaskPriority.CRITICAL
                    ));
                    currentGap = null;
                } else {
                    // Continue building the current gap's description
                    currentGap.append("\n").append(trimmed);
                }
            }
        }

        // Don't forget the last gap
        if (currentGap != null) {
            gaps.add(new Task(
                currentGap.toString().trim(),
                Task.TaskType.IMPLEMENT,
                Task.TaskPriority.CRITICAL
            ));
        }

        LOG.info("Parsed {} [CRITICAL] gap(s) from Verifier output", gaps.size());
        return gaps;
    }

    // ── Phase 11: Cleanup ─────────────────────────────────────────────────────────

    // ── Phase 10d: Organizer ───────────────────────────────────────────────────

    /** Source-file extensions worth scanning for size (add more as needed). */
    private static final java.util.Set<String> SOURCE_EXTENSIONS = java.util.Set.of(
        ".java", ".kt", ".scala", ".groovy",          // JVM
        ".py", ".pyi",                                 // Python
        ".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs",  // JS/TS
        ".go",                                         // Go
        ".rs",                                         // Rust
        ".c", ".h", ".cpp", ".hpp", ".cc",             // C/C++
        ".cs",                                         // C#
        ".rb",                                         // Ruby
        ".swift",                                      // Swift
        ".vue", ".svelte",                             // Web frameworks
        ".html", ".css", ".scss",                      // Web
        ".sql",                                        // SQL
        ".sh", ".bash", ".zsh"                         // Shell
    );

    /**
     * Phase 10d (every other cycle): scan the project for source files that
     * exceed {@link OrganizerAgent#MAX_FILE_CHARS} and, if any are found,
     * invoke the Organizer agent to refactor them.
     */
    private void runOrganizerPhase(
            int cycle, Path workingDir, Agent.ExecutionContext ctx, List<Result.TaskResult> results) {

        // Quick scan — collect oversized source files
        List<String> oversized = new java.util.ArrayList<>();
        try (var walk = Files.walk(workingDir, 20)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return SOURCE_EXTENSIONS.stream().anyMatch(name::endsWith);
                })
                .filter(p -> {
                    // Skip build output, node_modules, .git, etc.
                    String rel = workingDir.relativize(p).toString();
                    return !rel.contains("node_modules")
                        && !rel.startsWith("build" + java.io.File.separator)
                        && !rel.startsWith("target" + java.io.File.separator)
                        && !rel.startsWith("dist" + java.io.File.separator)
                        && !rel.startsWith(".git" + java.io.File.separator);
                })
                .forEach(p -> {
                    try {
                        // Read file content to count actual characters (not bytes).
                        // Files.size() returns bytes which overstates length for
                        // multi-byte encoded files.
                        long charCount = Files.readString(p, java.nio.charset.StandardCharsets.UTF_8).length();
                        if (charCount > OrganizerAgent.MAX_FILE_CHARS) {
                            String rel = workingDir.relativize(p).toString();
                            oversized.add("- " + rel + " (" + charCount + " chars, ~"
                                + (charCount / 4) + " tokens)");
                        }
                    } catch (IOException | java.io.UncheckedIOException ignored) {
                        /* skip unreadable or binary files */
                    }
                });
        } catch (IOException e) {
            LOG.warn("Organizer: could not scan working directory: {}", e.getMessage());
        }

        if (oversized.isEmpty()) {
            LOG.info("Phase 10d: Organizer — all source files are within size limits, skipping");
            broadcastCompleted("Organizer", "Organize", "All files within size limits");
            return;
        }

        LOG.info("Phase 10d: Organizer found {} oversized file(s) — refactoring...", oversized.size());
        broadcastStarted("Organizer", "Organize",
            "Found " + oversized.size() + " oversized file(s)...");

        String fileList = String.join("\n", oversized);
        Task task = new Task(
            "Refactor " + oversized.size() + " oversized source file(s) for cycle " + cycle,
            Task.TaskType.ORGANIZE, Task.TaskPriority.MEDIUM);
        AgentResponse response = executeWithRetry("Organizer", "Organize", organizer, task,
            ctx.withPreviousResults(fileList));

        if (response.success()) {
            LOG.info("Organizer completed: {}", truncate(response.content(), 200));
            broadcastSuccess("Organizer", "Organize", "Refactoring complete",
                response.content(), response.durationMs());
            results.add(createTaskResult("organize-" + cycle, "Organizer",
                true, response.content(), null));
        } else {
            LOG.warn("Organizer failed: {}", response.error());
            broadcastFailed("Organizer", "Organize", response.error());
        }
    }

    // ── Phase 11: Cleanup ────────────────────────────────────────────────────────

    /**
     * Phase 11: Cleaner removes temporary files, test artifacts, and build garbage.
     */
    private void runCleanerPhase(
            int cycle, Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        LOG.info("Phase 11: Cleaner is removing temp files and test artifacts...");
        broadcastStarted("Cleaner", "Clean", "Scanning for temporary files...");

        Task task = new Task("Clean working directory for cycle " + cycle, Task.TaskType.CLEAN, Task.TaskPriority.LOW);
        AgentResponse response = executeWithRetry("Cleaner", "Clean", cleaner, task,
            ctx.withPreviousResults("Cycle " + cycle + " complete. Scan for and remove temporary files, "
                + "test output, build garbage, and other detritus."));

        if (response.success()) {
            LOG.info("Cleaner completed: {}", truncate(response.content(), 200));
            broadcastSuccess("Cleaner", "Clean", "Cleanup complete", response.content(), response.durationMs());
            results.add(createTaskResult("clean-" + cycle, "Cleanup", true, response.content(), null));
        } else {
            LOG.warn("Cleaner failed: {}", response.error());
            broadcastFailed("Cleaner", "Clean", response.error());
        }
    }

    // ── Phase 12: Documentation ──────────────────────────────────────────────────

    /**
     * Phase 12: Documenter regenerates README.md and project docs from source code.
     */
    private void runDocumentation(
            int cycle, CoderPhaseResult coders, AgentResponse reviewResponse,
            Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        LOG.info("Phase 12: Documenter is regenerating documentation...");
        broadcastStarted("Documenter", "Document", "Updating README.md...");

        String docContext = String.format("""
            CYCLE %d SUMMARY:
            - Tasks completed: %d/%d
            - Review summary: %s

            CODER WORK SUMMARY:
            %s
            """,
            cycle, coders.successCount(), coders.totalTasks(),
            summarizeIfNeeded(reviewResponse.content(), 2000, "code review results"),
            summarizeIfNeeded(coders.coderWork(), 4000, "coder implementation work")
        );

        Task task = new Task("Update documentation for cycle " + cycle, Task.TaskType.DOCUMENT, Task.TaskPriority.MEDIUM);
        AgentResponse response = executeWithRetry("Documenter", "Document", documenter, task,
            ctx.withPreviousResults(docContext));

        if (response.success()) {
            LOG.info("Documenter completed: {}", truncate(response.content(), 200));
            broadcastSuccess("Documenter", "Document", "Documentation updated", response.content(), response.durationMs());
            results.add(createTaskResult("document-" + cycle, "Documentation", true, response.content(), null));
        } else {
            LOG.warn("Documenter failed: {}", response.error());
            broadcastFailed("Documenter", "Document", response.error());
        }
    }
    
    // ── Phase 0: Synthesizer (periodic) ───────────────────────────────────────────
    
    /**
     * Run Synthesizer every N cycles to merge ideas from recent cycles.
     */
    private void runSynthesizerPhase(int cycle, Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        LOG.info("Phase 0: Synthesizer is reviewing recent cycles for synergies...");
        broadcastStarted("Synthesizer", "Synthesize", "Looking for synergies across recent cycles...");
        
        String historyContext = historyManager.getRecentHistorySummary(config.orchestration().synthesizeInterval());
        
        Task task = new Task(
            "Synthesize ideas from recent " + config.orchestration().synthesizeInterval() + " cycles",
            Task.TaskType.SYNTHESIZE, Task.TaskPriority.HIGH
        );
        
        AgentResponse response = executeWithRetry("Synthesizer", "Synthesize", synthesizer, task,
            ctx.withPreviousResults(historyContext));

        if (response.success()) {
            LOG.info("Synthesizer found synergies: {}", truncate(response.content(), 200));
            latestSynthesisInsights = summarizeIfNeeded(response.content(), 4000, "synthesizer insights");
            broadcastSuccess("Synthesizer", "Synthesize", "Found synergies", response.content(), response.durationMs());
            results.add(createTaskResult("synthesize-" + cycle, "Synthesis", true, response.content(), null));
        } else {
            LOG.warn("Synthesizer failed: {}", response.error());
            broadcastFailed("Synthesizer", "Synthesize", response.error());
        }
    }
    
    // ── Phase 2: Idea Scoring ─────────────────────────────────────────────────────
    
    /**
     * Phase 2: Score idea quality and reshape if below threshold.
     *
     * <p>The Scorer never kills a cycle for scoring reasons. If ideas score below
     * threshold, the Scorer provides reshaped alternatives that the cycle continues with.
     *
     * @return scored idea (possibly reshaped), or empty only on hard failure (LLM error, stop requested)
     */
    private Optional<ScoredIdea> runScoringPhase(
            int cycle, IdeaResult idea, Agent.ExecutionContext ctx, List<Result.TaskResult> results) {

        // Check for duplicate ideas — pass similarity warning as context to the Scorer
        // instead of aborting, so the Scorer can factor it in and reshape accordingly.
        String duplicateWarning = "";
        CycleHistoryManager.SimilarityCheck similarity = historyManager.checkSimilarity(idea.ideas());
        if (similarity.isDuplicate()) {
            LOG.warn("Idea is similar to previous cycle ({}%): {}",
                (int)(similarity.similarity() * 100), similarity.getWarning());
            duplicateWarning = String.format(
                "\n\n**DUPLICATE WARNING**: This idea is %.0f%% similar to a previous cycle. %s\n" +
                "You should RESHAPE these ideas to be significantly different.\n",
                similarity.similarity() * 100, similarity.getWarning());
        }

        LOG.info("Phase 2: Scorer is evaluating idea quality...");
        broadcastStarted("Scorer", "Score", "Evaluating idea quality...");

        Task task = new Task(
            "Score idea quality for cycle " + cycle,
            Task.TaskType.SCORE, Task.TaskPriority.HIGH
        );

        // Include cycle history so the Scorer can evaluate novelty against recent work
        String recentHistory = getRecentHistory();
        StringBuilder scorerInput = new StringBuilder();
        if (!recentHistory.isBlank()) {
            scorerInput.append("RECENT CYCLE HISTORY (for novelty evaluation):\n");
            scorerInput.append(recentHistory).append("\n\n");
        }
        scorerInput.append("IDEAS TO SCORE:\n");
        scorerInput.append(idea.ideas());
        scorerInput.append(duplicateWarning);

        AgentResponse response = executeWithRetry("Scorer", "Score", scorer, task,
            ctx.withPreviousResults(scorerInput.toString()));

        if (!response.success()) {
            broadcastFailed("Scorer", "Score", response.error());
            return Optional.empty();
        }

        IdeaScore score = ScorerAgent.parseScore(response.content(),
            config.orchestration().minGoalAlignment(),
            config.orchestration().minOverallScore());
        LOG.info("Idea score: alignment={}, novelty={}, feasibility={}, overall={}, belowThreshold={}",
            score.goalAlignment(), score.novelty(), score.feasibility(),
            score.overallScore(), score.belowThreshold());

        // Determine the effective idea to continue with
        IdeaResult effectiveIdea;
        String statusMessage;

        if (score.wasReshaped()) {
            LOG.info("Scorer reshaped ideas (original score: {}/10)", score.overallScore());
            effectiveIdea = new IdeaResult(score.reshapedIdeas(),
                idea.agentName() + " (reshaped by Scorer)");
            statusMessage = String.format("Reshaped (score: %d/10)", score.overallScore());
        } else if (score.belowThreshold()) {
            // Below threshold but Scorer didn't provide reshaped ideas — continue with originals
            LOG.warn("Scorer scored below threshold ({}/10) but no reshaped ideas provided, continuing with originals",
                score.overallScore());
            effectiveIdea = idea;
            statusMessage = String.format("Below threshold (score: %d/10) - continuing with originals",
                score.overallScore());
        } else {
            effectiveIdea = idea;
            statusMessage = String.format("Approved (score: %d/10)", score.overallScore());
        }

        // Always store full output so the dashboard can display it
        broadcastSuccess("Scorer", "Score", statusMessage, response.content(), response.durationMs());
        results.add(createTaskResult("score-" + cycle, "Idea Scoring", true,
            String.format("Score: %d/10 - %s", score.overallScore(), score.reasoning()), null));

        return Optional.of(new ScoredIdea(effectiveIdea, score));
    }
    
    // ── Phase 4: Strategic Evaluation (Architect) ─────────────────────────────────
    
    /**
     * Phase 4: Architect evaluates strategic alignment.
     */
    private Optional<ArchitectInput> runArchitectPhase(
            int cycle, ScoredIdea idea, String critique,
            Agent.ExecutionContext ctx, List<Result.TaskResult> results) {
        
        LOG.info("Phase 4: Architect is evaluating strategic alignment...");
        broadcastStarted("Architect", "Evaluate", "Evaluating strategic alignment...");
        
        String architectContext = String.format("""
            %s'S IDEAS:
            %s
            
            SKEPTIC'S CRITIQUE:
            %s
            
            PROJECT PHASE: %s
            PROJECT TYPE: %s
            """,
            idea.original().agentName().toUpperCase(),
            idea.original().ideas(),
            critique,
            currentPhase.getDisplayName(),
            projectType.getDisplayName()
        );
        
        Task task = new Task(
            "Evaluate strategic alignment for cycle " + cycle,
            Task.TaskType.ARCHITECT, Task.TaskPriority.HIGH
        );
        
        AgentResponse response = executeWithRetry("Architect", "Evaluate", architect, task,
            ctx.withPreviousResults(architectContext));

        if (!response.success()) {
            broadcastFailed("Architect", "Evaluate", response.error());
            // Don't abort cycle on Architect failure - use defaults
            return Optional.of(new ArchitectInput(5, Task.TaskPriority.MEDIUM, "None", "Architect evaluation failed", "Proceed with caution"));
        }
        
        int alignment = ArchitectAgent.parseAlignment(response.content());
        Task.TaskPriority priority = ArchitectAgent.parsePriority(response.content());

        String deps           = parseResponseSection(response.content(), "Dependencies");
        String concerns       = parseResponseSection(response.content(), "Concerns");
        String recommendation = parseResponseSection(response.content(), "Convergence Recommendation");
        // Fall back to truncated full response if sections were not found
        if (deps.isEmpty())           deps           = "N/A";
        if (concerns.isEmpty())       concerns       = "N/A";
        if (recommendation.isEmpty()) recommendation = truncate(response.content(), 300);

        LOG.info("Architect verdict: alignment={}/10, priority={}", alignment, priority);
        broadcastSuccess("Architect", "Evaluate",
            String.format("Alignment: %d/10, Priority: %s", alignment, priority),
            response.content(), response.durationMs());
        results.add(createTaskResult("architect-" + cycle, "Strategic Evaluation", true, response.content(), null));

        return Optional.of(new ArchitectInput(alignment, priority,
            truncate(deps, 200), truncate(concerns, 200), truncate(recommendation, 300)));
    }

    /**
     * Merge two CoderPhaseResults into one combined result.
     * Used when verifier remediation adds additional coder work to the cycle.
     */
    private CoderPhaseResult mergeCoderResults(CoderPhaseResult original, CoderPhaseResult remediation) {
        List<AgentResponse> allResponses = new ArrayList<>(original.responses());
        allResponses.addAll(remediation.responses());

        String combinedWork = original.coderWork();
        if (!remediation.coderWork().isEmpty()) {
            combinedWork = combinedWork.isEmpty()
                ? remediation.coderWork()
                : combinedWork + "\n\n--- VERIFIER FIXES ---\n\n" + remediation.coderWork();
        }

        return new CoderPhaseResult(
            allResponses,
            combinedWork,
            original.successCount() + remediation.successCount(),
            original.totalTasks() + remediation.totalTasks()
        );
    }

    // ── Event broadcasting helpers ──────────────────────────────────────────────

    private void broadcastStarted(String agent, String phase, String message) {
        EventBroadcaster.getInstance().broadcastEvent(AgentEvent.builder()
            .agentName(agent).type(AgentEvent.EventType.STARTED)
            .phase(phase).message(message).build(), currentGeneration);
    }

    private void broadcastCompleted(String agent, String phase, String message) {
        EventBroadcaster.getInstance().broadcastEvent(AgentEvent.builder()
            .agentName(agent).type(AgentEvent.EventType.COMPLETED)
            .phase(phase).message(message).build(), currentGeneration);
    }

    private void broadcastSuccess(String agent, String phase, String message, String fullOutput, long durationMs) {
        EventBroadcaster broadcaster = EventBroadcaster.getInstance();
        broadcaster.storeAgentOutput(agent, activeCycle, phase, fullOutput, currentGeneration);
        broadcaster.broadcastEvent(AgentEvent.builder()
            .agentName(agent).type(AgentEvent.EventType.COMPLETED)
            .phase(phase).message(message).details(truncate(fullOutput, 500))
            .durationMs(durationMs).build(), currentGeneration);
    }

    private void broadcastFailed(String agent, String phase, String error) {
        EventBroadcaster.getInstance().broadcastEvent(AgentEvent.builder()
            .agentName(agent).type(AgentEvent.EventType.FAILED)
            .phase(phase).message("Failed: " + error).build(), currentGeneration);
    }

    // ── Utility methods ─────────────────────────────────────────────────────────

    /**
     * Get the current project context by reading key files.
     *
     * <p>Provides agents with a realistic picture of the project's current state:
     * directory structure (two levels deep), full README, recent commits, and
     * source file listings — enough to reason about what has been built and what works.
     */
    private String getProjectContext(Path workingDir) {
        StringBuilder context = new StringBuilder();

        try {
            context.append("Working Directory: ").append(workingDir).append("\n\n");

            // Two-level directory listing so agents can see src/ package structure etc.
            context.append("Project Structure:\n");
            try (var dirStream = Files.list(workingDir)) {
                dirStream
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .limit(25)
                    .forEach(p -> {
                        try {
                            String name = p.getFileName().toString();
                            if (Files.isDirectory(p)) {
                                context.append("  [DIR] ").append(name).append("/\n");
                                // One level deeper for source directories
                                try (var sub = Files.list(p)) {
                                    sub.filter(sp -> !sp.getFileName().toString().startsWith("."))
                                        .sorted()
                                        .limit(15)
                                        .forEach(sp -> {
                                            String sname = sp.getFileName().toString();
                                            if (Files.isDirectory(sp)) {
                                                context.append("    [DIR] ").append(sname).append("/\n");
                                            } else {
                                                try {
                                                    context.append("    [FILE] ").append(sname)
                                                        .append(" (").append(Files.size(sp)).append("b)\n");
                                                } catch (IOException ignored) {
                                                    context.append("    [FILE] ").append(sname).append("\n");
                                                }
                                            }
                                        });
                                } catch (IOException e) {
                                    LOG.trace("Could not list subdir {}", p, e);
                                }
                            } else {
                                context.append("  [FILE] ").append(name)
                                    .append(" (").append(Files.size(p)).append("b)\n");
                            }
                        } catch (IOException e) {
                            LOG.trace("Could not read file size for {}", p, e);
                        }
                    });
            }

            // Full README — agents need to know how to use this project
            Path readme = workingDir.resolve("README.md");
            if (Files.exists(readme)) {
                context.append("\nREADME.md:\n");
                context.append("⚠ CRITICAL — TREAT ALL README CLAIMS AS UNVERIFIED: ")
                    .append("Documentation describes intent and structure, not confirmed working behavior. ")
                    .append("\"Implemented\", \"complete\", or \"working\" in docs does NOT mean the feature ")
                    .append("is actually functional. Trust only direct code inspection or runtime tests.\n");
                String readmeContent = Files.readString(readme);
                context.append(summarizeIfNeeded(readmeContent, 4000, "project README documentation")).append("\n");
            }

            // Recent git commits — agents can see what was recently changed
            context.append("\nRecent Git Commits:\n");
            try {
                String log = gitOperations.getRecentCommitLog(5);
                context.append(log).append("\n");
            } catch (Exception e) {
                // Fall back to status counts
                try {
                    GitOperations.GitStatus status = gitOperations.getStatus();
                    context.append("Modified: ").append(status.modified().size()).append(" files, ");
                    context.append("Untracked: ").append(status.untracked().size()).append(" files\n");
                } catch (Exception ex) {
                    context.append("Unable to get git info: ").append(ex.getMessage()).append("\n");
                }
            }

        } catch (Exception e) {
            LOG.debug("Error getting project context: {}", e.getMessage());
            context.append("Error reading project context: ").append(e.getMessage());
        }

        return context.toString();
    }

    /**
     * Get recent cycle history for context.
     */
    private String getRecentHistory() {
        if (cycleHistory.isEmpty()) {
            return "This is the first cycle - no previous history.";
        }

        StringBuilder history = new StringBuilder("Recent cycles:\n\n");

        // Collect the last 3 cycles using descending iterator (O(1) per element)
        List<CycleResult> recentCycles = new ArrayList<>(3);
        Iterator<CycleResult> desc = cycleHistory.descendingIterator();
        while (desc.hasNext() && recentCycles.size() < 3) {
            recentCycles.add(desc.next());
        }
        // Reverse to get chronological order
        Collections.reverse(recentCycles);

        for (CycleResult cycle : recentCycles) {
            history.append("Cycle ").append(cycle.cycleNumber()).append(":\n");
            history.append("  Idea source: ").append(cycle.ideaAgentName()).append("\n");
            history.append("  Tasks completed: ").append(cycle.tasksCompleted()).append("/").append(cycle.tasksAttempted()).append("\n");
            history.append("  Duration: ").append(cycle.duration()).append("\n\n");
        }

        return history.toString();
    }

    private boolean commitChanges(String message) {
        try {
            gitOperations.commitAll(message);
            LOG.info("Committed changes: {}", truncate(message, 50));
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to commit changes: {}", e.getMessage());
            return false;
        }
    }

    private Result.TaskResult createTaskResult(
        String taskId,
        String description,
        boolean success,
        String output,
        String error
    ) {
        return new Result.TaskResult(taskId, description, success, output, error, Duration.ZERO);
    }
    
    /**
     * Record cycle outcome for learning and deduplication.
     */
    private void recordCycleOutcome(int cycle, IdeaResult idea, ScoredIdea scoredIdea,
            CoderPhaseResult coderResult, AgentResponse reviewResponse,
            boolean commitSuccessful, Duration duration) {

        int reviewIssuesFound = countReviewIssues(reviewResponse);
        int reviewIssuesFixed = countReviewFixes(reviewResponse);
        
        int completed = coderResult.responses().size();
        int successful = coderResult.successCount();
        CycleOutcome outcome = new CycleOutcome(
            cycle,
            idea.agentName(),
            truncate(idea.ideas(), 200),
            scoredIdea.score(),
            coderResult.totalTasks(),
            completed,
            successful,
            reviewIssuesFound,
            reviewIssuesFixed,
            commitSuccessful,
            duration,
            null
        );
        
        historyManager.addCycle(outcome);
        
        LOG.info("Cycle {} outcome recorded: quality score={}/10, implementation success={}/{}",
            cycle, outcome.calculateIdeaQualityScore(), 
            coderResult.successCount(), coderResult.totalTasks());
    }
    
    private int countReviewIssues(AgentResponse response) {
        if (response == null || !response.success() || response.content() == null) return 0;
        String content = response.content().toLowerCase();
        int count = 0;
        // Count positive indicators of issues, but subtract negated phrases
        // to avoid false positives like "no issues found but found a security bug"
        if (content.contains("issue") || content.contains("problem") || content.contains("bug")) count++;
        if (content.contains("security")) count++;
        if (content.contains("performance")) count++;
        // If all we found are negated phrases, no real issues
        if (count > 0) {
            int negations = 0;
            if (content.contains("no issues") || content.contains("no problems") || content.contains("no bugs")) negations++;
            if (content.contains("no security")) negations++;
            if (content.contains("no performance")) negations++;
            count = Math.max(0, count - negations);
        }
        return count;
    }
    
    private int countReviewFixes(AgentResponse response) {
        if (response == null || !response.success() || response.content() == null) return 0;
        String content = response.content().toLowerCase();
        int count = 0;
        if (content.contains("fixed") || content.contains("resolved")) count++;
        if (content.contains("applied fix")) count++;
        return count;
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return "";
        if (s.length() <= maxLength) return s;
        if (maxLength <= 3) return s.substring(0, Math.min(s.length(), maxLength));
        return s.substring(0, maxLength - 3) + "...";
    }

    /**
     * Condense content via LLM summarization when it exceeds a character threshold.
     * Returns the original content unchanged if it is short enough.
     *
     * <p>The {@code purpose} parameter tells the summarizer what information to
     * preserve (e.g. "coder implementation work" or "QA test results"), so the
     * summary retains domain-relevant details rather than producing a generic
     * abstract.
     *
     * <p>If the LLM call fails for any reason, falls back to returning the
     * original content unmodified (no data loss, just potentially long context).
     *
     * @param content   the text to potentially summarize
     * @param maxChars  threshold — content at or below this length is returned as-is
     * @param purpose   a short label describing what this content represents
     * @return the original content (if short) or a concise LLM-generated summary
     */
    /** Content shorter than this is never summarized — passed through as-is. */
    private static final int SUMMARIZE_THRESHOLD = 10_000;

    private String summarizeIfNeeded(String content, int maxChars, String purpose) {
        if (content == null || content.isBlank()) return "";
        if (content.length() <= SUMMARIZE_THRESHOLD) return content;

        LOG.debug("Summarizing {} ({} chars → target ~{} chars)", purpose, content.length(), maxChars);

        String systemPrompt = "You are a concise summarizer. Preserve ALL key facts, file names, "
            + "feature names, test results, and actionable details. Drop only filler and repetition. "
            + "Output the summary directly — no preamble, no meta-commentary.";

        String userPrompt = String.format(
            "Summarize the following %s into roughly %d characters or fewer.\n"
            + "Preserve every concrete detail (file names, class names, feature names, pass/fail "
            + "results, error messages, numerical values). Only remove redundancy and verbose prose.\n\n"
            + "--- CONTENT TO SUMMARIZE ---\n%s",
            purpose, maxChars, content);

        try {
            AgentResponse response = llm.sendPrompt(systemPrompt, userPrompt).join();
            if (response.success() && response.hasContent()) {
                LOG.debug("Summarized {} from {} to {} chars", purpose, content.length(), response.content().length());
                return response.content();
            }
        } catch (Exception e) {
            LOG.warn("Failed to summarize {} — truncating to {} chars: {}", purpose, maxChars, e.getMessage());
        }

        // Fallback: hard-truncate rather than returning unbounded content.
        // Callers rely on bounded output for prompt budget and memory safety.
        if (content.length() > maxChars) {
            String marker = "\n\n[TRUNCATED — summarization failed, showing first " + maxChars + " of " + content.length() + " chars]";
            int truncateAt = Math.max(0, maxChars - marker.length());
            return content.substring(0, truncateAt) + marker;
        }
        return content;
    }

    /**
     * Extracts the body text of a Markdown {@code ## SectionName} section.
     * Returns the text between the section header and the next {@code ##} header,
     * stripped of leading/trailing whitespace. Returns an empty string if the
     * section is not found.
     */
    private String parseResponseSection(String content, String sectionName) {
        if (content == null || content.isBlank()) return "";
        String header = "## " + sectionName;
        int start = content.indexOf(header);
        // Skip false prefix matches like "## Concerns" matching "## Concerns and Risks"
        while (start >= 0) {
            int afterHeader = start + header.length();
            if (afterHeader >= content.length() || content.charAt(afterHeader) == '\n'
                    || content.charAt(afterHeader) == '\r') {
                break; // exact match (header followed by newline or end of string)
            }
            start = content.indexOf(header, afterHeader);
        }
        if (start < 0) return "";
        int textStart = content.indexOf('\n', start + header.length());
        if (textStart < 0) return "";
        int nextSection = content.indexOf("## ", textStart);
        String section = nextSection < 0
            ? content.substring(textStart)
            : content.substring(textStart, nextSection);
        return section.strip();
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    /**
     * Stop the orchestration.
     */
    public void stop() {
        LOG.info("Stopping Director orchestration...");
        OrchestratorState prev = state.getAndSet(OrchestratorState.IDLE);
        if (prev == OrchestratorState.PAUSED) {
            // Wake up the paused thread so it can exit
            pauseLock.lock();
            try {
                resumeCondition.signalAll();
            } finally {
                pauseLock.unlock();
            }
        }
    }

    /**
     * Check if orchestration is running.
     */
    public boolean isRunning() {
        OrchestratorState s = state.get();
        return s == OrchestratorState.RUNNING || s == OrchestratorState.PAUSING || s == OrchestratorState.PAUSED;
    }

    /**
     * Request a graceful pause.
     * <p>
     * Sets the state to PAUSING but does <b>not</b> kill running processes.
     * In-progress agents finish their current LLM call naturally; successful
     * results are preserved. The orchestration blocks at the next
     * {@link #checkAndPause()} gate (between phases or between retry attempts)
     * until {@link #resume()} is called.
     * <p>
     * This means pause is not instantaneous — the system continues working
     * until the current agent completes, then pauses. Use {@link #stop()} for
     * immediate termination.
     */
    public void pause() {
        if (state.compareAndSet(OrchestratorState.RUNNING, OrchestratorState.PAUSING)) {
            LOG.info("Pause requested — current agents will finish, then orchestration pauses");
        }
    }

    /**
     * Resume from a paused or pausing state. If the orchestrator has already
     * reached PAUSED, unblocks waiting threads. If still PAUSING (no thread
     * has entered {@code checkAndPause()} yet), transitions directly back to
     * RUNNING — the orchestration continues seamlessly.
     */
    public void resume() {
        pauseLock.lock();
        try {
            OrchestratorState s = state.get();
            if (s == OrchestratorState.PAUSED) {
                state.set(OrchestratorState.RUNNING);
                LOG.info("Resuming orchestration from PAUSED");
                resumeCondition.signalAll();
            } else if (s == OrchestratorState.PAUSING) {
                state.set(OrchestratorState.RUNNING);
                LOG.info("Cancelling pause (orchestration hadn't fully paused yet)");
                // No signal needed — no thread is awaiting in PAUSING state
            }
        } finally {
            pauseLock.unlock();
        }
    }

    /**
     * Check if orchestration is paused or in the process of pausing.
     */
    public boolean isPaused() {
        OrchestratorState s = state.get();
        return s == OrchestratorState.PAUSED || s == OrchestratorState.PAUSING;
    }

    /**
     * Get the current orchestrator state.
     */
    public OrchestratorState getState() {
        return state.get();
    }

    /**
     * Returns true if the Dreamer has self-disabled after determining all goals are met.
     */
    public boolean isDreamerSelfDisabled() {
        return dreamerSelfDisabled;
    }

    /**
     * Hot-apply a new config while paused.
     * Updates the config reference and recreates the LLM client if the backend changed.
     */
    public void applyConfig(AutoIdeatorConfig newConfig) {
        OrchestratorState s = state.get();
        if (s != OrchestratorState.PAUSED && s != OrchestratorState.PAUSING) {
            throw new IllegalStateException("Config can only be hot-applied while paused");
        }
        boolean backendChanged = !newConfig.llm().backend().equals(config.llm().backend());
        boolean modelChanged = !newConfig.llm().model().equals(config.llm().model());

        // Update config — all subsequent reads of this.config see the new value
        this.config = newConfig;

        // Recreate LLM client if backend or model changed
        if (backendChanged || modelChanged) {
            LOG.info("LLM config changed (backend={}, model={}) — recreating LLM client",
                newConfig.llm().backend(), newConfig.llm().model());
            LlmInterface oldLlm = this.llm;
            this.llm = LlmInterface.create(newConfig);
            try { oldLlm.close(); } catch (Exception e) { LOG.warn("Error closing old LLM", e); }
        }

        ideaQueue.rebuildWeights(newConfig.orchestration().ideaQueueWeights());
        LOG.info("Applied config updates while paused");
    }

    /**
     * Get the current cycle count.
     */
    public int getCycleCount() {
        return cycleCount.get();
    }

    /**
     * Get all cycle history results.
     * Used by EventBroadcaster to repopulate event history after a reset.
     * 
     * @return list of all CycleResult records
     */
    public List<CycleResult> getCycleHistory() {
        return new ArrayList<>(cycleHistory);
    }

    // ── Checkpoint support ─────────────────────────────────────────────────────

    /**
     * Set a callback that is invoked after each completed cycle with a
     * snapshot of the orchestration state. The callback should persist
     * the checkpoint (e.g., to disk via {@link com.autoideator.checkpoint.CheckpointManager}).
     *
     * @param saver the checkpoint consumer, or null to disable auto-save
     */
    public void setCheckpointSaver(java.util.function.Consumer<OrchestrationCheckpoint> saver) {
        this.checkpointSaver = saver;
    }

    /**
     * Set a checkpoint to restore when {@link #orchestrate(Idea)} is next called.
     * The checkpoint replaces the normal fresh-state initialization.
     *
     * @param checkpoint the checkpoint to restore from
     */
    public void setCheckpointToRestore(OrchestrationCheckpoint checkpoint) {
        this.pendingCheckpointRestore = checkpoint;
    }

    /**
     * Capture the current orchestration state as an immutable checkpoint.
     * Call this at cycle boundaries or before shutdown.
     *
     * @param idea the current idea being processed
     * @return a snapshot of the orchestration state
     */
    public OrchestrationCheckpoint captureCheckpoint(Idea idea) {
        java.util.List<OrchestrationCheckpoint.CycleResultData> historyData =
            cycleHistory.stream()
                .map(OrchestrationCheckpoint.CycleResultData::from)
                .toList();

        java.util.List<OrchestrationCheckpoint.CycleOutcomeData> outcomeData =
            historyManager.getHistory().stream()
                .map(OrchestrationCheckpoint.CycleOutcomeData::from)
                .toList();

        return new OrchestrationCheckpoint(
            OrchestrationCheckpoint.CURRENT_VERSION,
            java.time.Instant.now(),
            config.workingDir().toAbsolutePath().normalize().toString(),
            idea.description(),
            cycleCount.get(),
            EventBroadcaster.getInstance().getTotalTokens(),
            consecutiveErrors.get(),
            currentPhase != null ? currentPhase.name() : null,
            projectType != null ? projectType.name() : null,
            latestSynthesisInsights,
            overseer.peekSuggestion(),
            ideaQueue.getPosition(),
            ideaQueue.getCurrentWeights(),
            historyData,
            outcomeData,
            dreamerSelfDisabled
        );
    }

    /**
     * Restore internal state from a checkpoint. Called at the start of
     * {@link #orchestrate(Idea)} when a pending checkpoint restore is set.
     *
     * <p>{@code cycleCount} represents the last <em>completed</em> cycle. The next
     * call to {@link #runSingleCycle} will execute cycle {@code cycleCount + 1}
     * from Phase 1. If the previous session exited mid-cycle, that incomplete
     * cycle is safely re-executed rather than skipped.
     */
    private void restoreFromCheckpoint(OrchestrationCheckpoint cp) {
        cycleCount.set(cp.cycleCount());
        consecutiveErrors.set(cp.consecutiveErrors());
        latestSynthesisInsights = cp.latestSynthesisInsights();

        // Restore project phase and type
        if (cp.projectPhase() != null) {
            try {
                this.currentPhase = ProjectPhase.valueOf(cp.projectPhase());
            } catch (IllegalArgumentException e) {
                LOG.warn("Unknown project phase '{}' in checkpoint — using EARLY", cp.projectPhase());
                this.currentPhase = ProjectPhase.EARLY;
            }
        }
        if (cp.projectType() != null) {
            try {
                this.projectType = PhaseDetector.ProjectType.valueOf(cp.projectType());
            } catch (IllegalArgumentException e) {
                LOG.warn("Unknown project type '{}' in checkpoint — using GENERIC", cp.projectType());
                this.projectType = PhaseDetector.ProjectType.GENERIC;
            }
        }

        // Restore Dreamer self-disable flag
        this.dreamerSelfDisabled = cp.dreamerSelfDisabled();
        if (dreamerSelfDisabled) {
            LOG.info("Restored Dreamer self-disabled state (goals met)");
        }

        // Restore IdeaQueue position — only if weights haven't changed
        if (cp.ideaQueueWeights() != null && cp.ideaQueueWeights().equals(ideaQueue.getCurrentWeights())) {
            ideaQueue.setPosition(cp.ideaQueuePosition());
            LOG.info("Restored IdeaQueue position to {}", cp.ideaQueuePosition());
        } else {
            LOG.info("IdeaQueue weights changed since checkpoint — position reset to 0");
        }

        // Restore cycle history (legacy CycleResult deque)
        cycleHistory.clear();
        if (cp.cycleHistory() != null) {
            for (OrchestrationCheckpoint.CycleResultData data : cp.cycleHistory()) {
                cycleHistory.addLast(data.toCycleResult());
            }
        }

        // Restore CycleHistoryManager outcomes
        if (cp.cycleOutcomes() != null) {
            java.util.List<CycleOutcome> outcomes = cp.cycleOutcomes().stream()
                .map(OrchestrationCheckpoint.CycleOutcomeData::toCycleOutcome)
                .toList();
            historyManager.restoreHistory(outcomes);
        }

        // Restore EventBroadcaster counters
        EventBroadcaster broadcaster = EventBroadcaster.getInstance();
        broadcaster.setCycleCount(cp.cycleCount());
        broadcaster.setTotalTokens(cp.totalTokens());

        // Re-populate event history from the restored cycle history so the
        // dashboard shows events from previous cycles.
        EventBroadcaster.getInstance().repopulateEventHistory(this);

        LOG.info("Checkpoint restored: cycle={}, tokens={}, phase={}, type={}",
            cp.cycleCount(), cp.totalTokens(),
            cp.projectPhase(), cp.projectType());
    }

    /**
     * Cleanup resources.
     */
    public void shutdown() {
        stop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warn("Executor did not terminate in 15s, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            llm.close();
        } catch (Exception e) {
            LOG.debug("Error closing LLM interface", e);
        }
    }

    // ── Inner records ───────────────────────────────────────────────────────────

    /**
     * Phase 1 result: generated ideas and the name of the agent that produced them.
     */
    private record IdeaResult(String ideas, String agentName) {}

    /**
     * Phase 4 result: coder execution summary.
     */
    private record CoderPhaseResult(
        List<AgentResponse> responses,
        String coderWork,
        int successCount,
        int totalTasks
    ) {}

    /**
     * Record of a single coder's execution result.
     */
    private record CoderResult(
        String coderName,
        int coderIndex,
        Task task,
        AgentResponse response
    ) {}

    /**
     * Record of a single cycle's results.
     */
    public record CycleResult(
        int cycleNumber,
        String ideaAgentName,
        String ideaContent,
        String skepticCritique,
        String directorPlan,
        int tasksAttempted,
        int tasksCompleted,
        Duration duration
    ) {}
    
    /**
     * Phase 2 result: scored idea with quality metrics.
     */
    private record ScoredIdea(IdeaResult original, IdeaScore score) {}
    
    /**
     * Phase 4 result: Architect's strategic input.
     */
    private record ArchitectInput(
        int alignmentScore,
        Task.TaskPriority priority,
        String dependencies,
        String concerns,
        String recommendation
    ) {}

    /**
     * Phase 7+8 result: review response and whether commit succeeded.
     */
    private record ReviewResult(AgentResponse response, boolean commitSuccessful) {}

    /**
     * A single feature parsed from the Verifier's inventory output.
     *
     * @param number   the feature number (1-based)
     * @param title    short title from "FEATURE N: <title>"
     * @param fullText the complete block including Description and Location lines
     */
    private record FeatureItem(int number, String title, String fullText) {}

    /**
     * Result of verifying a single feature.
     *
     * @param feature    the feature that was checked
     * @param passed     true if the Verifier returned PASS
     * @param output     the raw Verifier output (PASS summary or full FAIL details)
     */
    private record FeatureCheckResult(FeatureItem feature, boolean passed, String output) {
        /**
         * For failed features, returns the output suitable as a Coder task description.
         * Prepends the feature context so the Coder knows what it's fixing.
         */
        String failOutput() {
            return "[CRITICAL] Feature " + feature.number() + ": " + feature.title() + "\n" + output;
        }
    }
}
