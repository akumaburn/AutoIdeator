package com.autoideator.model;

/**
 * Represents a scored evaluation of an idea before it proceeds to implementation.
 *
 * <p>The Scorer never kills a cycle outright. If ideas score below threshold,
 * the Scorer reshapes them into goal-aligned alternatives and {@link #reshapedIdeas}
 * contains the replacement text in numbered-ideas format.
 */
public record IdeaScore(
    int goalAlignment,
    int novelty,
    int feasibility,
    int overallScore,
    String reasoning,
    boolean belowThreshold,
    String reshapedIdeas
) {
    /**
     * Minimum threshold for goal alignment (0-10 scale).
     */
    public static final int MIN_GOAL_ALIGNMENT = 6;

    /**
     * Minimum overall score to proceed (0-10 scale).
     */
    public static final int MIN_OVERALL_SCORE = 5;

    public IdeaScore {
        if (goalAlignment < 0 || goalAlignment > 10) {
            throw new IllegalArgumentException("goalAlignment must be 0-10");
        }
        if (novelty < 0 || novelty > 10) {
            throw new IllegalArgumentException("novelty must be 0-10");
        }
        if (feasibility < 0 || feasibility > 10) {
            throw new IllegalArgumentException("feasibility must be 0-10");
        }
        if (overallScore < 0 || overallScore > 10) {
            throw new IllegalArgumentException("overallScore must be 0-10");
        }
    }

    /**
     * Whether the Scorer reshaped the ideas because the originals scored below threshold.
     */
    public boolean wasReshaped() {
        return reshapedIdeas != null && !reshapedIdeas.isBlank();
    }

    /**
     * Create a score with automatic threshold determination.
     */
    public static IdeaScore of(int goalAlignment, int novelty, int feasibility, String reasoning) {
        int overall = Math.round((goalAlignment * 5 + feasibility * 3 + novelty * 2) / 10.0f);
        boolean belowThreshold = goalAlignment < MIN_GOAL_ALIGNMENT || overall < MIN_OVERALL_SCORE;
        return new IdeaScore(goalAlignment, novelty, feasibility, overall, reasoning, belowThreshold, null);
    }

    /**
     * Create a low-score result (e.g. when parsing fails). The cycle still continues.
     */
    public static IdeaScore lowScore(String reason) {
        return new IdeaScore(0, 0, 0, 0, reason, true, null);
    }
}
