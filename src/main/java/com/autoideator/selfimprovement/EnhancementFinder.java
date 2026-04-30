package com.autoideator.selfimprovement;

import com.autoideator.config.AutoIdeatorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds specific code enhancements using static analysis.
 */
public class EnhancementFinder {

    private static final Logger LOG = LoggerFactory.getLogger(EnhancementFinder.class);

    private final AutoIdeatorConfig config;

    // Patterns for common code issues
    private static final Pattern TODO_PATTERN = Pattern.compile("//\\s*TODO[:\\s]*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIXME_PATTERN = Pattern.compile("//\\s*FIXME[:\\s]*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HACK_PATTERN = Pattern.compile("//\\s*HACK[:\\s]*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}");
    private static final Pattern SYSTEM_OUT = Pattern.compile("System\\.(out|err)\\.print");
    private static final Pattern PRINT_STACK_TRACE = Pattern.compile("\\.printStackTrace\\(\\)");
    private static final Pattern HARD_CODED_PASSWORD = Pattern.compile("(password|passwd|pwd)\\s*=\\s*\"[^\"]+\"", Pattern.CASE_INSENSITIVE);

    public EnhancementFinder(AutoIdeatorConfig config) {
        this.config = config;
    }

    /**
     * Scan project for potential enhancements.
     */
    public List<Enhancement> scanForEnhancements(Path projectPath) throws IOException {
        List<Enhancement> enhancements = new ArrayList<>();

        Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();

                // Only scan source files
                if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".ts") || name.endsWith(".js")) {
                    try {
                        long fileSize = attrs.size();
                        if (fileSize > 10_000_000) {
                            LOG.debug("Skipping large file: {} ({} bytes)", file, fileSize);
                            return FileVisitResult.CONTINUE;
                        }
                        String content = Files.readString(file);
                        enhancements.addAll(scanFile(file, content));
                    } catch (IOException e) {
                        LOG.debug("Failed to scan file: {}", file);
                    }
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (name.startsWith(".") || name.equals("build") || name.equals("node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // Sort by severity
        enhancements.sort(Comparator.comparingInt(e -> e.severity().getLevel()));

        return enhancements;
    }

    private List<Enhancement> scanFile(Path file, String content) {
        List<Enhancement> enhancements = new ArrayList<>();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            // Check for TODOs
            Matcher todoMatcher = TODO_PATTERN.matcher(line);
            if (todoMatcher.find()) {
                enhancements.add(new Enhancement(
                    EnhancementType.TODO,
                    Severity.LOW,
                    file.toString(),
                    lineNum,
                    "TODO: " + todoMatcher.group(1).trim()
                ));
            }

            // Check for FIXMEs
            Matcher fixmeMatcher = FIXME_PATTERN.matcher(line);
            if (fixmeMatcher.find()) {
                enhancements.add(new Enhancement(
                    EnhancementType.TODO,
                    Severity.MEDIUM,
                    file.toString(),
                    lineNum,
                    "FIXME: " + fixmeMatcher.group(1).trim()
                ));
            }

            // Check for HACKs
            Matcher hackMatcher = HACK_PATTERN.matcher(line);
            if (hackMatcher.find()) {
                enhancements.add(new Enhancement(
                    EnhancementType.CODE_SMELL,
                    Severity.MEDIUM,
                    file.toString(),
                    lineNum,
                    "HACK: " + hackMatcher.group(1).trim()
                ));
            }

            // Check for empty catch blocks
            if (EMPTY_CATCH.matcher(line).find()) {
                enhancements.add(new Enhancement(
                    EnhancementType.ERROR_HANDLING,
                    Severity.HIGH,
                    file.toString(),
                    lineNum,
                    "Empty catch block - may swallow exceptions"
                ));
            }

            // Check for System.out/err usage
            if (SYSTEM_OUT.matcher(line).find()) {
                enhancements.add(new Enhancement(
                    EnhancementType.LOGGING,
                    Severity.LOW,
                    file.toString(),
                    lineNum,
                    "Use proper logging instead of System.out/err"
                ));
            }

            // Check for printStackTrace
            if (PRINT_STACK_TRACE.matcher(line).find()) {
                enhancements.add(new Enhancement(
                    EnhancementType.ERROR_HANDLING,
                    Severity.MEDIUM,
                    file.toString(),
                    lineNum,
                    "Use proper logging instead of printStackTrace()"
                ));
            }

            // Check for hardcoded passwords (security issue)
            if (HARD_CODED_PASSWORD.matcher(line).find()) {
                enhancements.add(new Enhancement(
                    EnhancementType.SECURITY,
                    Severity.CRITICAL,
                    file.toString(),
                    lineNum,
                    "Potential hardcoded password - use environment variables or secrets manager"
                ));
            }
        }

        return enhancements;
    }

    /**
     * Types of enhancements.
     */
    public enum EnhancementType {
        TODO("Pending task"),
        CODE_SMELL("Code quality issue"),
        SECURITY("Security vulnerability"),
        PERFORMANCE("Performance issue"),
        ERROR_HANDLING("Error handling issue"),
        LOGGING("Logging improvement"),
        DOCUMENTATION("Documentation needed"),
        TESTING("Test coverage gap");

        private final String description;

        EnhancementType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Severity levels.
     */
    public enum Severity {
        CRITICAL(1),
        HIGH(2),
        MEDIUM(3),
        LOW(4);

        private final int level;

        Severity(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }
    }

    /**
     * Represents a found enhancement opportunity.
     */
    public record Enhancement(
        EnhancementType type,
        Severity severity,
        String file,
        int line,
        String message
    ) {
        @Override
        public String toString() {
            return String.format("[%s] %s:%d - %s", severity, file, line, message);
        }
    }
}
