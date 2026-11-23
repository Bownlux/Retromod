/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.FabricRegistryIdSynthetic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** On a 26.x Fabric host, a block a mod builds into its own registration helper gets its id, as on NeoForge. */
class FabricSharedIdStampersTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("register(consumer, new Block(props), \"blood\") is wrapped in the Fabric bridge's enter and leave")
    void helperRegistrationGetsItsId(@TempDir Path dir) throws Exception {
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(dir, SOURCES), "mod/");
        Map<String, byte[]> out = BridgeHarness.transform("26.3", t -> {
            t.registerSyntheticClass(FabricRegistryIdSynthetic.INTERNAL, FabricRegistryIdSynthetic.generate());
            // Any registered rule, as a real Fabric 26.x host always has, so the transform runs its passes.
            t.registerClassRedirect("old/Unused", "new/Unused");
        }, mod);
        var calls = BridgeHarness.calls(out.get("mod/ModBlocks"), "registerBlocks");
        assertTrue(calls.stream().anyMatch(c -> c.startsWith(FabricRegistryIdSynthetic.INTERNAL + ".enter")), calls.toString());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith(FabricRegistryIdSynthetic.INTERNAL + ".leave")), calls.toString());
    }

    private static final Map<String, String> SOURCES = Map.of(
            "net/minecraft/resources/Identifier.java", """
                    package net.minecraft.resources;
                    public record Identifier(String namespace, String path) {
                        public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                    }""",
            "net/minecraft/world/level/block/state/BlockBehaviour.java", """
                    package net.minecraft.world.level.block.state;
                    public class BlockBehaviour {
                        public static class Properties { public static Properties of() { return new Properties(); } }
                    }""",
            "net/minecraft/world/level/block/Block.java", """
                    package net.minecraft.world.level.block;
                    import net.minecraft.world.level.block.state.BlockBehaviour;
                    public class Block { public Block(BlockBehaviour.Properties properties) {} }""",
            "mod/ModBlocks.java", """
                    package mod;
                    import java.util.function.BiConsumer;
                    import net.minecraft.resources.Identifier;
                    import net.minecraft.world.level.block.Block;
                    import net.minecraft.world.level.block.state.BlockBehaviour;
                    public class ModBlocks {
                        public static void registerBlocks(BiConsumer<Identifier, Block> consumer) {
                            register(consumer, new Block(BlockBehaviour.Properties.of()), "blood");
                        }
                        static Block register(BiConsumer<Identifier, Block> consumer, Block block, String name) {
                            consumer.accept(Identifier.fromNamespaceAndPath("bop", name), block);
                            return block;
                        }
                    }""");
}
