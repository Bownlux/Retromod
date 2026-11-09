/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

class ShimRegistryForgeMilestoneTest {
    @Test
    void forge120PatchHostUsesThe120Milestone() {
        ShimRegistry registry = new ShimRegistry();
        registry.register(shim("forge", "1.16.5", "1.17"));
        registry.register(shim("forge", "1.17", "1.20"));

        assertEquals(2, registry.findShimChain("forge", "1.16.4", "1.20.1").size());
    }

    @Test
    void fabricPatchHostStillRequiresItsRealPatchShim() {
        ShimRegistry registry = new ShimRegistry();
        registry.register(shim("fabric", "1.16.5", "1.20"));

        assertTrue(registry.findShimChain("fabric", "1.16.5", "1.20.1").isEmpty());
    }

    @Test
    void forge1201SourceResolvesWithoutInfoNoise() {
        ShimRegistry registry = new ShimRegistry();
        registry.register(shim("forge", "1.20", "1.21"));

        // The old logger wrote through java.util.logging, whose console handler may hold an
        // earlier System.err, so watch that logger directly as well as the captured stream.
        List<LogRecord> julRecords = new ArrayList<>();
        Logger julLogger = Logger.getLogger(ShimRegistry.class.getName());
        Handler julHandler = recordingHandler(julRecords);
        julLogger.addHandler(julHandler);
        List<VersionShim> chain = new ArrayList<>();
        String stderr;
        try {
            stderr = captureErr(() -> chain.addAll(registry.findShimChain("forge", "1.20.1", "1.21")));
        } finally {
            julLogger.removeHandler(julHandler);
        }

        assertEquals(1, chain.size());
        assertFalse(stderr.contains("fuzzy version matching"),
                "the 1.20.x milestone is a fixed alias, not a guess, got:\n" + stderr);
        assertFalse(stderr.contains("com.retromod.shim.ShimRegistry findShimChain"),
                "the alias line should not print a java.util.logging header, got:\n" + stderr);
        assertFalse(stderr.contains(" INFO Retromod-Shim - "),
                "the alias line belongs at DEBUG, got:\n" + stderr);
        assertTrue(julRecords.isEmpty(),
                "the alias line should not go through java.util.logging, got " + julRecords.size()
                        + " record(s)");
    }

    @Test
    void forge1201SourceReachesTheNewestHosts() {
        ShimRegistry registry = forgeShimsFromServiceLoader();

        assertFalse(registry.findShimChain("forge", "1.20.1", "26.2").isEmpty(),
                "a Forge 1.20.1 mod should have a shim chain to 26.2");
        assertFalse(registry.findShimChain("forge", "1.20.1", "26.3").isEmpty(),
                "a Forge 1.20.1 mod should have a shim chain to 26.3");
    }

    /** Every Forge version shim the build registers, as the CLI registers them. */
    static ShimRegistry forgeShimsFromServiceLoader() {
        ShimRegistry registry = new ShimRegistry();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if ("forge".equals(shim.getModLoaderType())) {
                registry.register(shim);
            }
        }
        return registry;
    }

    private static Handler recordingHandler(List<LogRecord> records) {
        return new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.INFO.intValue()) records.add(record);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
    }

    private static String captureErr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static VersionShim shim(String loader, String source, String target) {
        return new VersionShim() {
            @Override public String getShimName() { return source + " to " + target; }
            @Override public String getSourceVersion() { return source; }
            @Override public String getTargetVersion() { return target; }
            @Override public String getModLoaderType() { return loader; }
            @Override public void registerRedirects(RetromodTransformer transformer) { }
        };
    }
}
