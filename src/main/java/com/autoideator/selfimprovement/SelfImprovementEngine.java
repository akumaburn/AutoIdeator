package com.autoideator.selfimprovement;

import com.autoideator.agent.Agent;
import com.autoideator.agent.AgentSwarm;
import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.git.GitOperations;
import com.autoideator.llm.LlmInterface;
import com.autoideator.model.AgentResponse;
import com.autoideator.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.Set;

/**
 * Engine that continuously finds and implements improvements to the project.
 */
public class SelfImprovementEngine {

    private static final Logger LOG = LoggerFactory.getLogger(SelfImprovementEngine.class);

    private static final String IMPROVEMENT_PROMPT = """
        Analyze this project and identify potential improvements.

        Project Context:
        %s

        Recent Changes:
        %s

        Look for improvements in:
        1. Code quality and maintainability
        2. Performance optimizations
        3. Security enhancements
        4. Documentation improvements
        5. Test coverage
        6. Error handling
        7. Design pattern applications
        8. Dependency updates
        9. Configuration optimizations
        10. User experience improvements

        For each improvement, provide:
        - Category (quality, performance, security, docs, tests, etc.)
        - Priority (CRITICAL, HIGH, MEDIUM, LOW)
        - Description of the improvement
        - Files affected
        - Estimated effort (small, medium, large)

        Format your response as:
        ## Improvements Found
        1. [CATEGORY] [PRIORITY] Description
           - Files: file1.java, file2.java
           - Effort: small/medium/large
           - Rationale: why this improvement matters
        """;

    private final AutoIdeatorConfig config;
    private final LlmInterface llm;
    private final AgentSwarm agentSwarm;
    private final EnhancementFinder enhancementFinder;

    public SelfImprovementEngine(AutoIdeatorConfig config, LlmInterface llm, AgentSwarm agentSwarm) {
        this.config = config;
        this.llm = llm;
        this.agentSwarm = agentSwarm;
        this.enhancementFinder = new EnhancementFinder(config);
    }

    /**
     * Find and implement improvements in the project.
     */
    public CompletableFuture<ImprovementResult> findAndImplementImprovements(Path projectPath) {
        return CompletableFuture.supplyAsync(() -> {
            LOG.info("Scanning for improvements in: {}", projectPath);

            try {
                // Gather project context
                String projectContext = gatherProjectContext(projectPath);

                // Get recent changes from Git
                String recentChanges = getRecentChanges(projectPath);

                // Ask LLM for improvements
                String prompt = String.format(IMPROVEMENT_PROMPT, projectContext, recentChanges);

                AgentResponse response = llm.sendPrompt(
                    "You are a code improvement specialist. Identify actionable improvements.",
                    prompt
                ).join();

                if (!response.success() || response.content() == null || response.content().isBlank()) {
                    LOG.warn("Failed to get improvement suggestions: {}", response.error());
                    return new ImprovementResult(List.of(), "Failed to get suggestions");
                }

                // Parse improvements from response
                List<Improvement> improvements = parseImprovements(response.content());

                // Limit to max improvements per cycle
                improvements = improvements.stream()
                    .limit(config.selfImprovement().maxImprovementsPerCycle())
                    .toList();

                LOG.info("Found {} potential improvements", improvements.size());

                // Implement each improvement
                List<Improvement> implemented = new ArrayList<>();
                for (Improvement improvement : improvements) {
                    if (implementImprovement(improvement, projectPath)) {
                        implemented.add(improvement);
                    }
                }

                String summary = String.format("Implemented %d/%d improvements",
                    implemented.size(), improvements.size());

                return new ImprovementResult(implemented, summary);

            } catch (Exception e) {
                LOG.error("Error during improvement scan", e);
                return new ImprovementResult(List.of(), "Error: " + e.getMessage());
            }
        });
    }

    private String gatherProjectContext(Path projectPath) throws IOException {
        StringBuilder context = new StringBuilder();

        // Get project structure
        context.append("## Project Structure\n");
        context.append(getProjectStructure(projectPath)).append("\n\n");

        // Get key files content (README, config files, etc.)
        context.append("## Key Files\n");
        context.append(readKeyFiles(projectPath)).append("\n\n");

        // Get code statistics
        context.append("## Code Statistics\n");
        context.append(getCodeStatistics(projectPath)).append("\n");

        return context.toString();
    }

