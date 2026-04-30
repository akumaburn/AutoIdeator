package com.autoideator.checkpoint;

import com.autoideator.config.AutoIdeatorConfig.IdeaQueueWeights;
import com.autoideator.model.CycleOutcome;
import com.autoideator.orchestrator.DirectorOrchestrator.CycleResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Immutable snapshot of the orchestration state at a cycle boundary.
 *
 * <p>Saved after each completed cycle so that a program exit is effectively a
 * pause — the next start can restore this checkpoint and resume from where
 * it left off. An explicit "Stop" clears the checkpoint for the directory.
 *
 * <p>Serialized to JSON via Jackson. All nested types are records with
 * primitive/String/Duration/Instant fields, which Jackson handles natively
 * with the JavaTimeModule.
 */
public record OrchestrationCheckpoint(
    int version,
    Instant timestamp,
    String workingDirectory,
    String ideaDescription,
    int cycleCount,
    long totalTokens,
    int consecutiveErrors,
    String projectPhase,
    String projectType,
    String latestSynthesisInsights,
    String pendingOverseerSuggestion,
    int ideaQueuePosition,
    IdeaQueueWeights ideaQueueWeights,
    List<CycleResultData> cycleHistory,
    List<CycleOutcomeData> cycleOutcomes,
    boolean dreamerSelfDisabled
) {
    /** Current checkpoint format version. Increment on breaking schema changes. */
    public static final int CURRENT_VERSION = 1;

    /**
     * Flat representation of {@link CycleResult} for serialization.
     * Duration is stored as millis to avoid format ambiguity.
     */
    public record CycleResultData(
        int cycleNumber,
        String ideaAgentName,
        String ideaContent,
        String skepticCritique,
        String directorPlan,
        int tasksAttempted,
        int tasksCompleted,
        long durationMillis
    ) {
        public static CycleResultData from(CycleResult r) {
            return new CycleResultData(
                r.cycleNumber(), r.ideaAgentName(), r.ideaContent(),
                r.skepticCritique(), r.directorPlan(),
                r.tasksAttempted(), r.tasksCompleted(),
                r.duration().toMillis()
            );
        }

        public CycleResult toCycleResult() {
            return new CycleResult(
                cycleNumber, ideaAgentName, ideaContent,
                skepticCritique, directorPlan,
                tasksAttempted, tasksCompleted,
                Duration.ofMillis(durationMillis)
            );
        }
    }

    /**
     * Flat representation of {@link CycleOutcome} for serialization.
     */
    public record CycleOutcomeData(
        int cycleNumber,
        String ideaAgentName,
        String ideaSummary,
        IdeaScoreData ideaScore,
        int tasksPlanned,
        int tasksCompleted,
        int tasksSuccessful,
        int reviewIssuesFound,
        int reviewIssuesFixed,
        boolean commitSuccessful,
        long durationMillis,
        String failureReason
    ) {
        public static CycleOutcomeData from(CycleOutcome o) {
            return new CycleOutcomeData(
                o.cycleNumber(), o.ideaAgentName(), o.ideaSummary(),
                IdeaScoreData.from(o.ideaScore()),
                o.tasksPlanned(), o.tasksCompleted(), o.tasksSuccessful(),
                o.reviewIssuesFound(), o.reviewIssuesFixed(),
                o.commitSuccessful(),
                o.cycleDuration() != null ? o.cycleDuration().toMillis() : 0,
                o.failureReason()
            );
        }

        public CycleOutcome toCycleOutcome() {
            return new CycleOutcome(
                cycleNumber, ideaAgentName, ideaSummary,
                ideaScore.toIdeaScore(),
                tasksPlanned, tasksCompleted, tasksSuccessful,
                reviewIssuesFound, reviewIssuesFixed,
                commitSuccessful,
                Duration.ofMillis(durationMillis),
                failureReason
            );
        }
    }

    /**
     * Flat representation of {@link com.autoideator.model.IdeaScore} for serialization.
     */
    public record IdeaScoreData(
        int goalAlignment,
        int novelty,
        int feasibility,
        int overallScore,
        String reasoning,
        boolean belowThreshold,
        String reshapedIdeas
    ) {
        public static IdeaScoreData from(com.autoideator.model.IdeaScore s) {
            if (s == null) {
                return new IdeaScoreData(0, 0, 0, 0, null, true, null);
            }
            return new IdeaScoreData(
                s.goalAlignment(), s.novelty(), s.feasibility(),
                s.overallScore(), s.reasoning(),
                s.belowThreshold(), s.reshapedIdeas()
            );
        }

        public com.autoideator.model.IdeaScore toIdeaScore() {
            // Clamp values to valid range to handle checkpoints from older versions
            // or manually edited files.
            return new com.autoideator.model.IdeaScore(
                Math.max(0, Math.min(10, goalAlignment)),
                Math.max(0, Math.min(10, novelty)),
                Math.max(0, Math.min(10, feasibility)),
                Math.max(0, Math.min(10, overallScore)),
                reasoning, belowThreshold, reshapedIdeas
            );
        }
    }
}
