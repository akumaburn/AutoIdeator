package com.autoideator.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents the initial idea/concept to be developed.
 */
public record Idea(
    String description,
    Path workingDirectory,
    String id,
    Instant createdAt
) {
    public Idea(String description, Path workingDirectory) {
        this(description, workingDirectory, UUID.randomUUID().toString(), Instant.now());
    }

    public Idea withDescription(String newDescription) {
        return new Idea(newDescription, workingDirectory, id, createdAt);
    }
}
