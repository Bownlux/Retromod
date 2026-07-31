/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks memory, CPU, TPS, and per-mod transform cost, and warns the user
 * which mods are heaviest when performance degrades.
 */
public class MemorySafetyMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Performance");

    private static MemorySafetyMonitor instance;

    private static final double MEMORY_CRITICAL_PERCENT = 0.90;
    private static final double MEMORY_WARNING_PERCENT = 0.80;
    private static final double CPU_CRITICAL_PERCENT = 0.95;
    private static final double CPU_WARNING_PERCENT = 0.80;
    private static final int TPS_CRITICAL = 5;
    private static final int TPS_WARNING = 10;
    private static final int NORMAL_TPS = 20;

    private final MemoryMXBean memoryBean;
    private final OperatingSystemMXBean osBean;

    private final Map<String, ModPerformanceData> modPerformance = new ConcurrentHashMap<>();

    private final AtomicInteger activeTransforms = new AtomicInteger(0);
    private final AtomicLong totalTransformTimeNs = new AtomicLong(0);
    private final AtomicInteger totalTransformed = new AtomicInteger(0);

    private volatile int currentTps = NORMAL_TPS;
    private long lastTickTime = System.currentTimeMillis();
    private int tickCount = 0;

    private volatile boolean shutdownRequested = false;
    private volatile boolean warningShown = false;
    private volatile PerformanceIssue lastIssue = null;
    
    public enum PerformanceIssue {
        NONE,
        MEMORY_WARNING,
        MEMORY_CRITICAL,
        CPU_WARNING,
        CPU_CRITICAL,
        TPS_WARNING,
        TPS_CRITICAL
    }
    
    public static class ModPerformanceData {
        public final String modId;
        public final String modName;
        public long totalTransformTimeNs = 0;
        public int classesTransformed = 0;
        public long memoryUsedBytes = 0;
        public int transformErrors = 0;
        
        public ModPerformanceData(String modId, String modName) {
            this.modId = modId;
            this.modName = modName != null ? modName : modId;
        }
        
        public double getAverageTransformTimeMs() {
            if (classesTransformed == 0) return 0;
            return (totalTransformTimeNs / 1_000_000.0) / classesTransformed;
        }
        
        public long getTotalTransformTimeMs() {
            return totalTransformTimeNs / 1_000_000;
        }
    }
    
    private MemorySafetyMonitor() {
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.osBean = ManagementFactory.getOperatingSystemMXBean();

        Thread monitor = new Thread(this::monitorLoop, "Retromod-PerformanceMonitor");
        monitor.setDaemon(true);
        monitor.start();
    }
    
    public static synchronized MemorySafetyMonitor getInstance() {
        if (instance == null) {
            instance = new MemorySafetyMonitor();
        }
        return instance;
    }
    
    /** Called by Minecraft's tick loop to track TPS. */
    public void onServerTick() {
        tickCount++;
        long now = System.currentTimeMillis();
        long elapsed = now - lastTickTime;

        if (elapsed >= 1000) {
            currentTps = (int) (tickCount * 1000.0 / elapsed);
            tickCount = 0;
            lastTickTime = now;

            if (currentTps <= TPS_CRITICAL && !warningShown) {
                lastIssue = PerformanceIssue.TPS_CRITICAL;
                showPerformanceError();
            } else if (currentTps <= TPS_WARNING && !warningShown) {
                lastIssue = PerformanceIssue.TPS_WARNING;
            }
        }
    }
    
    /** Call before starting to transform a class. */
    public TransformContext beginTransform(String className, String modId) {
        if (shutdownRequested) {
            return null;
        }

        activeTransforms.incrementAndGet();

        PerformanceIssue issue = checkPerformance();
        if (issue == PerformanceIssue.MEMORY_CRITICAL ||
            issue == PerformanceIssue.CPU_CRITICAL ||
            issue == PerformanceIssue.TPS_CRITICAL) {

            activeTransforms.decrementAndGet();
            if (!warningShown) {
                lastIssue = issue;
                showPerformanceError();
            }
            return null;
        }
        
        return new TransformContext(className, modId, System.nanoTime());
    }
    
    /** Call after finishing transformation. */
    public void endTransform(TransformContext ctx, boolean success, long memoryUsed) {
        if (ctx == null) return;

        activeTransforms.decrementAndGet();
        long elapsed = System.nanoTime() - ctx.startTimeNs;
        totalTransformTimeNs.addAndGet(elapsed);

        if (success) {
            totalTransformed.incrementAndGet();
        }

        if (ctx.modId != null) {
            ModPerformanceData data = modPerformance.computeIfAbsent(
                ctx.modId, 
                id -> new ModPerformanceData(id, ctx.modId)
            );
            data.totalTransformTimeNs += elapsed;
            data.classesTransformed++;
            data.memoryUsedBytes += memoryUsed;
            if (!success) {
                data.transformErrors++;
            }
        }
    }
    
    public PerformanceIssue checkPerformance() {
        double memPercent = getMemoryUsagePercent();
        if (memPercent > MEMORY_CRITICAL_PERCENT) {
            return PerformanceIssue.MEMORY_CRITICAL;
        }
        if (memPercent > MEMORY_WARNING_PERCENT) {
            return PerformanceIssue.MEMORY_WARNING;
        }

        double cpuPercent = getCpuUsagePercent();
        if (cpuPercent > CPU_CRITICAL_PERCENT) {
            return PerformanceIssue.CPU_CRITICAL;
        }
        if (cpuPercent > CPU_WARNING_PERCENT) {
            return PerformanceIssue.CPU_WARNING;
        }

        if (currentTps <= TPS_CRITICAL) {
            return PerformanceIssue.TPS_CRITICAL;
        }
        if (currentTps <= TPS_WARNING) {
            return PerformanceIssue.TPS_WARNING;
        }

        return PerformanceIssue.NONE;
    }

    /** Heap usage as a fraction from 0.0 to 1.0. */
    public double getMemoryUsagePercent() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        return (double) heap.getUsed() / heap.getMax();
    }

    /** Process CPU load as a fraction from 0.0 to 1.0. */
    public double getCpuUsagePercent() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            double load = sunBean.getProcessCpuLoad();
            return load >= 0 ? load : 0.5; // 50% when unavailable
        }
        return osBean.getSystemLoadAverage() / osBean.getAvailableProcessors();
    }

    public int getCurrentTps() {
        return currentTps;
    }

    /** Mods sorted by total transform time, heaviest first. */
    public List<ModPerformanceData> getHeaviestMods(int limit) {
        return modPerformance.values().stream()
            .sorted((a, b) -> Long.compare(b.totalTransformTimeNs, a.totalTransformTimeNs))
            .limit(limit)
            .toList();
    }
    
    private void monitorLoop() {
        while (!shutdownRequested) {
            try {
                Thread.sleep(500);

                PerformanceIssue issue = checkPerformance();

                if (issue != PerformanceIssue.NONE && !warningShown) {
                    LOGGER.debug("Performance: Memory={}%, CPU={}%, TPS={}",
                        String.format("%.1f", getMemoryUsagePercent() * 100),
                        String.format("%.1f", getCpuUsagePercent() * 100),
                        currentTps);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.debug("Monitor error: {}", e.getMessage());
            }
        }
    }
    
    /** Reports the cause and heaviest mods, via GUI on a client or console on a server. */
    private void showPerformanceError() {
        warningShown = true;

        StringBuilder message = new StringBuilder();
        message.append("Retromod noticed a performance problem.\n\n");

        switch (lastIssue) {
            case MEMORY_CRITICAL, MEMORY_WARNING -> {
                message.append("Memory pressure\n");
                message.append(String.format("Memory usage: %.0f%%\n\n", getMemoryUsagePercent() * 100));
                message.append("Minecraft is close to its current memory limit.\n\n");
            }
            case CPU_CRITICAL, CPU_WARNING -> {
                message.append("High CPU use\n");
                message.append(String.format("CPU usage: %.0f%%\n\n", getCpuUsagePercent() * 100));
                message.append("Transforms and other game work are competing for CPU time.\n\n");
            }
            case TPS_CRITICAL, TPS_WARNING -> {
                message.append("Low server tick rate\n");
                message.append(String.format("Current TPS: %d (normal is 20)\n\n", currentTps));
            }
            default -> message.append("Minecraft is running more slowly than expected.\n\n");
        }

        List<ModPerformanceData> heavyMods = getHeaviestMods(5);
        if (!heavyMods.isEmpty()) {
            message.append("Slowest mod transforms:\n");
            
            for (int i = 0; i < heavyMods.size(); i++) {
                ModPerformanceData mod = heavyMods.get(i);
                message.append(String.format("%d. %s: %d ms, %d classes",
                    i + 1, mod.modName, mod.getTotalTransformTimeMs(), mod.classesTransformed));
                if (mod.memoryUsedBytes > 0) {
                    message.append(String.format(", %.1f MB", mod.memoryUsedBytes / 1024.0 / 1024.0));
                }
                message.append("\n");
            }
            message.append("\n");
        }

        message.append("Things to try:\n");
        
        if (lastIssue == PerformanceIssue.MEMORY_CRITICAL || 
            lastIssue == PerformanceIssue.MEMORY_WARNING) {
            message.append("- Raise Minecraft's memory limit if the computer has memory available.\n");
            if (EnvironmentDetector.isDedicatedServer()) {
                message.append("  Edit the server start script and review its -Xmx value.\n");
            } else {
                message.append("  In the launcher, open the installation's Java options and review -Xmx.\n");
            }
        }
        
        message.append("- Close other CPU or memory-heavy programs.\n");
        message.append("- Test without the slowest mod transforms listed above.\n");
        
        boolean isCritical = lastIssue == PerformanceIssue.MEMORY_CRITICAL ||
                             lastIssue == PerformanceIssue.CPU_CRITICAL ||
                             lastIssue == PerformanceIssue.TPS_CRITICAL;

        if (EnvironmentDetector.canShowGui()) {
            showGuiPerformanceError(message.toString(), isCritical);
        } else {
            showConsolePerformanceError(message.toString(), isCritical);
        }
    }

    private void showGuiPerformanceError(String message, boolean isCritical) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            if (isCritical) {
                int choice = JOptionPane.showOptionDialog(
                    null,
                    message,
                    "Retromod performance problem",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.ERROR_MESSAGE,
                    null,
                    new String[]{"Exit Minecraft", "Keep Running"},
                    "Exit Minecraft"
                );
                
                if (choice == 0) {
                    LOGGER.error("Exiting after a serious performance warning.");
                    System.exit(1);
                } else {
                    LOGGER.warn("Continuing after a serious performance warning.");
                    shutdownRequested = false;
                    warningShown = false; // let a later issue prompt again
                }
            } else {
                JOptionPane.showMessageDialog(
                    null,
                    message,
                    "Retromod performance warning",
                    JOptionPane.WARNING_MESSAGE
                );
                warningShown = false;
            }
        });
    }
    
    private void showConsolePerformanceError(String message, boolean isCritical) {
        if (isCritical) {
            LOGGER.error("Retromod detected a serious performance problem:");
            for (String line : message.split("\n")) {
                LOGGER.error("{}", line);
            }
            LOGGER.error("The server will keep running, but you should review the items above.");
        } else {
            LOGGER.warn("Retromod detected a performance problem:");
            for (String line : message.split("\n")) {
                LOGGER.warn("{}", line);
            }
        }

        warningShown = false;
    }

    public void requestGarbageCollection() {
        double before = getMemoryUsagePercent();
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        double after = getMemoryUsagePercent();
        LOGGER.info("GC: {}% -> {}%", String.format("%.1f", before * 100), String.format("%.1f", after * 100));
    }
    
    /** Tracks one in-flight class transformation. */
    public static class TransformContext {
        public final String className;
        public final String modId;
        public final long startTimeNs;
        
        public TransformContext(String className, String modId, long startTimeNs) {
            this.className = className;
            this.modId = modId;
            this.startTimeNs = startTimeNs;
        }
    }
}
