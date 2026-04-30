package com.autoideator.orchestrator;

import com.autoideator.model.ProjectPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Detects project phase and type based on repository state and file structure.
 */
public class PhaseDetector {
    
    private static final Logger LOG = LoggerFactory.getLogger(PhaseDetector.class);
    
    /**
     * Detect the current project phase based on commit count.
     */
    public static ProjectPhase detectPhase(int commitCount) {
        ProjectPhase phase = ProjectPhase.fromCommitCount(commitCount);
        LOG.debug("Detected project phase: {} (based on {} commits)", phase, commitCount);
        return phase;
    }
    
    /**
     * Detect project type based on file structure.
     */
    public static ProjectType detectProjectType(Path workingDir) {
        Set<String> files = listTopLevelFiles(workingDir);
        
        // Check for frontend indicators
        boolean hasFrontend = hasFrontendIndicators(files, workingDir);
        
        // Check for backend/API indicators
        boolean hasBackend = hasBackendIndicators(files, workingDir);
        
        // Check for CLI indicators
        boolean hasCLI = hasCLIIndicators(files, workingDir);
        
        // Check for library indicators
        boolean hasLibrary = hasLibraryIndicators(files, workingDir);
        
        // Determine primary type
        if (hasFrontend && hasBackend) {
            return ProjectType.FULL_STACK;
        } else if (hasFrontend) {
            return ProjectType.FRONTEND;
        } else if (hasBackend) {
            return ProjectType.BACKEND_API;
        } else if (hasCLI) {
            return ProjectType.CLI_TOOL;
        } else if (hasLibrary) {
            return ProjectType.LIBRARY;
        } else {
            return ProjectType.GENERIC;
        }
    }
    
    private static Set<String> listTopLevelFiles(Path dir) {
        Set<String> files = new HashSet<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> !p.getFileName().toString().startsWith("."))
                .limit(50)
                .forEach(p -> files.add(p.getFileName().toString().toLowerCase()));
        } catch (IOException e) {
            LOG.debug("Could not list files in {}", dir, e);
        }
        return files;
    }
    
    private static boolean hasFrontendIndicators(Set<String> files, Path workingDir) {
        // Check for common frontend files/directories
        if (files.contains("index.html") || 
            files.contains("index.jsx") || 
            files.contains("index.tsx") ||
            files.contains("app.jsx") ||
            files.contains("app.tsx") ||
            files.contains("app.js") ||
            files.contains("main.jsx") ||
            files.contains("main.tsx")) {
            return true;
        }
        
        // Check for frontend directories
        if (files.contains("src") || files.contains("public") || 
            files.contains("components") || files.contains("pages") ||
            files.contains("views") || files.contains("assets") ||
            files.contains("styles") || files.contains("css")) {
            
            // Verify it's actually frontend by checking for frontend-specific files.
            // Limit depth to 5 to avoid traversing deep node_modules trees.
            Path srcDir = workingDir.resolve("src");
            if (Files.exists(srcDir)) {
                try (var stream = Files.walk(srcDir, 5)) {
                    boolean hasJSX = stream
                        .anyMatch(p -> {
                            String name = p.toString();
                            return name.endsWith(".jsx") ||
                                   name.endsWith(".tsx") ||
                                   name.endsWith(".vue") ||
                                   name.endsWith(".svelte");
                        });
                    if (hasJSX) return true;
                } catch (IOException e) {
                    LOG.debug("Could not walk src directory", e);
                }
            }
        }
        
        // Check for package.json with frontend dependencies
        Path packageJson = workingDir.resolve("package.json");
        if (Files.exists(packageJson)) {
            try {
                String content = Files.readString(packageJson).toLowerCase();
                if (content.contains("react") || content.contains("vue") || 
                    content.contains("angular") || content.contains("svelte") ||
                    content.contains("next") || content.contains("sveltekit")) {
                    return true;
                }
            } catch (IOException e) {
                LOG.debug("Could not read package.json", e);
            }
        }
        
        return false;
    }
    
    private static boolean hasBackendIndicators(Set<String> files, Path workingDir) {
        // Check for backend entry points
        if (files.contains("server.js") || 
            files.contains("server.ts") ||
            files.contains("app.py") ||
            files.contains("main.py") ||
            files.contains("main.go") ||
            files.contains("main.java") ||
            files.contains("build.gradle") ||
            files.contains("pom.xml")) {
            return true;
        }
        
        // Check for API/backend directories
        if (files.contains("api") || files.contains("routes") || 
            files.contains("controllers") || files.contains("services") ||
            files.contains("models") || files.contains("repositories")) {
            return true;
        }
        
        // Check for backend config
        if (files.contains("application.conf") || files.contains("application.properties") ||
            files.contains("config.py") || files.contains("settings.py")) {
            return true;
        }
        
        return false;
    }
    
    private static boolean hasCLIIndicators(Set<String> files, Path workingDir) {
        // Check for CLI entry points
        if (files.contains("cli.js") || files.contains("cli.py") ||
            files.contains("__main__.py") || files.contains("main.go")) {
            return true;
        }
        
        // Check package.json for bin field
        Path packageJson = workingDir.resolve("package.json");
        if (Files.exists(packageJson)) {
            try {
                String content = Files.readString(packageJson);
                if (content.contains("\"bin\"") || content.contains("commander") || 
                    content.contains("yargs") || content.contains("cli")) {
                    return true;
                }
            } catch (IOException e) {
                LOG.debug("Could not read package.json", e);
            }
        }
        
        // Check for setup.py with entry points
        Path setupPy = workingDir.resolve("setup.py");
        if (Files.exists(setupPy)) {
            try {
                String content = Files.readString(setupPy);
                if (content.contains("entry_points") || content.contains("console_scripts")) {
                    return true;
                }
            } catch (IOException e) {
                LOG.debug("Could not read setup.py", e);
            }
        }
        
        return false;
    }
    
    private static boolean hasLibraryIndicators(Set<String> files, Path workingDir) {
        // Libraries typically have lib or src directories but no app/server entry points
        if (files.contains("lib") && !files.contains("server.js") && 
            !files.contains("app.js") && !files.contains("index.html")) {
            return true;
        }
        
        // Check for library-specific files
        if (files.contains("index.js") || files.contains("index.ts")) {
            // Check if it's a library by looking for exports
            Path indexFile = workingDir.resolve("index.js");
            if (!Files.exists(indexFile)) {
                indexFile = workingDir.resolve("index.ts");
            }
            
            if (Files.exists(indexFile)) {
                try {
                    String content = Files.readString(indexFile);
                    if (content.contains("module.exports") || content.contains("export ")) {
                        // Likely a library if it has exports but no server/app
                        return !hasBackendIndicators(files, workingDir) && 
                               !hasFrontendIndicators(files, workingDir);
                    }
                } catch (IOException e) {
                    LOG.debug("Could not read index file", e);
                }
            }
        }
        
        return false;
    }
    
    /**
     * Project type classification.
     */
    public enum ProjectType {
        FRONTEND("Frontend Application", true),
        BACKEND_API("Backend API", false),
        FULL_STACK("Full-Stack Application", true),
        CLI_TOOL("CLI Tool", false),
        LIBRARY("Library/Package", false),
        GENERIC("Generic Project", false);
        
        private final String displayName;
        private final boolean hasUI;
        
        ProjectType(String displayName, boolean hasUI) {
            this.displayName = displayName;
            this.hasUI = hasUI;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public boolean hasUI() {
            return hasUI;
        }
    }
}
