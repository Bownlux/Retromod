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
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Old mods that read and fill the axe, shovel and hoe tables reach 26.3's block transformers through rebuilt tables. */
class LegacyToolTablesBridgeTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith("retromod.shared.minecraft.legacyToolTables."));
    }

    @Test
    @DisplayName("GlitchCore's copy-then-put of STRIPPABLES and FLATTENABLES registers stripping and flattening with Fabric API")
    void putsReachFabricApi(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, FABRIC_HOST);
        run.call("mod/Compat", "setup");
        assertEquals("[strip oak_log->stripped_oak_log, flatten dirt->state(dirt_path)]",
                run.call("net/fabricmc/fabric/api/item/v1/BlockTransformerHelper", "calls").toString());
    }

    @Test
    @DisplayName("On NeoForge the entries go into its transformer table, and back in after each data map reload")
    void putsReachNeoForgeAndSurviveReloads(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, NEOFORGE_HOST);
        run.call("mod/Compat", "setup");
        assertEquals("{axe=[strip oak_log->stripped_oak_log], shovel=[flatten dirt->dirt_path]}",
                run.call("net/neoforged/neoforge/common/DataMapHooks", "table").toString());
        run.call("net/neoforged/neoforge/common/DataMapHooks", "reload");
        assertEquals("{axe=[strip oak_log->stripped_oak_log], shovel=[flatten dirt->dirt_path]}",
                run.call("net/neoforged/neoforge/common/DataMapHooks", "table").toString(),
                "the refill cleared the table, and the listener put the old entries back");
    }

    @Test
    @DisplayName("Old reads see what old mods added, and the hoe helpers keep the state for the host")
    void readsAndHoeHelpers(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, FABRIC_HOST);
        run.call("mod/Compat", "setup");
        assertEquals("stripped_oak_log", run.call("mod/Compat", "stripped").toString());
        run.call("mod/Compat", "till");
        assertEquals("[strip oak_log->stripped_oak_log, flatten dirt->state(dirt_path), till grass->state(farmland)]",
                run.call("net/fabricmc/fabric/api/item/v1/BlockTransformerHelper", "calls").toString());
    }

    @Test
    @DisplayName("The tables register only where the host has no AxeItem; offline the target decides")
    void followsTheHost() {
        assertFalse(LegacyToolTablesBridge.hostRemovedThem(new ClassNode()), "26.2 still has AxeItem");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(LegacyToolTablesBridge.hostRemovedThem(null));
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(LegacyToolTablesBridge.hostRemovedThem(null));
    }

    private BridgeHarness.Run run(Path dir, Map<String, String> loader) throws Exception {
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", t -> {
            t.setModSourceVersion("1.21.1");
            LegacyToolTablesBridge.register(t, name -> null);
        }, BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), OLD), "mod/"));
        Map<String, String> host = new HashMap<>(SHARED_HOST);
        host.putAll(loader);
        return new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), host), mod);
    }

    /** Types both hosts share with 26.2 in the members the tables touch. */
    private static final Map<String, String> SHARED_HOST = Map.of(
            "net/minecraft/world/level/block/Block.java", """
                    package net.minecraft.world.level.block;
                    import net.minecraft.world.level.block.state.BlockState;
                    public class Block implements net.minecraft.world.level.ItemLike {
                        private final String name;
                        public Block(String name) { this.name = name; }
                        public final BlockState defaultBlockState() { return new BlockState(this); }
                        public final BlockState withPropertiesOf(BlockState state) { return new BlockState(this); }
                        @Override public String toString() { return name; }
                    }""",
            "net/minecraft/world/level/ItemLike.java", """
                    package net.minecraft.world.level;
                    public interface ItemLike {}""",
            "net/minecraft/world/level/block/state/BlockState.java", """
                    package net.minecraft.world.level.block.state;
                    import net.minecraft.world.level.block.Block;
                    public class BlockState {
                        private final Block block;
                        public BlockState(Block block) { this.block = block; }
                        public Block getBlock() { return block; }
                        @Override public String toString() { return "state(" + block + ")"; }
                    }""",
            "net/minecraft/world/item/context/UseOnContext.java", """
                    package net.minecraft.world.item.context;
                    public class UseOnContext {}""",
            "com/mojang/datafixers/util/Pair.java", """
                    package com.mojang.datafixers.util;
                    public record Pair<F, S>(F first, S second) {
                        public static <F, S> Pair<F, S> of(F first, S second) { return new Pair<>(first, second); }
                        public S getSecond() { return second; }
                    }""");

    /** Fabric API 26.3's helper, recording what it is asked to register. */
    private static final Map<String, String> FABRIC_HOST = Map.of(
            "net/fabricmc/fabric/api/item/v1/BlockTransformerHelper.java", """
                    package net.fabricmc.fabric.api.item.v1;
                    import java.util.ArrayList;
                    import java.util.List;
                    import net.minecraft.world.level.block.Block;
                    import net.minecraft.world.level.block.state.BlockState;
                    public final class BlockTransformerHelper {
                        private static final List<String> CALLS = new ArrayList<>();
                        public static List<String> calls() { return CALLS; }
                        public static void registerStripping(Block input, Block output) { CALLS.add("strip " + input + "->" + output); }
                        public static void registerFlattening(Block input, BlockState output) { CALLS.add("flatten " + input + "->" + output); }
                        public static void registerTilling(Block input, BlockState output) { CALLS.add("till " + input + "->" + output); }
                    }""");

    /** NeoForge 26.3's transformer table, its data map factory, and an event bus that runs listeners on demand. */
    private static final Map<String, String> NEOFORGE_HOST = Map.of(
            "net/neoforged/neoforge/common/DataMapHooks.java", """
                    package net.neoforged.neoforge.common;
                    import java.util.List;
                    import java.util.Map;
                    import java.util.TreeMap;
                    public class DataMapHooks {
                        private static final Map<Object, List<Object>> DATAMAP_BLOCK_TRANSFORMERS = new TreeMap<>();
                        public static Object table() { return DATAMAP_BLOCK_TRANSFORMERS; }
                        public static void reload() {
                            DATAMAP_BLOCK_TRANSFORMERS.clear();
                            NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent());
                        }
                    }""",
            "net/neoforged/neoforge/common/NeoForge.java", """
                    package net.neoforged.neoforge.common;
                    public class NeoForge {
                        public static final net.neoforged.bus.api.IEventBus EVENT_BUS = new net.neoforged.bus.api.IEventBus();
                    }""",
            "net/neoforged/bus/api/EventPriority.java", """
                    package net.neoforged.bus.api;
                    public enum EventPriority { HIGHEST, NORMAL, LOWEST }""",
            "net/neoforged/bus/api/IEventBus.java", """
                    package net.neoforged.bus.api;
                    import java.util.ArrayList;
                    import java.util.List;
                    import java.util.function.Consumer;
                    public class IEventBus {
                        private final List<Consumer<Object>> listeners = new ArrayList<>();
                        @SuppressWarnings("unchecked")
                        public <T> void addListener(EventPriority priority, Class<T> event, Consumer<T> listener) {
                            listeners.add((Consumer<Object>) listener);
                        }
                        public void post(Object event) { listeners.forEach(listener -> listener.accept(event)); }
                    }""",
            "net/neoforged/neoforge/registries/datamaps/DataMapsUpdatedEvent.java", """
                    package net.neoforged.neoforge.registries.datamaps;
                    public class DataMapsUpdatedEvent {}""",
            "net/neoforged/neoforge/registries/datamaps/builtin/Transformable.java", """
                    package net.neoforged.neoforge.registries.datamaps.builtin;
                    import java.util.Map;
                    import net.minecraft.world.level.block.Block;
                    public record Transformable(Map<String, String> transformers) {
                        public static Transformable stripping(Block input, Block output) { return new Transformable(Map.of("axe", "strip " + input + "->" + output)); }
                        public static Transformable flattening(Block input, Block output) { return new Transformable(Map.of("shovel", "flatten " + input + "->" + output)); }
                        public static Transformable tilling(Block input, Block output) { return new Transformable(Map.of("hoe", "till " + input + "->" + output)); }
                    }""");

    /** 26.2's tool items and a mod written like GlitchCore's BlockHelper. */
    private static final Map<String, String> OLD = Map.ofEntries(
            Map.entry("net/minecraft/world/level/block/Block.java", SHARED_HOST.get("net/minecraft/world/level/block/Block.java")),
            Map.entry("net/minecraft/world/level/ItemLike.java", SHARED_HOST.get("net/minecraft/world/level/ItemLike.java")),
            Map.entry("net/minecraft/world/level/block/state/BlockState.java",
                    SHARED_HOST.get("net/minecraft/world/level/block/state/BlockState.java")),
            Map.entry("net/minecraft/world/item/context/UseOnContext.java",
                    SHARED_HOST.get("net/minecraft/world/item/context/UseOnContext.java")),
            Map.entry("com/mojang/datafixers/util/Pair.java", SHARED_HOST.get("com/mojang/datafixers/util/Pair.java")),
            Map.entry("net/minecraft/world/item/AxeItem.java", """
                    package net.minecraft.world.item;
                    import java.util.Map;
                    import net.minecraft.world.level.block.Block;
                    import net.minecraft.world.level.block.state.BlockState;
                    public class AxeItem {
                        public static Map<Block, Block> STRIPPABLES = Map.of();
                        public static BlockState getAxeStrippingState(BlockState state) { return null; }
                    }"""),
            Map.entry("net/minecraft/world/item/ShovelItem.java", """
                    package net.minecraft.world.item;
                    import java.util.Map;
                    import net.minecraft.world.level.block.Block;
                    import net.minecraft.world.level.block.state.BlockState;
                    public class ShovelItem { public static Map<Block, BlockState> FLATTENABLES = Map.of(); }"""),
            Map.entry("net/minecraft/world/item/HoeItem.java", """
                    package net.minecraft.world.item;
                    import com.mojang.datafixers.util.Pair;
                    import java.util.Map;
                    import java.util.function.Consumer;
                    import java.util.function.Predicate;
                    import net.minecraft.world.item.context.UseOnContext;
                    import net.minecraft.world.level.block.Block;
                    import net.minecraft.world.level.block.state.BlockState;
                    public class HoeItem {
                        public static Map<Block, Pair<Predicate<UseOnContext>, Consumer<UseOnContext>>> TILLABLES = Map.of();
                        public static Consumer<UseOnContext> changeIntoState(BlockState state) { return null; }
                        public static boolean onlyIfAirAbove(UseOnContext context) { return true; }
                    }"""),
            Map.entry("mod/Compat.java", """
                    package mod;
                    import com.mojang.datafixers.util.Pair;
                    import java.util.HashMap;
                    import net.minecraft.world.item.AxeItem;
                    import net.minecraft.world.item.HoeItem;
                    import net.minecraft.world.item.ShovelItem;
                    import net.minecraft.world.level.block.Block;
                    public class Compat {
                        static final Block LOG = new Block("oak_log");
                        public static void setup() {
                            AxeItem.STRIPPABLES = new HashMap<>(AxeItem.STRIPPABLES);
                            AxeItem.STRIPPABLES.put(LOG, new Block("stripped_oak_log"));
                            ShovelItem.FLATTENABLES = new HashMap<>(ShovelItem.FLATTENABLES);
                            ShovelItem.FLATTENABLES.put(new Block("dirt"), new Block("dirt_path").defaultBlockState());
                        }
                        public static Object stripped() { return AxeItem.getAxeStrippingState(LOG.defaultBlockState()).getBlock(); }
                        public static void till() {
                            HoeItem.TILLABLES.put(new Block("grass"),
                                    Pair.of(HoeItem::onlyIfAirAbove, HoeItem.changeIntoState(new Block("farmland").defaultBlockState())));
                        }
                    }"""));
}
