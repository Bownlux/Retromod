/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/** A busy machine never stops a class from being translated, and the report never opens a window. */
class MemorySafetyMonitorTest {

    @Test
    @DisplayName("A critical CPU or heap sample still lets the class be translated")
    void criticalSampleNeverSkipsTheTransform() {
        assertNotNull(new MemorySafetyMonitor(() -> 0.10, () -> 0.99, false).beginTransform("mod/A", "mod"),
                "critical CPU");
        assertNotNull(new MemorySafetyMonitor(() -> 0.95, () -> 0.10, false).beginTransform("mod/A", "mod"),
                "critical heap");
        assertNotNull(new MemorySafetyMonitor(() -> 0.10, () -> 0.10, false).beginTransform("mod/A", "mod"),
                "normal");
    }

    @Test
    @DisplayName("The report goes to the log once per launch and starts no window system")
    void reportIsLoggedOnceWithoutAWindow() throws Exception {
        MemorySafetyMonitor monitor = new MemorySafetyMonitor(() -> 0.95, () -> 0.10, false);
        monitor.beginTransform("mod/A", "mod");
        assertTrue(warningShown(monitor), "the first critical sample is reported");
        monitor.beginTransform("mod/B", "mod");
        assertTrue(warningShown(monitor), "and the flag stays set, so the report is not repeated");
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().startsWith("AWT-EventQueue")),
                "no dialog thread was started");
    }

    private static boolean warningShown(MemorySafetyMonitor monitor) throws Exception {
        Field shown = MemorySafetyMonitor.class.getDeclaredField("warningShown");
        shown.setAccessible(true);
        return (boolean) shown.get(monitor);
    }
}
