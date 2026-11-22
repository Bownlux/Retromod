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
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.shim.common.LegacyJigsawBlockInfoBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** Code written for 26.2's JigsawBlockInfo, a block info with jigsaw fields, works on 26.3's position-and-state record. */
class LegacyJigsawBlockInfoBridgeTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("info() rebuilds the block info with the jigsaw's fields in its tag, under the block entity's keys")
    void infoRebuildsTheBlockInfo(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir);
        assertEquals("1,2,3 minecraft:jigsaw {name=dungeons:door, target=dungeons:hall, pool=dungeons:rooms, joint=aligned,"
                + " placement_priority=4, selection_priority=5}", run.call("mod/Jigsaws", "describe"));
    }

    @Test
    @DisplayName("of, withInfo and the old constructor reach parse, the two-argument withInfo and the new constructor")
    void oldFactoriesReachTheNewOnes(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir);
        assertEquals("parsed:1,2,3", run.call("mod/Jigsaws", "parsed"));
        assertEquals("7,8,9 minecraft:air dungeons:door", run.call("mod/Jigsaws", "moved"));
        assertEquals("1,2,3 minecraft:jigsaw dungeons:built", run.call("mod/Jigsaws", "built"));
    }

    @Test
    @DisplayName("Retromod's own jigsaw unwrap and canAttach bridge follow the host: info() and of before 26.3, the rebuild and parse after")
    void overloadBridgeFollowsTheHost() {
        assertTrue(calls(IsOverloadBridgeSynthetic.generate(false), "shuffledJigsawBlocks").contains(JBI + ".info" + INFO_DESC));
        assertTrue(calls(IsOverloadBridgeSynthetic.generate(true), "shuffledJigsawBlocks")
                .contains(IsOverloadBridgeSynthetic.INTERNAL + ".jigsawInfo(L" + JBI + ";)L" + SBI + ";"));
        assertTrue(calls(IsOverloadBridgeSynthetic.generate(true), "jigsawCanAttach").stream().anyMatch(c -> c.startsWith(JBI + ".parse")));
        assertTrue(calls(IsOverloadBridgeSynthetic.generate(false), "jigsawCanAttach").stream().anyMatch(c -> c.startsWith(JBI + ".of")));
    }

    @Test
    @DisplayName("The bridge registers only where the host's record lost info(); offline the target decides")
    void followsTheHost() {
        ClassNode old = new ClassNode();
        old.methods.add(new MethodNode(1, "info", INFO_DESC, null, null));
        assertFalse(hostRebuiltIt(name -> name.equals(JBI) ? old : null), "26.2");
        assertTrue(hostRebuiltIt(name -> name.equals(JBI) ? new ClassNode() : null), "26.3");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(hostRebuiltIt(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(hostRebuiltIt(name -> null));
    }

    private static List<String> calls(byte[] bytes, String method) {
        return BridgeHarness.calls(bytes, method);
    }

    private BridgeHarness.Run run(Path dir) throws Exception {
        Consumer<RetromodTransformer> register = t -> {
            register(t, name -> null);
            t.setModSourceVersion("26.2");
        };
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", register,
                BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), withShared(OLD_262)), "mod/"));
        return new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), withShared(HOST_263)), mod);
    }

    private static Map<String, String> withShared(Map<String, String> sources) {
        Map<String, String> all = new HashMap<>(SHARED);
        all.putAll(sources);
        return all;
    }

    private static final String TEMPLATE = "net/minecraft/world/level/levelgen/structure/templatesystem/";

    /** Types the same on 26.2 and 26.3. */
    private static final Map<String, String> SHARED = Map.of(
            "net/minecraft/core/BlockPos.java", """
                    package net.minecraft.core;
                    public record BlockPos(int x, int y, int z) { public String toString() { return x + "," + y + "," + z; } }""",
            "net/minecraft/world/level/block/state/BlockState.java", """
                    package net.minecraft.world.level.block.state;
                    public record BlockState(String id) { public String toString() { return id; } }""",
            "net/minecraft/resources/Identifier.java", """
                    package net.minecraft.resources;
                    public record Identifier(String id) { public String toString() { return id; } }""",
            "net/minecraft/resources/ResourceKey.java", """
                    package net.minecraft.resources;
                    public record ResourceKey<T>(Identifier identifier) {}""",
            "net/minecraft/world/level/block/entity/JigsawBlockEntity.java", """
                    package net.minecraft.world.level.block.entity;
                    public class JigsawBlockEntity {
                        public enum JointType { ROLLABLE, ALIGNED; public String getSerializedName() { return name().toLowerCase(); } }
                    }""",
            "net/minecraft/nbt/CompoundTag.java", """
                    package net.minecraft.nbt;
                    import java.util.LinkedHashMap;
                    import java.util.Map;
                    public class CompoundTag {
                        private final Map<String, Object> values = new LinkedHashMap<>();
                        public void putString(String key, String value) { values.put(key, value); }
                        public void putInt(String key, int value) { values.put(key, value); }
                        public String getString(String key) { return String.valueOf(values.get(key)); }
                        public String toString() { return values.toString(); }
                    }""");

    /** 26.2's record wraps a block info, and a mod built against it. */
    private static final Map<String, String> OLD_262 = Map.of(
            TEMPLATE + "StructureTemplate.java", """
                    package net.minecraft.world.level.levelgen.structure.templatesystem;
                    import net.minecraft.core.BlockPos;
                    import net.minecraft.nbt.CompoundTag;
                    import net.minecraft.resources.Identifier;
                    import net.minecraft.resources.ResourceKey;
                    import net.minecraft.world.level.block.entity.JigsawBlockEntity;
                    import net.minecraft.world.level.block.state.BlockState;
                    public class StructureTemplate {
                        public record StructureBlockInfo(BlockPos pos, BlockState state, CompoundTag nbt) {}
                        public record JigsawBlockInfo(StructureBlockInfo info, JigsawBlockEntity.JointType jointType,
                                Identifier name, ResourceKey<Object> pool, Identifier target, int placementPriority, int selectionPriority) {
                            public static JigsawBlockInfo of(StructureBlockInfo info) { return null; }
                            public JigsawBlockInfo withInfo(StructureBlockInfo info) { return null; }
                        }
                    }""",
            "mod/Jigsaws.java", """
                    package mod;
                    import net.minecraft.core.BlockPos;
                    import net.minecraft.nbt.CompoundTag;
                    import net.minecraft.resources.Identifier;
                    import net.minecraft.resources.ResourceKey;
                    import net.minecraft.world.level.block.entity.JigsawBlockEntity;
                    import net.minecraft.world.level.block.state.BlockState;
                    import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.JigsawBlockInfo;
                    import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
                    public class Jigsaws {
                        static StructureBlockInfo block() { return new StructureBlockInfo(new BlockPos(1, 2, 3), new BlockState("minecraft:jigsaw"), new CompoundTag()); }
                        static JigsawBlockInfo jigsaw(String name) {
                            return new JigsawBlockInfo(block(), JigsawBlockEntity.JointType.ALIGNED, new Identifier(name),
                                    new ResourceKey<>(new Identifier("dungeons:rooms")), new Identifier("dungeons:hall"), 4, 5);
                        }
                        public static String describe() {
                            StructureBlockInfo info = jigsaw("dungeons:door").info();
                            return info.pos() + " " + info.state() + " " + info.nbt();
                        }
                        public static String parsed() { return String.valueOf(JigsawBlockInfo.of(block())); }
                        public static String moved() {
                            JigsawBlockInfo moved = jigsaw("dungeons:door").withInfo(new StructureBlockInfo(new BlockPos(7, 8, 9), new BlockState("minecraft:air"), new CompoundTag()));
                            return moved.info().pos() + " " + moved.info().state() + " " + moved.name();
                        }
                        public static String built() {
                            StructureBlockInfo info = jigsaw("dungeons:built").info();
                            return info.pos() + " " + info.state() + " " + info.nbt().getString("name");
                        }
                    }""");

    /** 26.3's record: a position and a state with the jigsaw's fields, parse in place of of. */
    private static final Map<String, String> HOST_263 = Map.of(
            TEMPLATE + "StructureTemplate.java", """
                    package net.minecraft.world.level.levelgen.structure.templatesystem;
                    import net.minecraft.core.BlockPos;
                    import net.minecraft.nbt.CompoundTag;
                    import net.minecraft.resources.Identifier;
                    import net.minecraft.resources.ResourceKey;
                    import net.minecraft.world.level.block.entity.JigsawBlockEntity;
                    import net.minecraft.world.level.block.state.BlockState;
                    public class StructureTemplate {
                        public record StructureBlockInfo(BlockPos pos, BlockState state, CompoundTag nbt) {}
                        public record JigsawBlockInfo(BlockPos pos, BlockState state, JigsawBlockEntity.JointType jointType,
                                Identifier name, ResourceKey<Object> pool, Identifier target, int placementPriority, int selectionPriority) {
                            public static JigsawBlockInfo parse(StructureBlockInfo info) {
                                return new JigsawBlockInfo(info.pos(), info.state(), JigsawBlockEntity.JointType.ROLLABLE,
                                        new Identifier("minecraft:empty"), null, new Identifier("minecraft:empty"), 0, 0);
                            }
                            public JigsawBlockInfo withInfo(BlockPos pos, BlockState state) {
                                return new JigsawBlockInfo(pos, state, jointType, name, pool, target, placementPriority, selectionPriority);
                            }
                            public String toString() { return "parsed:" + pos; }
                        }
                    }""");
}
