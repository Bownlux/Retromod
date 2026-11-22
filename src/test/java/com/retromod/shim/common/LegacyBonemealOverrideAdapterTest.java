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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A 26.2 mod's bonemealable blocks still grow on 26.3, which added a bonemeal source to the three bonemeal methods. */
class LegacyBonemealOverrideAdapterTest {

    private static final String OLD_PERFORM = LegacyBonemealOverrideAdapter.OLD.get("performBonemeal");

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Vanilla's bone meal reaches a mod block's old overrides, directly or through an interface default")
    void vanillaReachesOldOverrides(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "26.2");
        assertEquals("rice.valid rice.success rice.grow", grow(run, "rice"));
        assertEquals("growable.valid growable.success growable.grow", grow(run, "vine"));
    }

    @Test
    @DisplayName("An old super call reaches vanilla's new method, and one to a mod base stays on the base")
    void superCallsKeepTheirTarget(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "26.2");
        assertEquals("tomato.valid crop.valid:INTERACTION crop.success:MOB crop.grow:MOB", grow(run, "tomato"));
        assertEquals("bush.valid bush.success berry.grow bush.grow", grow(run, "berry"));
    }

    @Test
    @DisplayName("A mod's own old calls pass a source to vanilla blocks and still reach its own blocks")
    void oldCallsPassASource(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "26.2");
        assertEquals("crop.valid:INTERACTION crop.success:INTERACTION crop.grow:INTERACTION",
                run.call("mod/Blocks", "fertilize", run.call("mod/Blocks", "crop")));
        assertEquals("rice.valid rice.success rice.grow", run.call("mod/Blocks", "fertilize", run.call("mod/Blocks", "rice")));
    }

    @Test
    @DisplayName("A mixin and a mod built for 26.3 get no new methods")
    void mixinsAndNewerModsAreLeftAlone(@TempDir Path dir) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Map<String, byte[]> older = transform(old, host, "26.2");
        assertFalse(declaresNew(older.get("mod/BlockMixin")), "a mixin's members merge into its target");
        assertTrue(declaresNew(older.get("mod/Rice")));
        assertFalse(declaresNew(transform(old, host, "26.3").get("mod/Rice")), "a 26.3 mod already writes the new methods");
    }

    @Test
    @DisplayName("Only a host whose bonemeal methods take a source turns the pass on; offline the target decides")
    void onlySourceTakingHostsGetThePass() {
        ClassNode newShape = new ClassNode();
        newShape.methods.add(new MethodNode(0, "performBonemeal", LegacyBonemealOverrideAdapter.withSource(OLD_PERFORM), null, null));
        ClassNode oldShape = new ClassNode();
        oldShape.methods.add(new MethodNode(0, "performBonemeal", OLD_PERFORM, null, null));
        assertTrue(LegacyBonemealOverrideAdapter.hostTakesSource(newShape));
        assertFalse(LegacyBonemealOverrideAdapter.hostTakesSource(oldShape), "1.21.2 to 26.2 hosts");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(LegacyBonemealOverrideAdapter.hostTakesSource(null));
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(LegacyBonemealOverrideAdapter.hostTakesSource(null));
    }

    private static String grow(BridgeHarness.Run run, String block) throws Throwable {
        return (String) run.call("net/minecraft/world/item/BoneMeal", "use", run.call("mod/Blocks", block));
    }

    private static BridgeHarness.Run run(Path dir, String source) throws Exception {
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Map<String, byte[]> mod = transform(BridgeHarness.compile(dir.resolve("old"), oldWorld()), host, source);
        return new BridgeHarness.Run(host, mod);
    }

    private static Map<String, byte[]> transform(Map<String, byte[]> old, Map<String, byte[]> host, String source) {
        return BridgeHarness.transform("26.3", t -> {
            LegacyBonemealOverrideAdapter.register(t, reader(host));
            t.setModSourceVersion(source);
        }, BridgeHarness.only(old, "mod/"), host);
    }

    private static boolean declaresNew(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        String desc = LegacyBonemealOverrideAdapter.withSource(OLD_PERFORM);
        return node.methods.stream().anyMatch(m -> m.name.equals("performBonemeal") && m.desc.equals(desc));
    }

    private static Function<String, ClassNode> reader(Map<String, byte[]> host) {
        return name -> {
            byte[] bytes = host.get(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        };
    }

    // 26.2's shape: three bonemeal methods without a source, and the mod written against them.
    private static Map<String, String> oldWorld() {
        Map<String, String> s = new HashMap<>(shared());
        s.put("net/minecraft/world/level/block/BonemealableBlock.java", """
                package net.minecraft.world.level.block;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.block.state.BlockState;
                public interface BonemealableBlock {
                    boolean isValidBonemealTarget(net.minecraft.world.level.LevelReader l, BlockPos p, BlockState s);
                    boolean isBonemealSuccess(net.minecraft.world.level.Level l, net.minecraft.util.RandomSource r, BlockPos p, BlockState s);
                    void performBonemeal(net.minecraft.server.level.ServerLevel l, net.minecraft.util.RandomSource r, BlockPos p, BlockState s);
                }""");
        s.put("net/minecraft/world/level/block/CropBlock.java", """
                package net.minecraft.world.level.block;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.block.state.BlockState;
                public class CropBlock extends Block implements BonemealableBlock {
                    public boolean isValidBonemealTarget(net.minecraft.world.level.LevelReader l, BlockPos p, BlockState s) { return true; }
                    public boolean isBonemealSuccess(net.minecraft.world.level.Level l, net.minecraft.util.RandomSource r, BlockPos p, BlockState s) { return true; }
                    public void performBonemeal(net.minecraft.server.level.ServerLevel l, net.minecraft.util.RandomSource r, BlockPos p, BlockState s) {}
                }""");
        mod(s);
        return s;
    }

    // 26.3's shape: each method takes the source, and vanilla's bone meal passes one.
    private static Map<String, String> newWorld() {
        Map<String, String> s = new HashMap<>(shared());
        s.put("net/minecraft/world/level/block/BonemealSource.java",
                "package net.minecraft.world.level.block; public enum BonemealSource { INTERACTION, MOB }");
        s.put("net/minecraft/world/level/block/BonemealableBlock.java", """
                package net.minecraft.world.level.block;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.block.state.BlockState;
                public interface BonemealableBlock {
                    boolean isValidBonemealTarget(net.minecraft.world.level.LevelReader l, BlockPos p, BlockState s, BonemealSource source);
                    boolean isBonemealSuccess(net.minecraft.world.level.Level l, net.minecraft.util.RandomSource r, BlockPos p, BlockState s, BonemealSource source);
                    void performBonemeal(net.minecraft.server.level.ServerLevel l, net.minecraft.util.RandomSource r, BlockPos p, BlockState s, BonemealSource source);
                }""");
        s.put("net/minecraft/world/level/block/CropBlock.java", """
                package net.minecraft.world.level.block;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraft.world.item.Trace;
                public class CropBlock extends Block implements BonemealableBlock {
                    public boolean isValidBonemealTarget(net.minecraft.world.level.LevelReader l, BlockPos p, BlockState s, BonemealSource source) { Trace.add("crop.valid:" + source); return true; }
                    public boolean isBonemealSuccess(net.minecraft.world.level.Level l, net.minecraft.util.RandomSource r, BlockPos p, BlockState s, BonemealSource source) { Trace.add("crop.success:" + source); return true; }
                    public void performBonemeal(net.minecraft.server.level.ServerLevel l, net.minecraft.util.RandomSource r, BlockPos p, BlockState s, BonemealSource source) { Trace.add("crop.grow:" + source); }
                }""");
        s.put("net/minecraft/world/item/BoneMeal.java", """
                package net.minecraft.world.item;
                import net.minecraft.world.level.block.*;
                public final class BoneMeal {
                    // A bee pollinating, which passes the mob source.
                    public static String use(Object target) {
                        BonemealableBlock block = (BonemealableBlock) target;
                        var source = BonemealSource.MOB;
                        if (block.isValidBonemealTarget(new net.minecraft.world.level.Level(), null, null, source)
                                && block.isBonemealSuccess(new net.minecraft.world.level.Level(), null, null, null, source)) {
                            block.performBonemeal(new net.minecraft.server.level.ServerLevel(), null, null, null, source);
                        }
                        return Trace.take();
                    }
                }""");
        return s;
    }

    private static Map<String, String> shared() {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/world/level/LevelReader.java", "package net.minecraft.world.level; public interface LevelReader {}");
        s.put("net/minecraft/world/level/Level.java", "package net.minecraft.world.level; public class Level implements LevelReader {}");
        s.put("net/minecraft/server/level/ServerLevel.java", "package net.minecraft.server.level; public class ServerLevel extends net.minecraft.world.level.Level {}");
        s.put("net/minecraft/util/RandomSource.java", "package net.minecraft.util; public class RandomSource {}");
        s.put("net/minecraft/core/BlockPos.java", "package net.minecraft.core; public class BlockPos {}");
        s.put("net/minecraft/world/level/block/state/BlockState.java", "package net.minecraft.world.level.block.state; public class BlockState {}");
        s.put("net/minecraft/world/level/block/Block.java", "package net.minecraft.world.level.block; public class Block {}");
        s.put("net/minecraft/world/item/Trace.java", """
                package net.minecraft.world.item;
                public final class Trace {
                    static final StringBuilder LOG = new StringBuilder();
                    public static void add(String step) { LOG.append(LOG.length() == 0 ? "" : " ").append(step); }
                    public static String take() { String s = LOG.toString(); LOG.setLength(0); return s; }
                }""");
        s.put("org/spongepowered/asm/mixin/Mixin.java", """
                package org.spongepowered.asm.mixin;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                public @interface Mixin { Class<?>[] value(); }""");
        return s;
    }

    // The mod, written against 26.2.
    private static void mod(Map<String, String> s) {
        String imports = """
                package mod;
                import net.minecraft.core.BlockPos;
                import net.minecraft.server.level.ServerLevel;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.item.Trace;
                import net.minecraft.world.level.Level;
                import net.minecraft.world.level.LevelReader;
                import net.minecraft.world.level.block.*;
                import net.minecraft.world.level.block.state.BlockState;
                """;
        s.put("mod/Rice.java", imports + """
                public class Rice extends Block implements BonemealableBlock {
                    public boolean isValidBonemealTarget(LevelReader l, BlockPos p, BlockState s) { Trace.add("rice.valid"); return true; }
                    public boolean isBonemealSuccess(Level l, RandomSource r, BlockPos p, BlockState s) { Trace.add("rice.success"); return true; }
                    public void performBonemeal(ServerLevel l, RandomSource r, BlockPos p, BlockState s) { Trace.add("rice.grow"); }
                }""");
        s.put("mod/Tomato.java", imports + """
                public class Tomato extends CropBlock {
                    public boolean isValidBonemealTarget(LevelReader l, BlockPos p, BlockState s) {
                        Trace.add("tomato.valid");
                        return super.isValidBonemealTarget(l, p, s);
                    }
                }""");
        s.put("mod/Bush.java", imports + """
                public abstract class Bush extends Block implements BonemealableBlock {
                    public boolean isValidBonemealTarget(LevelReader l, BlockPos p, BlockState s) { Trace.add("bush.valid"); return true; }
                    public boolean isBonemealSuccess(Level l, RandomSource r, BlockPos p, BlockState s) { Trace.add("bush.success"); return true; }
                    public void performBonemeal(ServerLevel l, RandomSource r, BlockPos p, BlockState s) { Trace.add("bush.grow"); }
                }""");
        s.put("mod/Berry.java", imports + """
                public class Berry extends Bush {
                    public void performBonemeal(ServerLevel l, RandomSource r, BlockPos p, BlockState s) {
                        Trace.add("berry.grow");
                        super.performBonemeal(l, r, p, s);
                    }
                }""");
        s.put("mod/Growable.java", imports + """
                public interface Growable extends BonemealableBlock {
                    default boolean isValidBonemealTarget(LevelReader l, BlockPos p, BlockState s) { Trace.add("growable.valid"); return true; }
                    default boolean isBonemealSuccess(Level l, RandomSource r, BlockPos p, BlockState s) { Trace.add("growable.success"); return true; }
                    default void performBonemeal(ServerLevel l, RandomSource r, BlockPos p, BlockState s) { Trace.add("growable.grow"); }
                }""");
        s.put("mod/Vine.java", imports + "public class Vine extends Block implements Growable {}");
        s.put("mod/BlockMixin.java", imports + """
                @org.spongepowered.asm.mixin.Mixin(CropBlock.class)
                public abstract class BlockMixin {
                    public void performBonemeal(ServerLevel l, RandomSource r, BlockPos p, BlockState s) {}
                }""");
        s.put("mod/Blocks.java", imports + """
                public final class Blocks {
                    public static Object rice() { return new Rice(); }
                    public static Object tomato() { return new Tomato(); }
                    public static Object berry() { return new Berry(); }
                    public static Object vine() { return new Vine(); }
                    public static Object crop() { return new CropBlock(); }
                    // A fertilizer item applying bone meal itself.
                    public static String fertilize(Object target) {
                        BonemealableBlock block = (BonemealableBlock) target;
                        if (block.isValidBonemealTarget(new Level(), null, null) && block.isBonemealSuccess(new Level(), null, null, null)) {
                            block.performBonemeal(new ServerLevel(), null, null, null);
                        }
                        return Trace.take();
                    }
                }""");
    }
}
