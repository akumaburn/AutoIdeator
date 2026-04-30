package com.autoideator.git;

import com.autoideator.ProcessManager;
import com.autoideator.config.AutoIdeatorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Handles Git operations for version control.
 */
public class GitOperations {

    private static final Logger LOG = LoggerFactory.getLogger(GitOperations.class);
    private static final long GIT_TIMEOUT_SECONDS = 30;

    private final AutoIdeatorConfig config;
    private final Path workingDirectory;

    public GitOperations(AutoIdeatorConfig config) {
        this.config = config;
        // Use workingDir from config if available, otherwise fall back to current directory
        this.workingDirectory = config.workingDir() != null
            ? config.workingDir()
            : Path.of(System.getProperty("user.dir"));
    }

    public GitOperations(AutoIdeatorConfig config, Path workingDirectory) {
        this.config = config;
        this.workingDirectory = workingDirectory;
    }

    /**
     * Check if we're in a Git repository.
     */
    public boolean isGitRepository() {
        try {
            executeGit("rev-parse", "--git-dir");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Initialize a new Git repository.
     */
    public void initRepository() throws GitException {
        if (isGitRepository()) {
            LOG.debug("Already in a Git repository");
            return;
        }

        try {
            executeGit("init");
            LOG.info("Initialized Git repository");
        } catch (Exception e) {
            throw new GitException("Failed to initialize repository", e);
        }
    }

    /**
     * Get current branch name.
     */
    public String getCurrentBranch() throws GitException {
        try {
            return executeGit("branch", "--show-current").trim();
        } catch (Exception e) {
            throw new GitException("Failed to get current branch", e);
        }
    }

    /**
     * Get repository status.
     */
    public GitStatus getStatus() throws GitException {
        try {
            String output = executeGit("status", "--porcelain");
            List<String> modified = new ArrayList<>();
            List<String> untracked = new ArrayList<>();
            List<String> staged = new ArrayList<>();

            for (String line : output.split("\n")) {
                if (line.isBlank() || line.length() < 4) continue;

                String statusCode = line.substring(0, 2);
                String file = line.substring(3);

                // For renames/copies, git outputs "old -> new"; use the new path
                if ((statusCode.charAt(0) == 'R' || statusCode.charAt(0) == 'C') && file.contains(" -> ")) {
                    String[] renameParts = file.split(" -> ", 2);
                    file = renameParts[1];
                }

                if (statusCode.startsWith("??")) {
                    untracked.add(file);
                } else {
                    if (statusCode.matches("[MADRC].")) {
                        staged.add(file);
                    }
                    if (statusCode.matches(".[MD]")) {
                        modified.add(file);
                    }
                }
            }

            return new GitStatus(modified, untracked, staged);
        } catch (Exception e) {
            throw new GitException("Failed to get status", e);
        }
    }

    /**
     * Stage all changes.
     */
    public void stageAll() throws GitException {
        try {
            executeGit("add", "-A");
            LOG.debug("Staged all changes");
        } catch (Exception e) {
            throw new GitException("Failed to stage changes", e);
        }
    }

    /**
     * Stage specific files.
     */
    public void stageFiles(List<String> files) throws GitException {
        try {
            // Validate file paths for security
            for (String file : files) {
                validateFilePath(file);
            }

            List<String> args = new ArrayList<>();
            args.add("add");
            args.addAll(files);
            executeGit(args.toArray(new String[0]));
            LOG.debug("Staged {} files", files.size());
        } catch (Exception e) {
            throw new GitException("Failed to stage files", e);
        }
    }

    /**
     * Validate file path to prevent command injection and path traversal.
     */
    private void validateFilePath(String path) throws GitException {
        if (path == null || path.isBlank()) {
            throw new GitException("File path cannot be null or blank");
        }
        // Check for shell metacharacters that could enable command injection
        if (path.contains(";") || path.contains("|") || path.contains("&")
                || path.contains("`") || path.contains("$") || path.contains("\n")
                || path.contains("\r") || path.contains("\0")) {
            throw new GitException("Invalid file path: contains forbidden characters");
        }
        // Check for path traversal attempts
        if (path.contains("..")) {
            throw new GitException("Path traversal not allowed in file path");
        }
    }

    /**
     * Commit all staged changes.
     */
    public void commit(String message) throws GitException {
        try {
            String sanitizedMessage = sanitizeCommitMessage(message);
            executeGit("commit", "-m", sanitizedMessage);
            LOG.info("Committed: {}", sanitizedMessage);
        } catch (Exception e) {
            throw new GitException("Failed to commit", e);
        }
    }

    /**
     * Sanitize commit message — only strips control characters.
     * ProcessBuilder passes arguments directly to execvp (no shell), so
     * shell metacharacters like ;|&$` are harmless and should be preserved.
     */
    private String sanitizeCommitMessage(String message) {
        if (message == null) {
            return "autoideator: empty commit message";
        }
        return message
            .replace("\r", "")
            .replace("\0", "")
            .trim();
    }

    /**
     * Stage all changes and commit.
     */
    public void commitAll(String message) throws GitException {
        GitStatus status = getStatus();
        if (status.isEmpty()) {
            LOG.debug("No changes to commit");
            return;
        }

        stageAll();
        commit(formatCommitMessage(message));
    }

    /**
     * Create a new branch.
     */
    public void createBranch(String branchName) throws GitException {
        validateBranchName(branchName);
        try {
            executeGit("checkout", "-b", branchName);
            LOG.info("Created and switched to branch: {}", branchName);
        } catch (Exception e) {
            throw new GitException("Failed to create branch: " + branchName, e);
        }
    }

    /**
     * Switch to a branch.
     */
    public void checkout(String branchName) throws GitException {
        validateBranchName(branchName);
        try {
            executeGit("checkout", branchName);
            LOG.info("Switched to branch: {}", branchName);
        } catch (Exception e) {
            throw new GitException("Failed to checkout branch: " + branchName, e);
        }
    }

    /**
     * Merge a branch into current branch.
     */
    public void merge(String branchName) throws GitException {
        validateBranchName(branchName);
        try {
            executeGit("merge", "--no-ff", branchName, "-m", "Merge branch '" + branchName + "'");
            LOG.info("Merged branch: {}", branchName);
        } catch (Exception e) {
            throw new GitException("Failed to merge branch: " + branchName, e);
        }
    }

    /**
     * Validate branch name to prevent command injection.
     */
    private void validateBranchName(String branchName) throws GitException {
        if (branchName == null || branchName.isBlank()) {
            throw new GitException("Branch name cannot be null or blank");
        }
        if (branchName.contains(";") || branchName.contains("|") || branchName.contains("&")
                || branchName.contains("`") || branchName.contains("$") || branchName.contains("\n")
                || branchName.contains("\r") || branchName.contains("\0") || branchName.contains("..")) {
            throw new GitException("Invalid branch name: contains forbidden characters");
        }
    }

    /**
     * Get commit log.
     */
    public List<CommitInfo> getLog(int limit) throws GitException {
        try {
            // Use %x00 (null byte) as delimiter to avoid conflicts with pipes in commit messages.
            // %aI gives ISO-8601 strict timestamps.
            String format = "%H%x00%s%x00%an%x00%aI";
            String output = executeGit("log", "--format=" + format, "-n", String.valueOf(limit));

            List<CommitInfo> commits = new ArrayList<>();
            for (String line : output.split("\n")) {
                if (line.isBlank()) continue;

                String[] parts = line.split("\0", 4);
                if (parts.length >= 4) {
                    try {
                        Instant timestamp = OffsetDateTime.parse(parts[3].trim()).toInstant();
                        commits.add(new CommitInfo(parts[0], parts[1], parts[2], timestamp));
                    } catch (DateTimeParseException e) {
                        LOG.debug("Failed to parse commit timestamp '{}', skipping", parts[3]);
                    }
                }
            }

            return commits;
        } catch (Exception e) {
            throw new GitException("Failed to get log", e);
        }
    }

    /**
     * Returns a human-readable summary of the most recent commits, one per line.
     * Format: {@code <short-hash> <subject> (<author>)}
     *
     * @param limit maximum number of commits to include
     * @return formatted log string, or a fallback message on error
     */
    public String getRecentCommitLog(int limit) {
        try {
            List<CommitInfo> commits = getLog(limit);
            if (commits.isEmpty()) {
                return "(no commits yet)";
            }
            StringBuilder sb = new StringBuilder();
            for (CommitInfo c : commits) {
                sb.append(c.hash().substring(0, Math.min(7, c.hash().length())))
                    .append(" ").append(c.message())
                    .append(" (").append(c.author()).append(")\n");
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "(unable to read commit log: " + e.getMessage() + ")";
        }
    }

    /**
     * Count the number of commits reachable from HEAD.
     *
     * @return the commit count, or 0 if the directory is not a git repo or has no commits
     */
    public int getCommitCount() {
        try {
            String output = executeGit("rev-list", "--count", "HEAD").trim();
            return Integer.parseInt(output);
        } catch (Exception e) {
            LOG.debug("Could not count commits (no repo or no commits yet): {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Get diff of uncommitted changes.
     */
    public String getDiff() throws GitException {
        try {
            return executeGit("diff", "HEAD");
        } catch (Exception e) {
            throw new GitException("Failed to get diff", e);
        }
    }

    private String executeGit(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));

        if (config.orchestration().sandboxEnabled()
                && com.autoideator.sandbox.BubblewrapSandbox.isAvailable()) {
            command = new ArrayList<>(
                com.autoideator.sandbox.BubblewrapSandbox.wrapCommand(command, workingDirectory));
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(false);
        // Prevent git from blocking on interactive prompts (credentials, editors, etc.)
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "echo");
        pb.environment().put("GIT_EDITOR", "true");

        Process process = pb.start();
        ProcessManager.getInstance().register(process);

        try {
            // Read stdout and stderr in parallel to prevent pipe-buffer deadlock.
            // StringBuffer is used because these are written from virtual threads.
            StringBuffer outputBuilder = new StringBuffer();
            StringBuffer errorBuilder = new StringBuffer();

            Thread outputThread = Thread.startVirtualThread(() -> {
                try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputBuilder.append(line).append("\n");
                    }
                } catch (IOException e) {
                    LOG.debug("Error reading git stdout: {}", e.getMessage());
                }
            });

            Thread errorThread = Thread.startVirtualThread(() -> {
                try (BufferedReader reader = process.errorReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorBuilder.append(line).append("\n");
                    }
                } catch (IOException e) {
                    LOG.debug("Error reading git stderr: {}", e.getMessage());
                }
            });

            boolean completed = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                joinThread(outputThread, 2000);
                joinThread(errorThread, 2000);
                throw new IOException("Git command timed out after " + GIT_TIMEOUT_SECONDS + " seconds");
            }

            joinThread(outputThread, 5000);
            joinThread(errorThread, 5000);

            String output = outputBuilder.toString();
            String error = errorBuilder.toString();
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                // git commit returns exit code 1 when there is nothing to commit — not an error
                if (args.length > 0 && "commit".equals(args[0]) && exitCode == 1 && error.contains("nothing to commit")) {
                    return output;
                }
                throw new IOException("Git command 'git " + String.join(" ", args) + "' failed (exit " + exitCode + "): " + error);
            }

            return output;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            ProcessManager.getInstance().unregister(process);
        }
    }

    private void joinThread(Thread thread, long timeoutMs) {
        if (thread != null) {
            try {
                thread.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String formatCommitMessage(String message) {
        String format = config.git().commitFormat();
        if (!format.contains("${prefix}") && !format.contains("${message}")) {
            return "autoideator: " + message;
        }
        return format.replace("${prefix}", "autoideator").replace("${message}", message);
    }

    /**
     * Git repository status.
     */
    public record GitStatus(
        List<String> modified,
        List<String> untracked,
        List<String> staged
    ) {
        /** Compact constructor — defensively copies mutable lists to preserve immutability. */
        public GitStatus {
            modified = List.copyOf(modified);
            untracked = List.copyOf(untracked);
            staged = List.copyOf(staged);
        }

        public boolean isEmpty() {
            return modified.isEmpty() && untracked.isEmpty() && staged.isEmpty();
        }

        public List<String> getAllChanged() {
            List<String> all = new ArrayList<>();
            all.addAll(modified);
            all.addAll(untracked);
            all.addAll(staged);
            return all;
        }
    }

    /**
     * Commit information.
     */
    public record CommitInfo(
        String hash,
        String message,
        String author,
        Instant timestamp
    ) {}

    /**
     * Git operation exception.
     */
    public static class GitException extends Exception {
        public GitException(String message) {
            super(message);
        }

        public GitException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
