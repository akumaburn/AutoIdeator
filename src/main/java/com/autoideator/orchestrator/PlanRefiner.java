package com.autoideator.orchestrator;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.AgentResponse;
import com.autoideator.model.Idea;
import com.autoideator.model.Plan;
import com.autoideator.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Iteratively refines execution plans through LLM analysis.
 */
public class PlanRefiner {

    private static final Logger LOG = LoggerFactory.getLogger(PlanRefiner.class);

    private static final String REFINEMENT_PROMPT = """
        You are a plan refinement specialist. Analyze the current plan and improve it.

        Consider:
        1. Are all necessary tasks included?
        2. Are task dependencies correctly ordered?
        3. Are priorities appropriate?
        4. Are there missing edge cases or error handling?
        5. Can the plan be made more efficient?
        6. Are acceptance criteria clear?

        Original Idea: %s

        Current Plan (version %d):
        %s

        Provide:
        1. Analysis of current plan (strengths/weaknesses)
        2. Specific improvements to make
        3. Updated task list (if changes needed)
        4. Refinement summary

        Format your response as:
        ## Analysis
        [Your analysis]

        ## Improvements
        [List of improvements]

        ## Updated Tasks
        [List of tasks, one per line, format: - [PRIORITY] Task description]

        ## Summary
        [Brief summary of changes]
        """;

    private final AutoIdeatorConfig config;
    private final LlmInterface llm;

    public PlanRefiner(AutoIdeatorConfig config, LlmInterface llm) {
        this.config = config;
        this.llm = llm;
    }

    /**
     * Refine a plan through LLM analysis.
     */
    public CompletableFuture<RefinementResult> refine(Plan plan, Idea idea) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String planDescription = formatPlanForRefinement(plan);
                String prompt = String.format(REFINEMENT_PROMPT,
                    idea.description(),
                    plan.version(),
                    planDescription
                );

                String systemPrompt = """
                    You are an expert at refining execution plans.
                    Your goal is to make plans more complete, efficient, and actionable.
                    Focus on practical improvements that add real value.
                    """;

                AgentResponse response = llm.sendPrompt(systemPrompt, prompt).join();

                if (!response.success()) {
                    LOG.warn("Refinement failed: {}", response.error());
                    return new RefinementResult(plan, "No changes - refinement failed", false);
                }

                // Parse the refined plan from the response
                Plan refinedPlan = parseRefinedPlan(response.content(), plan);

                String summary = extractSummary(response.content());
                boolean hasChanges = refinedPlan.version() > plan.version();

                LOG.debug("Refinement complete: {} changes", hasChanges ? "with" : "without");
                return new RefinementResult(refinedPlan, summary, hasChanges);

            } catch (Exception e) {
                LOG.error("Refinement error", e);
                return new RefinementResult(plan, "No changes - error: " + e.getMessage(), false);
            }
        });
    }

    private String formatPlanForRefinement(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Description: ").append(plan.description()).append("\n");
        sb.append("Status: ").append(plan.status()).append("\n");
        sb.append("Progress: ").append(String.format("%.1f%%", plan.getProgress())).append("\n\n");
        sb.append("Tasks:\n");

        for (int i = 0; i < plan.tasks().size(); i++) {
            Task task = plan.tasks().get(i);
            sb.append(String.format("%d. [%s] [%s] %s%n",
                i + 1,
                task.priority(),
                task.type(),
                task.description()
            ));
            if (!task.dependencies().isEmpty()) {
                sb.append("   Dependencies: ").append(task.dependencies()).append("\n");
            }
        }

        return sb.toString();
    }

    private Plan parseRefinedPlan(String response, Plan currentPlan) {
        List<Task> newTasks = new ArrayList<>();
        boolean hasChanges = false;

        String[] sections = response.split("##");
        for (String section : sections) {
            if (section.trim().startsWith("Updated Tasks")) {
                String[] lines = section.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("-")) {
                        Task task = parseTaskLine(line);
                        if (task != null) {
                            newTasks.add(task);
                        }
                    }
                }
            }
        }

        // If we parsed new tasks, update the plan
        if (!newTasks.isEmpty()) {
            // Check if tasks are actually different
            if (tasksAreDifferent(currentPlan.tasks(), newTasks)) {
                hasChanges = true;
                return currentPlan.withTasks(newTasks);
            }
        }

        return currentPlan;
    }

    private Task parseTaskLine(String line) {
        // Parse format: - [PRIORITY] Task description
        line = line.replaceFirst("^-\\s*", "");

        Task.TaskPriority priority = Task.TaskPriority.MEDIUM;
        Task.TaskType type = Task.TaskType.IMPLEMENT;

        // Extract priority
        if (line.contains("[CRITICAL]")) {
            priority = Task.TaskPriority.CRITICAL;
            line = line.replace("[CRITICAL]", "").trim();
        } else if (line.contains("[HIGH]")) {
            priority = Task.TaskPriority.HIGH;
            line = line.replace("[HIGH]", "").trim();
        } else if (line.contains("[LOW]")) {
            priority = Task.TaskPriority.LOW;
            line = line.replace("[LOW]", "").trim();
        } else if (line.contains("[MEDIUM]")) {
            line = line.replace("[MEDIUM]", "").trim();
        }

        // Infer task type from description
        String upperLine = line.toUpperCase();
        if (upperLine.contains("TEST")) {
            type = Task.TaskType.TEST;
        } else if (upperLine.contains("REVIEW")) {
            type = Task.TaskType.REVIEW;
        } else if (upperLine.contains("DOCUMENT")) {
            type = Task.TaskType.DOCUMENT;
        } else if (upperLine.contains("DESIGN") || upperLine.contains("ANALYZE")) {
            type = Task.TaskType.DESIGN;
        } else if (upperLine.contains("GIT") || upperLine.contains("COMMIT")) {
            type = Task.TaskType.GIT;
        }

        if (line.length() > 5) {
            return new Task(line, type, priority);
        }
        return null;
    }

    private boolean tasksAreDifferent(List<Task> oldTasks, List<Task> newTasks) {
        if (oldTasks.size() != newTasks.size()) {
            return true;
        }

        for (int i = 0; i < oldTasks.size(); i++) {
            Task oldTask = oldTasks.get(i);
            Task newTask = newTasks.get(i);

            if (!oldTask.description().equals(newTask.description()) ||
                oldTask.type() != newTask.type() ||
                oldTask.priority() != newTask.priority()) {
                return true;
            }
        }

        return false;
    }

    private String extractSummary(String response) {
        String[] sections = response.split("##");
        for (String section : sections) {
            if (section.trim().startsWith("Summary")) {
                return section.replaceFirst("Summary\\s*", "").trim();
            }
        }
        return "Refinement completed";
    }

    /**
     * Result of a plan refinement.
     */
    public record RefinementResult(
        Plan plan,
        String summary,
        boolean hasChanges
    ) {}
}
