package com.autoideator.checkpoint;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Manages orchestration checkpoints on disk, one per working directory.
 *
 * <p>Checkpoints are stored in {@code ~/.autoideator/checkpoints/} with filenames
 * derived from a SHA-256 hash of the working directory's absolute, normalized path.
 * This allows multiple projects to have independent checkpoints without
 * path-character issues in filenames.
 *
 * <p>Thread-safety: individual save/load/delete calls are atomic at the filesystem
 * level (write to temp file then atomic rename). Concurrent calls for different
 * directories are safe; concurrent calls for the same directory are safe because
 * only one orchestrator thread saves at a time and the rename is atomic.
 */
public class CheckpointManager {

    private static final Logger LOG = LoggerFactory.getLogger(CheckpointManager.class);
    private static final Path CHECKPOINT_DIR =
        Path.of(System.getProperty("user.home"), ".autoideator", "checkpoints");

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Save a checkpoint to disk. Overwrites any existing checkpoint for the
     * same working directory.
     *
     * @param checkpoint the checkpoint to persist
     */
    public void save(OrchestrationCheckpoint checkpoint) {
        Path tmp = null;
        try {
            Files.createDirectories(CHECKPOINT_DIR);
            Path target = checkpointPath(checkpoint.workingDirectory());
            tmp = target.resolveSibling(target.getFileName() + ".tmp");

            String json = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(checkpoint);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);

            // Atomic rename — prevents half-written files on crash.
            // Fall back to regular replace if the filesystem doesn't support ATOMIC_MOVE.
            try {
                Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                LOG.debug("Atomic move not supported, falling back to regular move");
                Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            LOG.info("Checkpoint saved for {} (cycle {})",
                checkpoint.workingDirectory(), checkpoint.cycleCount());
        } catch (IOException e) {
            LOG.warn("Failed to save checkpoint for {}: {}",
                checkpoint.workingDirectory(), e.getMessage());
            // Clean up temp file on failure
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Load the checkpoint for a working directory, if one exists and is valid.
     *
     * @param workingDirectory the absolute, normalized working directory path
     * @return the checkpoint, or empty if none exists or it's unreadable
     */
    public Optional<OrchestrationCheckpoint> load(String workingDirectory) {
        Path path = checkpointPath(workingDirectory);
        if (!Files.exists(path)) {
            // Fall back to legacy 32-char hash path for pre-migration checkpoints
            Path legacy = legacyCheckpointPath(workingDirectory);
            if (!legacy.equals(path) && Files.exists(legacy)) {
                LOG.info("Found checkpoint at legacy path, will migrate on next save: {}", legacy);
                path = legacy;
            } else {
                return Optional.empty();
            }
        }

        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            OrchestrationCheckpoint checkpoint = MAPPER.readValue(json, OrchestrationCheckpoint.class);

            // Version guard — reject checkpoints from incompatible future versions
            if (checkpoint.version() > OrchestrationCheckpoint.CURRENT_VERSION) {
                LOG.warn("Checkpoint version {} is newer than supported version {} — ignoring",
                    checkpoint.version(), OrchestrationCheckpoint.CURRENT_VERSION);
                return Optional.empty();
            }

            // Sanity check: working directory must match
            if (!workingDirectory.equals(checkpoint.workingDirectory())) {
                LOG.warn("Checkpoint working directory mismatch: expected '{}', got '{}' — ignoring",
                    workingDirectory, checkpoint.workingDirectory());
                return Optional.empty();
            }

            LOG.info("Loaded checkpoint for {} (cycle {}, saved {})",
                workingDirectory, checkpoint.cycleCount(), checkpoint.timestamp());
            return Optional.of(checkpoint);
        } catch (Exception e) {
            LOG.warn("Failed to load checkpoint for {}: {}",
                workingDirectory, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Delete the checkpoint for a working directory.
     *
     * @param workingDirectory the absolute, normalized working directory path
     * @return true if a checkpoint was deleted, false if none existed
     */
    public boolean delete(String workingDirectory) {
        Path path = checkpointPath(workingDirectory);
        try {
            boolean deleted = Files.deleteIfExists(path);
            // Also clean up legacy 32-char hash file if it exists
            Path legacy = legacyCheckpointPath(workingDirectory);
            if (!legacy.equals(path)) {
                deleted |= Files.deleteIfExists(legacy);
            }
            if (deleted) {
                LOG.info("Checkpoint deleted for {}", workingDirectory);
            }
            return deleted;
        } catch (IOException e) {
            LOG.warn("Failed to delete checkpoint for {}: {}",
                workingDirectory, e.getMessage());
            return false;
        }
    }

    /**
     * Check whether a checkpoint exists for the given working directory.
     */
    public boolean exists(String workingDirectory) {
        if (Files.exists(checkpointPath(workingDirectory))) {
            return true;
        }
        Path legacy = legacyCheckpointPath(workingDirectory);
        return !legacy.equals(checkpointPath(workingDirectory)) && Files.exists(legacy);
    }

    /**
     * Compute the filesystem path for a checkpoint file.
     * Uses the full SHA-256 hex hash of the working directory path to
     * avoid special characters in filenames.
     */
    private Path checkpointPath(String workingDirectory) {
        String hash = sha256Hex(workingDirectory);
        return CHECKPOINT_DIR.resolve(hash + ".json");
    }

    /**
     * Legacy path using only 32 hex chars (pre-migration).
     * Used as a fallback when loading/deleting to migrate old checkpoints.
     */
    private Path legacyCheckpointPath(String workingDirectory) {
        String hash = sha256Hex(workingDirectory);
        return CHECKPOINT_DIR.resolve(hash.substring(0, Math.min(32, hash.length())) + ".json");
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec — this is unreachable
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