    private String getProjectStructure(Path projectPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
            private int depth = 0;
            private static final int MAX_DEPTH = 4;

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (name.startsWith(".") || name.equals("build") || name.equals("node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                if (depth < MAX_DEPTH) {
                    sb.append("  ".repeat(depth)).append("├── ").append(name).append("/\n");
                    depth++;
                    return FileVisitResult.CONTINUE;
                }
                return FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                depth--;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (depth < MAX_DEPTH) {
                    sb.append("  ".repeat(depth)).append("├── ").append(file.getFileName()).append("\n");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sb.toString();
    }

    private String readKeyFiles(Path projectPath) {
        StringBuilder sb = new StringBuilder();
        String[] keyFiles = {"README.md", "build.gradle.kts", "pom.xml", "package.json"};

        for (String fileName : keyFiles) {
            Path file = projectPath.resolve(fileName);
            if (Files.exists(file)) {
                try {
                    long fileSize = Files.size(file);
                    if (fileSize > 10_000_000) {
                        LOG.debug("Skipping large key file: {} ({} bytes)", fileName, fileSize);
                        continue;
                    }
                    String content = Files.readString(file);
                    if (content.length() > 500) {
                        content = content.substring(0, 500) + "...";
                    }
                    sb.append("### ").append(fileName).append("\n```\n")
                        .append(content).append("\n```\n\n");
                } catch (IOException e) {
                    LOG.debug("Failed to read key file: {}", fileName);
                }
            }
        }

        return sb.toString();
    }

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
        ".java", ".kt", ".ts", ".js", ".tsx", ".jsx", ".py", ".go", ".rs",
        ".c", ".cpp", ".h", ".hpp", ".cs", ".rb", ".swift", ".scala",
        ".html", ".css", ".scss", ".xml", ".json", ".yaml", ".yml",
        ".toml", ".conf", ".properties", ".md", ".txt", ".sql", ".sh"
    );

    private String getCodeStatistics(Path projectPath) throws IOException {
        Map<String, Integer> fileCounts = new HashMap<>();
        Map<String, Integer> lineCounts = new HashMap<>();

        Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (name.startsWith(".") || name.equals("build") || name.equals("node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf(".")) : "other";

                // Skip hidden files
                if (name.startsWith(".")) {
                    return FileVisitResult.CONTINUE;
                }

                fileCounts.merge(ext, 1, Integer::sum);

                // Only count lines for known text/source files to avoid reading binary files
                if (SOURCE_EXTENSIONS.contains(ext)) {
                    try {
                        // Skip files > 10MB to prevent OOM on large generated files
                        if (attrs.size() > 10_000_000) {
                            return FileVisitResult.CONTINUE;
                        }
                        int lines = Files.readAllLines(file).size();
                        lineCounts.merge(ext, lines, Integer::sum);
                    } catch (IOException e) {
                        // Ignore files that can't be read as text
                    }
                }

                return FileVisitResult.CONTINUE;
            }
        });

        StringBuilder sb = new StringBuilder();
        sb.append("File counts by extension:\n");
        fileCounts.forEach((ext, count) -> sb.append("  ").append(ext).append(": ").append(count).append(" files\n"));

        sb.append("\nLine counts by extension:\n");
        lineCounts.forEach((ext, count) -> sb.append("  ").append(ext).append(": ").append(count).append(" lines\n"));

        return sb.toString();
    }

    private String getRecentChanges(Path projectPath) {
        try {
            GitOperations git = new GitOperations(config, projectPath);
            if (!git.isGitRepository()) {
                return "No Git repository found";
            }

            List<GitOperations.CommitInfo> commits = git.getLog(10);
            return commits.stream()
                .map(c -> "- " + c.message() + " (" + c.timestamp() + ")")
                .collect(Collectors.joining("\n"));

        } catch (Exception e) {
            return "Unable to get recent changes: " + e.getMessage();
        }
    }

    private List<Improvement> parseImprovements(String response) {
        List<Improvement> improvements = new ArrayList<>();

        String[] lines = response.split("\n");
        for (String line : lines) {
            line = line.trim();

            // Look for improvement lines: 1. [CATEGORY] [PRIORITY] Description
            if (line.matches("^\\d+\\.\\s+\\[\\w+\\]\\s+\\[\\w+\\].+")) {
                improvements.add(parseImprovementLine(line));
            }
        }

        // Sort by priority
        improvements.sort(Comparator.comparingInt(i -> i.priority().getLevel()));

        return improvements;
    }

    private Improvement parseImprovementLine(String line) {
        // Parse: 1. [CATEGORY] [PRIORITY] Description
        line = line.replaceFirst("^\\d+\\.\\s+", "");

        String category = "general";
        Task.TaskPriority priority = Task.TaskPriority.MEDIUM;

        // Extract category
        int catStart = line.indexOf('[');
        int catEnd = line.indexOf(']');
        if (catStart >= 0 && catEnd > catStart) {
            category = line.substring(catStart + 1, catEnd).toLowerCase();
            line = line.substring(catEnd + 1).trim();
        }

        // Extract priority
        int priStart = line.indexOf('[');
        int priEnd = line.indexOf(']');
        if (priStart >= 0 && priEnd > priStart) {
            try {
                priority = Task.TaskPriority.valueOf(line.substring(priStart + 1, priEnd).toUpperCase());
            } catch (IllegalArgumentException e) {
                // Keep default
            }
            line = line.substring(priEnd + 1).trim();
        }

        return new Improvement(
            UUID.randomUUID().toString(),
            category,
            priority,
            line,
            List.of(),
            "medium",
            Instant.now()
        );
    }

    private boolean implementImprovement(Improvement improvement, Path projectPath) {
        LOG.info("Implementing improvement: {}", improvement.description());

        Task task = new Task(
            "Implement improvement: " + improvement.description(),
            Task.TaskType.IMPLEMENT,
            improvement.priority()
        );

        Agent.ExecutionContext context = Agent.ExecutionContext.create(config, llm)
            .withProjectContext("Project path: " + projectPath);

        AgentResponse response = agentSwarm.executeTask(task, context).join();

        if (response.success()) {
            LOG.info("Successfully implemented: {}", improvement.description());
            return true;
        } else {
            LOG.warn("Failed to implement: {} - {}", improvement.description(), response.error());
            return false;
        }
    }

    /**
     * Represents a potential improvement.
     */
    public record Improvement(
        String id,
        String category,
        Task.TaskPriority priority,
        String description,
        List<String> affectedFiles,
        String effort,
        Instant identified
    ) {}

    /**
     * Result of improvement implementation.
     */
    public record ImprovementResult(
        List<Improvement> improvements,
        String summary
    ) {}
}
