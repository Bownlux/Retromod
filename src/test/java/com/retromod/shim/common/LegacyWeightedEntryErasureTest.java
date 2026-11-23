/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** A generic layer over wrapped weighted entries, as TerraBlender's region layer is, runs where the wrapper is Weighted. */
class LegacyWeightedEntryErasureTest {

    @TempDir
    Path dir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A method reference over T extends WeightedEntry takes the host's Weighted values without a failed cast")
    void layerOverWrappedEntries() throws Throwable {
        Map<String, byte[]> mod = only(compile(dir.resolve("old"), OLD), "mod/");
        Map<String, byte[]> host = compile(dir.resolve("host"), Map.of(
                "net/minecraft/util/random/Weighted.java", """
                        package net.minecraft.util.random;
                        public record Weighted<T>(T value, int weight) {}"""));
        Run bridged = new Run(host, transform("26.3", LegacyWeightedEntryBridge::register, mod));
        assertEquals(5, bridged.call("mod/BiomeLayer", "first"), "the layer reads the first wrapped value, \"lilac\"");
        assertEquals(3, bridged.call("mod/BiomeLayer", "weightOfSecond"), "a call through the interface still reads the weight");
    }

    private static final Map<String, String> OLD = Map.of(
            "net/minecraft/util/random/Weight.java", """
                    package net.minecraft.util.random;
                    public class Weight { public static Weight of(int w) { return null; } public int asInt() { return 0; } }""",
            "net/minecraft/util/random/WeightedEntry.java", """
                    package net.minecraft.util.random;
                    public interface WeightedEntry {
                        Weight getWeight();
                        static <T> Wrapper<T> wrap(T data, int weight) { return null; }
                        class Wrapper<T> implements WeightedEntry {
                            public T getData() { return null; }
                            public Weight getWeight() { return null; }
                        }
                    }""",
            "mod/WeightedLayer.java", """
                    package mod;
                    import java.util.List;
                    import net.minecraft.util.random.WeightedEntry;
                    public abstract class WeightedLayer<T extends WeightedEntry> {
                        private final List<T> entries;
                        protected WeightedLayer(List<T> entries) { this.entries = entries; }
                        public int apply() { return entries.stream().findFirst().map(this::index).orElse(-1); }
                        public int weightAt(int i) { WeightedEntry entry = entries.get(i); return entry.getWeight().asInt(); }
                        protected abstract int index(T entry);
                    }""",
            "mod/BiomeLayer.java", """
                    package mod;
                    import java.util.List;
                    import net.minecraft.util.random.WeightedEntry;
                    public class BiomeLayer extends WeightedLayer<WeightedEntry.Wrapper<String>> {
                        BiomeLayer() { super(List.of(WeightedEntry.wrap("lilac", 2), WeightedEntry.wrap("fir", 3))); }
                        @Override protected int index(WeightedEntry.Wrapper<String> entry) { return entry.getData().length(); }
                        public static int first() { return new BiomeLayer().apply(); }
                        public static int weightOfSecond() { return new BiomeLayer().weightAt(1); }
                    }""");
}
