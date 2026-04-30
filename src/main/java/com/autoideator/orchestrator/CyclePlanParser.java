package com.autoideator.orchestrator;

import com.autoideator.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for parsing Director plan output into actionable implementation tasks
 * and extracting commit messages from Reviewer output.
 */
final class CyclePlanParser {

    private static final Logger LOG = LoggerFactory.getLogger(CyclePlanParser.class);

    private CyclePlanParser() {}

    /**
     * Parse implementation tasks from Director's plan.
     * Looks specifically for the "Implementation Tasks" section.
     * Returns ALL parsed tasks — the caller is responsible for batching
     * execution to respect {@code maxConcurrentCoders}.
     */
    static List<Task> parseImplementationTasks(String directorPlan, int cycleNumber) {
        if (directorPlan == null || directorPlan.isBlank()) {
            LOG.warn("Director plan is null/blank, creating default task for cycle {}", cycleNumber);
            return List.of(new Task(
                "Implement the Director's plan for cycle " + cycleNumber,
                Task.TaskType.IMPLEMENT,
                Task.TaskPriority.HIGH
            ));
        }

        List<Task> tasks = new ArrayList<>();

        // Find the Implementation Tasks section
        String[] sections = directorPlan.split("(?i)##\\s*implementation\\s*tasks?");
        String taskSection = sections.length > 1 ? sections[1] : directorPlan;

        // Also try looking for "Implementation Plan" section
        if (sections.length <= 1) {
            sections = directorPlan.split("(?i)##\\s*implementation\\s*plan");
            taskSection = sections.length > 1 ? sections[1] : directorPlan;
        }

        // Split into lines and parse
        String[] lines = taskSection.split("\n");
        Task.TaskPriority currentPriority = Task.TaskPriority.MEDIUM;
        boolean inTaskSection = false;

        for (String line : lines) {
            line = line.trim();

            // Stop at next section header once we've already parsed at least one task
            if (line.startsWith("##") && !line.toLowerCase().contains("task")) {
                if (!tasks.isEmpty()) {
                    break; // We've found tasks and hit a new section, stop
                }
            }

            // Skip empty lines
            if (line.isEmpty()) continue;

            // Detect if we're in a task section
            if (line.toLowerCase().contains("implementation task") ||
                (line.toLowerCase().contains("task") && (line.contains(":") || line.matches("^\\d+\\.")))) {
                inTaskSection = true;
            }

            // Detect priority markers — applies to all subsequent tasks until another marker
            if (line.toLowerCase().contains("priority: high") || line.toLowerCase().contains("(high)")) {
                currentPriority = Task.TaskPriority.HIGH;
            } else if (line.toLowerCase().contains("priority: critical")) {
                currentPriority = Task.TaskPriority.CRITICAL;
            } else if (line.toLowerCase().contains("priority: low")) {
                currentPriority = Task.TaskPriority.LOW;
            } else if (line.toLowerCase().contains("priority: medium")) {
                currentPriority = Task.TaskPriority.MEDIUM;
            }

            // Look for task lines (numbered or bulleted)
            if (line.matches("^\\d+\\.\\s+.+") || line.matches("^[-*]\\s+.+")) {
                // Reset priority to MEDIUM for each new task line unless the SAME line
                // contains an inline priority marker (handled above and already set).
                // This prevents priority from one task bleeding into unrelated tasks.
                // Re-detect inline priority on this specific task line:
                String lineLower = line.toLowerCase();
                if (lineLower.contains("priority: high") || lineLower.contains("(high)")) {
                    currentPriority = Task.TaskPriority.HIGH;
                } else if (lineLower.contains("priority: critical")) {
                    currentPriority = Task.TaskPriority.CRITICAL;
                } else if (lineLower.contains("priority: low")) {
                    currentPriority = Task.TaskPriority.LOW;
                } else {
                    currentPriority = Task.TaskPriority.MEDIUM;
                }
                // Detect blocking marker on this task line
                boolean isBlocking = lineLower.contains("(blocking)") || lineLower.contains("[blocking]");

                // Strip numbered prefix (e.g. "1. ") or bullet prefix (e.g. "- " or "* ")
                String taskDesc = line.replaceFirst("^\\d+\\.\\s+", "");
                taskDesc = taskDesc.replaceFirst("^[-*]\\s+", "");

                // Strip Director template prefix like "[Task 1]: " or "[Task N]: "
                taskDesc = taskDesc.replaceFirst("^\\[(?:Task\\s*)?\\d+]:\\s*", "");

                // Strip inline markers from the description text
                taskDesc = taskDesc.replaceAll("(?i)\\(blocking\\)", "").replaceAll("(?i)\\[blocking]", "").trim();

                // Skip non-implementation items
                String taskLower = taskDesc.toLowerCase();
                if (taskLower.contains("success criteria") ||
                    taskLower.contains("risk mitigation") ||
                    taskLower.contains("reasoning") ||
                    taskLower.contains("note:") ||
                    taskLower.contains("description:") ||
                    taskLower.matches("^\\[.*]\\(.*\\).*") || // Skip markdown links [text](url)
                    taskDesc.length() < 15) {     // Skip very short lines
                    continue;
                }

                tasks.add(new Task(
                    taskDesc,
                    Task.TaskType.IMPLEMENT,
                    currentPriority,
                    isBlocking
                ));
            }
        }

        // If no tasks parsed, create a default one
        if (tasks.isEmpty()) {
            LOG.warn("No implementation tasks parsed from Director's plan, creating default task");
            tasks.add(new Task(
                "Implement the Director's plan for cycle " + cycleNumber,
                Task.TaskType.IMPLEMENT,
                Task.TaskPriority.HIGH
            ));
        }

        return tasks;
    }

    /**
     * Extract commit message from review response.
     */
    static String extractCommitMessage(String reviewContent, int cycleNumber) {
        if (reviewContent == null) {
            return "cycle: Completed cycle " + cycleNumber;
        }

        String[] lines = reviewContent.split("\n");
        boolean inCommitSection = false;
        StringBuilder commitMsg = new StringBuilder();

        for (String line : lines) {
            if (line.toLowerCase().contains("commit message")) {
                inCommitSection = true;
                continue;
            }
            if (inCommitSection) {
                if (line.startsWith("```")) {
                    continue;
                }
                if (line.startsWith("##")) {
                    break;
                }
                commitMsg.append(line).append("\n");
            }
        }

        String msg = commitMsg.toString().trim();
        if (msg.isEmpty()) {
            return "cycle: Completed development cycle " + cycleNumber;
        }
        // Truncate to prevent excessively long commit messages
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
