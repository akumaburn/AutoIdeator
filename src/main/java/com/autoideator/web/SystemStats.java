package com.autoideator.web;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.time.Duration;
import java.time.Instant;

/**
 * System statistics for the dashboard.
 */
public record SystemStats(
    long timestamp,
    MemoryStats memory,
    CpuStats cpu,
    ProcessStats process,
    int activeAgents,
    int cycleCount,
    long totalTokensUsed,
    Duration uptime
) {
    public record MemoryStats(
        long heapUsedMB,
        long heapMaxMB,
        long heapUsedPercent,
        long nonHeapUsedMB
    ) {}

    public record CpuStats(
        double processCpuLoad,
        double systemCpuLoad,
        int availableProcessors
    ) {}

    public record ProcessStats(
        long threadCount,
        long peakThreadCount,
        long totalStartedThreads,
        Duration uptime
    ) {}

    public static SystemStats current(int activeAgents, int cycleCount, long totalTokensUsed, Instant startTime) {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        var threadBean = ManagementFactory.getThreadMXBean();
        var runtimeBean = ManagementFactory.getRuntimeMXBean();

        var heapUsage = memoryBean.getHeapMemoryUsage();
        long heapUsed = heapUsage.getUsed() / (1024 * 1024);
        long rawMax = heapUsage.getMax();
        long heapMax = rawMax > 0 ? rawMax / (1024 * 1024) : -1;
        // Compute percentage from raw byte values to avoid precision loss from MB truncation
        long heapPercent = rawMax > 0 ? (heapUsage.getUsed() * 100 / rawMax) : 0;
        long nonHeap = memoryBean.getNonHeapMemoryUsage().getUsed() / (1024 * 1024);

        MemoryStats memory = new MemoryStats(heapUsed, heapMax, heapPercent, nonHeap);

        double processCpu = 0;
        double systemCpu = 0;
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
            processCpu = sunOsBean.getProcessCpuLoad() * 100;
            systemCpu = sunOsBean.getCpuLoad() * 100;
            if (Double.isNaN(processCpu) || processCpu < 0) processCpu = 0;
            if (Double.isNaN(systemCpu) || systemCpu < 0) systemCpu = 0;
            // Cap at 100% — some Linux kernels/containers can report values slightly above 1.0
            processCpu = Math.min(processCpu, 100.0);
            systemCpu = Math.min(systemCpu, 100.0);
        }

        CpuStats cpu = new CpuStats(
            Math.round(processCpu * 10.0) / 10.0,
            Math.round(systemCpu * 10.0) / 10.0,
            osBean.getAvailableProcessors()
        );

        ProcessStats process = new ProcessStats(
            threadBean.getThreadCount(),
            threadBean.getPeakThreadCount(),
            threadBean.getTotalStartedThreadCount(),
            Duration.ofMillis(runtimeBean.getUptime())
        );

        return new SystemStats(
            System.currentTimeMillis(),
            memory,
            cpu,
            process,
            activeAgents,
            cycleCount,
            totalTokensUsed,
            Duration.between(startTime, Instant.now())
        );
    }
}
