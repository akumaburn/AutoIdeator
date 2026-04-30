package com.autoideator.model;

import java.time.Duration;

/**
 * Represents the outcome of a completed cycle with quality metrics.
 * Used for learning and improving future cycles.
 */
public record CycleOutcome(
    int cycleNumber,
    String ideaAgentName,
    String ideaSummary,
    IdeaScore ideaScore,
    int tasksPlanned,
    int tasksCompleted,
    int tasksSuccessful,
    int reviewIssuesFound,
    int reviewIssuesFixed,
    boolean commitSuccessful,
    Duration cycleDuration,
    String failureReason
) {
    /**
     * Calculate the implementation success rate (0.0 to 1.0).
     */
    public double implementationSuccessRate() {
        if (tasksCompleted == 0) return 0.0;
        return (double) tasksSuccessful / tasksCompleted;
    }
    
    /**
     * Calculate the idea quality score (0-10).
     * Based on implementation success and review quality.
     */
    public int calculateIdeaQualityScore() {
        if (!commitSuccessful) return 1;
        
        double implScore = implementationSuccessRate() * 5;
        double reviewScore = Math.max(0, 5 - (reviewIssuesFound * 0.5));
        
        return (int) Math.round(implScore + reviewScore);
    }
    
    /**
     * Check if this was a high-quality cycle.
     */
    public boolean isHighQuality() {
        return commitSuccessful && 
               implementationSuccessRate() >= 0.8 && 
               reviewIssuesFound <= 2;
    }
    
    /**
     * Create a failed cycle outcome.
     */
    public static CycleOutcome failed(int cycleNumber, String ideaAgentName, String ideaSummary, String failureReason) {
        return new CycleOutcome(
            cycleNumber, ideaAgentName, ideaSummary,
            IdeaScore.lowScore(failureReason),
            0, 0, 0, 0, 0, false,
            Duration.ZERO, failureReason
        );
    }
}
