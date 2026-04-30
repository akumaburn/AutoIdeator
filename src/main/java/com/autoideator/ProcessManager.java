package com.autoideator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages spawned processes and ensures they are killed on JVM shutdown.
 * Uses eager singleton initialization for thread-safety.
 */
public class ProcessManager {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessManager.class);
    private static final ProcessManager INSTANCE = new ProcessManager();

    private final List<Process> activeProcesses = new CopyOnWriteArrayList<>();
    private volatile boolean shutdownHookRegistered = false;

    private ProcessManager() {
        registerShutdownHook();
    }

    public static ProcessManager getInstance() {
        return INSTANCE;
    }

    /**
     * Register a process to be tracked and killed on shutdown.
     * @param process The process to track
     * @return The same process for chaining
     */
    public Process register(Process process) {
        activeProcesses.add(process);

        // Clean up completed processes periodically (exclude the just-added
        // process to avoid a race where it exits before removeIf runs)
        activeProcesses.removeIf(p -> p != process && !p.isAlive());

        LOG.debug("Registered process (total active: {})", activeProcesses.size());
        return process;
    }

    /**
     * Unregister a process (e.g., when it completes normally).
     * @param process The process to unregister
     */
    public void unregister(Process process) {
        activeProcesses.remove(process);
        LOG.debug("Unregistered process (total active: {})", activeProcesses.size());
    }

    /**
     * Kill all tracked processes immediately.
     */
    public void killAll() {
        LOG.info("Killing {} active process(es)...", activeProcesses.size());

        for (Process process : activeProcesses) {
            if (process.isAlive()) {
                try {
                    LOG.debug("Destroying process {}", process.pid());
                    process.destroyForcibly();
                } catch (Exception e) {
                    LOG.warn("Error destroying process {}: {}", process.pid(), e.getMessage());
                }
            }
        }

        // Give processes a moment to die
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Clear the list
        activeProcesses.clear();
        LOG.info("All processes killed");
    }

    private synchronized void registerShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown hook triggered - cleaning up processes");
            killAll();
        }, "ProcessManager-Shutdown-Hook"));

        shutdownHookRegistered = true;
        LOG.debug("Shutdown hook registered");
    }

    /**
     * Get the number of active tracked processes.
     */
    public int getActiveProcessCount() {
        return (int) activeProcesses.stream().filter(Process::isAlive).count();
    }
}
