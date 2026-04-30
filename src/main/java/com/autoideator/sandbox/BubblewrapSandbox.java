package com.autoideator.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wraps CLI commands in a bubblewrap (bwrap) sandbox for write isolation.
 * <p>
 * The sandbox mounts the entire filesystem as read-only, then selectively
 * grants write access to the project working directory and {@code /tmp}.
 * This prevents agent-spawned tools from modifying files outside the
 * intended project scope.
 * <p>
 * Sandbox layout:
 * <ul>
 *   <li>{@code --ro-bind / /}           — full filesystem, read-only</li>
 *   <li>{@code --dev /dev}              — device nodes</li>
 *   <li>{@code --proc /proc}            — procfs</li>
 *   <li>{@code --tmpfs /tmp}            — ephemeral writable tmpfs</li>
 *   <li>{@code --bind WORKDIR WORKDIR}  — writable project directory</li>
 *   <li>{@code --share-net}             — network access for LLM API calls</li>
 *   <li>{@code --die-with-parent}       — cleanup on parent exit</li>
 *   <li>{@code --new-session}           — new terminal session</li>
 * </ul>
 */
public final class BubblewrapSandbox {

    private static final Logger LOG = LoggerFactory.getLogger(BubblewrapSandbox.class);
    private static final String BWRAP = "bwrap";

    /**
     * Common build tool cache directories (relative to user home) that require
     * write access for normal operation. These are user-level caches — not
     * project files — and blocking them causes builds to fail inside the sandbox
     * (e.g. Gradle's "Failed to load native library 'libnative-platform.so'").
     */
    private static final String[] BUILD_TOOL_CACHES = {
        ".gradle",     // Gradle wrapper, daemon, native libs, dependency cache
        ".m2",         // Maven local repository
        ".npm",        // npm cache
        ".cargo",      // Rust/Cargo cache
        ".cache",      // Generic XDG cache (pip, yarn, etc.)
        ".local/share/gradle",  // Some Gradle setups use XDG dirs
        ".sdkman",     // SDKMAN (JDK version manager)
    };

    /** Cached availability check result. */
    private static volatile Boolean available;

    private BubblewrapSandbox() {}

    /**
     * Check whether the {@code bwrap} binary is available on the system PATH.
     * Result is cached after the first call.
     *
     * @return true if bwrap is installed and executable
     */
    public static boolean isAvailable() {
        if (available == null) {
            synchronized (BubblewrapSandbox.class) {
                if (available == null) {
                    available = checkBwrapAvailable();
                    if (available) {
                        LOG.info("Bubblewrap sandbox available — agent processes will be sandboxed");
                    } else {
                        LOG.warn("Bubblewrap (bwrap) not found in PATH — sandbox disabled. "
                            + "Install bubblewrap for write isolation of agent processes.");
                    }
                }
            }
        }
        return available;
    }

