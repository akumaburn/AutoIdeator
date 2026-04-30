package com.autoideator.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Manages cycle history for learning and deduplication.
 * Tracks outcomes, successful patterns, and helps avoid repetition.
 */
public class CycleHistoryManager {
    
    private static final Logger LOG = LoggerFactory.getLogger(CycleHistoryManager.class);
    
    private static final int MAX_HISTORY_SIZE = 100;
    private static final int DEDUP_LOOKBACK_CYCLES = 10;
    private static final double SIMILARITY_THRESHOLD = 0.7;
    private static final Set<String> COMMON_WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "the", "and", "for", "are", "but", "not", "you", "all", "can", "her", "was", "one",
        "our", "out", "has", "have", "been", "will", "would", "could", "should", "this", "that",
        "with", "from", "they", "what", "when", "where", "which", "while"
    )));
    
    private final Deque<CycleOutcome> history;
    private final Map<String, Integer> ideaKeywords;
    private final Map<String, Integer> agentSuccessCount;
    private final Map<String, Integer> agentTotalCount;
    
    public CycleHistoryManager() {
        this.history = new ConcurrentLinkedDeque<>();
        this.ideaKeywords = new java.util.concurrent.ConcurrentHashMap<>();
        this.agentSuccessCount = new java.util.concurrent.ConcurrentHashMap<>();
        this.agentTotalCount = new java.util.concurrent.ConcurrentHashMap<>();
    }
    
    /**
     * Add a completed cycle to history.
     * Synchronized to prevent concurrent eviction corrupting the keyword map.
     */
    public synchronized void addCycle(CycleOutcome outcome) {
        while (history.size() >= MAX_HISTORY_SIZE) {
            CycleOutcome removed = history.removeFirst();
            removeKeywordTracking(removed.ideaSummary());
        }

        history.addLast(outcome);
        updateKeywordTracking(outcome.ideaSummary());
        updateAgentStats(outcome);

        LOG.debug("Recorded cycle {} outcome: quality={}",
            outcome.cycleNumber(), outcome.calculateIdeaQualityScore());
    }
    
    /**
     * Check if a similar idea was recently attempted.
     * Returns similarity score (0.0 to 1.0) and the similar cycle if found.
     */
    public synchronized SimilarityCheck checkSimilarity(String newIdea) {
        if (history.isEmpty()) {
            return new SimilarityCheck(0.0, null);
        }
        
        Set<String> newKeywords = extractKeywords(newIdea);
        if (newKeywords.isEmpty()) {
            return new SimilarityCheck(0.0, null);
        }
        
        // Snapshot the deque to avoid TOCTOU race between size() and stream()
        List<CycleOutcome> snapshot = new ArrayList<>(history);
        List<CycleOutcome> recentCycles = snapshot.subList(
            Math.max(0, snapshot.size() - DEDUP_LOOKBACK_CYCLES), snapshot.size());
        
        double maxSimilarity = 0.0;
        CycleOutcome mostSimilar = null;
        
        for (CycleOutcome past : recentCycles) {
            Set<String> pastKeywords = extractKeywords(past.ideaSummary());
            double similarity = calculateJaccardSimilarity(newKeywords, pastKeywords);
            
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
                mostSimilar = past;
            }
        }
        
        return new SimilarityCheck(maxSimilarity, mostSimilar);
    }
    
    /**
     * Get recent history summary for context.
     */
    public synchronized String getRecentHistorySummary(int lastN) {
        if (history.isEmpty() || lastN <= 0) {
            return "No previous cycles completed.";
        }
        
        List<CycleOutcome> snapshot = new ArrayList<>(history);
        StringBuilder sb = new StringBuilder();
        sb.append("Last ").append(Math.min(lastN, snapshot.size())).append(" cycles:\n\n");
        List<CycleOutcome> recent = snapshot.subList(
            Math.max(0, snapshot.size() - lastN), snapshot.size());
        
        for (CycleOutcome cycle : recent) {
            sb.append("Cycle ").append(cycle.cycleNumber()).append(":\n");
            sb.append("  Agent: ").append(cycle.ideaAgentName()).append("\n");
            sb.append("  Idea: ").append(truncate(cycle.ideaSummary(), 100)).append("\n");
            sb.append("  Success: ").append(cycle.tasksSuccessful()).append("/")
              .append(cycle.tasksCompleted()).append(" tasks\n");
            sb.append("  Quality Score: ").append(cycle.calculateIdeaQualityScore()).append("/10\n");
            sb.append("  Committed: ").append(cycle.commitSuccessful() ? "Yes" : "No").append("\n\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Get agent success rates.
     */
    public synchronized Map<String, Double> getAgentSuccessRates() {
        Map<String, Double> rates = new HashMap<>();
        
        for (Map.Entry<String, Integer> entry : agentTotalCount.entrySet()) {
            int total = entry.getValue();
            int success = agentSuccessCount.getOrDefault(entry.getKey(), 0);
            rates.put(entry.getKey(), total > 0 ? (double) success / total : 0.0);
        }
        
        return rates;
    }
    
    /**
     * Get frequently used keywords in recent ideas.
     */
    public synchronized List<String> getFrequentKeywords(int minOccurrences) {
        return ideaKeywords.entrySet().stream()
            .filter(e -> e.getValue() >= minOccurrences)
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .limit(20)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
    
    /**
     * Get high-quality cycle patterns for learning.
     */
    public synchronized List<CycleOutcome> getHighQualityCycles() {
        return new ArrayList<>(history).stream()
            .filter(CycleOutcome::isHighQuality)
            .collect(Collectors.toList());
    }
    
    /**
     * Get total cycle count.
     */
    public synchronized int size() {
        return history.size();
    }

    /**
     * Check if history is empty.
     */
    public synchronized boolean isEmpty() {
        return history.isEmpty();
    }

    /**
     * Returns a snapshot of all cycle outcomes for checkpointing.
     * The returned list is a defensive copy in chronological order.
     */
    public synchronized List<CycleOutcome> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Restore history from a list of cycle outcomes (e.g., from a checkpoint).
     * Clears any existing history and replays the outcomes to rebuild
     * the derived keyword and agent stats maps.
     *
     * @param outcomes the cycle outcomes to restore, in chronological order
     */
    public synchronized void restoreHistory(List<CycleOutcome> outcomes) {
        history.clear();
        ideaKeywords.clear();
        agentSuccessCount.clear();
        agentTotalCount.clear();

        // Only restore the last MAX_HISTORY_SIZE outcomes to respect the bound
        int startIdx = Math.max(0, outcomes.size() - MAX_HISTORY_SIZE);
        List<CycleOutcome> bounded = outcomes.subList(startIdx, outcomes.size());

        for (CycleOutcome outcome : bounded) {
            history.addLast(outcome);
            updateKeywordTracking(outcome.ideaSummary());
            updateAgentStats(outcome);
        }

        LOG.info("Restored {} cycle outcomes from checkpoint", bounded.size());
    }
    
    // Private helper methods
    
    private Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }
        
        String[] words = text.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", " ")
            .split("\\s+");
        
        return Arrays.stream(words)
            .filter(w -> w.length() >= 3)
            .filter(w -> !isCommonWord(w))
            .collect(Collectors.toSet());
    }
    
    private boolean isCommonWord(String word) {
        return COMMON_WORDS.contains(word);
    }
    
    private double calculateJaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() || set2.isEmpty()) {
            return 0.0;
        }
        
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        
        return (double) intersection.size() / union.size();
    }
    
    private void updateKeywordTracking(String ideaSummary) {
        Set<String> keywords = extractKeywords(ideaSummary);
        for (String keyword : keywords) {
            ideaKeywords.merge(keyword, 1, Integer::sum);
        }
    }
    
    private void removeKeywordTracking(String ideaSummary) {
        Set<String> keywords = extractKeywords(ideaSummary);
        for (String keyword : keywords) {
            ideaKeywords.computeIfPresent(keyword, (k, v) -> v > 1 ? v - 1 : null);
        }
    }
    
    private void updateAgentStats(CycleOutcome outcome) {
        String agent = outcome.ideaAgentName();
        agentTotalCount.merge(agent, 1, Integer::sum);
        
        if (outcome.isHighQuality()) {
            agentSuccessCount.merge(agent, 1, Integer::sum);
        }
    }
    
    private String truncate(String s, int max) {
        if (s == null || max <= 0) return "";
        if (max <= 3) return s.substring(0, Math.min(s.length(), max));
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /**
     * Result of checking idea similarity.
     */
    public record SimilarityCheck(double similarity, CycleOutcome similarCycle) {
        public boolean isDuplicate() {
            return similarity >= SIMILARITY_THRESHOLD;
        }

        public String getWarning() {
            if (!isDuplicate() || similarCycle == null) {
                return null;
            }
            return String.format("Similar to cycle %d (%.0f%% similar): %s",
                similarCycle.cycleNumber(),
                similarity * 100,
                truncate(similarCycle.ideaSummary(), 80));
        }

        private String truncate(String s, int max) {
            if (s == null || max <= 0) return "";
            if (max <= 3) return s.substring(0, Math.min(s.length(), max));
            return s.length() <= max ? s : s.substring(0, max - 3) + "...";
        }
    }
}
