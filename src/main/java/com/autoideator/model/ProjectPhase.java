package com.autoideator.model;

/**
 * Represents the current phase of project development.
 * Used to adjust agent behavior and weights based on project maturity.
 */
public enum ProjectPhase {
    /**
     * Project is just starting - basic structure, few commits.
     * Focus on: building core features, setting up architecture.
     */
    BOOTSTRAP(0, 5, "Bootstrap"),
    
    /**
     * Core features being implemented, growing codebase.
     * Focus on: feature development, expanding functionality.
     */
    EARLY(5, 20, "Early Development"),
    
    /**
     * Significant functionality exists, expanding and refining.
     * Focus on: feature completion, optimization, quality.
     */
    GROWTH(20, 50, "Growth"),
    
    /**
     * Mature codebase with extensive features.
     * Focus on: optimization, correctness, refinement, maintenance.
     */
    MATURE(50, Integer.MAX_VALUE, "Mature");
    
    private final int minCommits;
    private final int maxCommits;
    private final String displayName;
    
    ProjectPhase(int minCommits, int maxCommits, String displayName) {
        this.minCommits = minCommits;
        this.maxCommits = maxCommits;
        this.displayName = displayName;
    }
    
    /**
     * Determine project phase based on commit count.
     */
    public static ProjectPhase fromCommitCount(int commitCount) {
        for (ProjectPhase phase : values()) {
            if (commitCount >= phase.minCommits && commitCount < phase.maxCommits) {
                return phase;
            }
        }
        return MATURE;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public int getMinCommits() {
        return minCommits;
    }
    
    public int getMaxCommits() {
        return maxCommits;
    }
}