    private static boolean checkBwrapAvailable() {
        try {
            Process p = new ProcessBuilder(BWRAP, "--version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start();
            boolean completed = p.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Wrap a command in a bubblewrap sandbox.
     * <p>
     * Convenience overload that only mounts the working directory as writable.
     *
     * @param command    the original command and arguments
     * @param workingDir the project working directory (mounted read-write)
     * @return an unmodifiable list with the bwrap-wrapped command
     * @see #wrapCommand(List, Path, Path...)
     */
    public static List<String> wrapCommand(List<String> command, Path workingDir) {
        return wrapCommand(command, workingDir, new Path[0]);
    }

    /**
     * Wrap a command in a bubblewrap sandbox.
     * <p>
     * The working directory and any additional paths are bind-mounted
     * read-write. Everything else is read-only. {@code /tmp} is a fresh
     * tmpfs inside the sandbox (not shared with the host {@code /tmp}).
     * <p>
     * Additional writable paths are typically CLI tool data directories
     * (e.g. {@code ~/.local/share/opencode}, {@code ~/.claude}) that the
     * tool needs for its own database, logs, or session state.
     *
     * @param command              the original command and arguments
     * @param workingDir           the project working directory (mounted read-write);
     *                             may be null (in which case only /tmp is writable)
     * @param additionalWritePaths extra directories to bind read-write (e.g. tool data dirs);
     *                             null entries and non-existent paths are silently skipped
     * @return an unmodifiable list with the bwrap-wrapped command
     */
    public static List<String> wrapCommand(List<String> command, Path workingDir,
                                            Path... additionalWritePaths) {
        // Validate all paths to prevent bwrap flag injection.
        // ProcessBuilder passes arguments as an array (no shell expansion), but
        // bwrap itself parses its argv — a path starting with "-" could be
        // misinterpreted as a flag.
        validatePath(workingDir, "Working directory");
        if (additionalWritePaths != null) {
            for (Path p : additionalWritePaths) {
                validatePath(p, "Additional writable path");
            }
        }

        List<String> wrapped = new ArrayList<>();
        wrapped.add(BWRAP);

        // Read-only root filesystem
        wrapped.add("--ro-bind");
        wrapped.add("/");
        wrapped.add("/");

        // Device and proc filesystems
        wrapped.add("--dev");
        wrapped.add("/dev");
        wrapped.add("--proc");
        wrapped.add("/proc");

        // Writable /tmp (ephemeral tmpfs, not shared with host)
        wrapped.add("--tmpfs");
        wrapped.add("/tmp");

        // Writable project directory
        if (workingDir != null) {
            String dir = workingDir.toAbsolutePath().normalize().toString();
            wrapped.add("--bind");
            wrapped.add(dir);
            wrapped.add(dir);
        }

        // Additional writable directories (CLI tool data dirs, etc.)
        if (additionalWritePaths != null) {
            for (Path extraPath : additionalWritePaths) {
                if (extraPath == null) continue;
                Path abs = extraPath.toAbsolutePath().normalize();
                if (!java.nio.file.Files.exists(abs)) {
                    LOG.debug("Skipping non-existent additional writable path: {}", abs);
                    continue;
                }
                String dir = abs.toString();
                wrapped.add("--bind");
                wrapped.add(dir);
                wrapped.add(dir);
            }
        }

        // Build tool caches — these are user-level directories that build tools
        // (Gradle, Maven, npm, Cargo, etc.) need write access to for caching,
        // native library loading, and dependency management. Without these, tools
        // fail with errors like "Failed to load native library 'libnative-platform.so'"
        // or "Read-only file system" on lock file creation.
        String home = System.getProperty("user.home");
        if (home != null) {
            for (String cache : BUILD_TOOL_CACHES) {
                Path cachePath = Path.of(home, cache);
                if (java.nio.file.Files.exists(cachePath)) {
                    String dir = cachePath.toAbsolutePath().normalize().toString();
                    wrapped.add("--bind");
                    wrapped.add(dir);
                    wrapped.add(dir);
                    LOG.trace("Sandbox: mounting build tool cache read-write: {}", dir);
                }
            }
        }

        // Network access (required for LLM API calls via OpenRouter, etc.)
        wrapped.add("--share-net");

        // Terminate sandbox children when the parent (Java) process exits
        wrapped.add("--die-with-parent");

        // New terminal session (prevents TIOCSTI injection)
        wrapped.add("--new-session");

        // Set working directory inside the sandbox
        if (workingDir != null) {
            wrapped.add("--chdir");
            wrapped.add(workingDir.toAbsolutePath().normalize().toString());
        }

        // Separator and original command
        wrapped.add("--");
        wrapped.addAll(command);

        return Collections.unmodifiableList(wrapped);
    }

    private static void validatePath(Path path, String label) {
        if (path != null) {
            String normalized = path.toAbsolutePath().normalize().toString();
            if (normalized.startsWith("-")) {
                throw new IllegalArgumentException(
                    label + " must not start with '-': " + normalized);
            }
        }
    }
}
