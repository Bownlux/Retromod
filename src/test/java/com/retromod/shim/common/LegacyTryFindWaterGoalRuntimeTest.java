/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt TryFindWaterGoal, run against stand-ins: it searches two blocks down to the mob's level, like the old goal. */
class LegacyTryFindWaterGoalRuntimeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String GOAL = "com/retromod/legacyapi/minecraft/world/entity/ai/goal/LegacyTryFindWaterGoal";

    @Test
    @DisplayName("A mob on a bank two blocks above the water heads for it")
    void findsWaterTwoBelow(@TempDir Path dir) throws Exception {
        assertEquals("11,62,10", wantedAfterStart(dir, "11,62,10"));
    }

    @Test
    @DisplayName("Water above the mob's level is not a target")
    void ignoresWaterAbove(@TempDir Path dir) throws Exception {
        assertNull(wantedAfterStart(dir, "11,65,10"));
    }

    /** Puts a mob at 10,64,10 on the ground with one water block, runs the goal, and returns where it was sent. */
    private static String wantedAfterStart(Path dir, String water) throws Exception {
        ClassLoader game = gameWith(BridgeHarness.compile(dir, HOST));
        Class<?> levelType = game.loadClass("net.minecraft.world.level.Level");
        Object level = levelType.getConstructor(Set.class).newInstance(Set.of(water));
        Class<?> mobType = game.loadClass("net.minecraft.world.entity.PathfinderMob");
        Object mob = mobType.getConstructor(levelType, int.class, int.class, int.class).newInstance(level, 10, 64, 10);
        Object goal = game.loadClass(GOAL.replace('/', '.')).getConstructor(mobType).newInstance(mob);

        assertTrue((Boolean) goal.getClass().getMethod("canUse").invoke(goal), "a mob on the ground outside water");
        goal.getClass().getMethod("start").invoke(goal);
        Object control = mobType.getMethod("getMoveControl").invoke(mob);
        return (String) control.getClass().getField("wanted").get(control);
    }

    /** A loader that defines the stand-ins and the rebuilt goal. */
    private static ClassLoader gameWith(Map<String, byte[]> host) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(host);
        classes.put(GOAL, Files.readAllBytes(COMPILED.resolve(GOAL + ".class")));
        return new ClassLoader(LegacyTryFindWaterGoalRuntimeTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes == null) return super.loadClass(name, resolve);
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
                }
            }
        };
    }

    private static final Map<String, String> HOST = Map.ofEntries(
            Map.entry("net/minecraft/core/Vec3i.java", """
                    package net.minecraft.core;
                    public class Vec3i {
                        private final int x, y, z;
                        public Vec3i(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
                        public int getX() { return x; }
                        public int getY() { return y; }
                        public int getZ() { return z; }
                        public String toString() { return x + "," + y + "," + z; }
                    }"""),
            Map.entry("net/minecraft/core/BlockPos.java", """
                    package net.minecraft.core;
                    public class BlockPos extends Vec3i {
                        public BlockPos(int x, int y, int z) { super(x, y, z); }
                        public BlockPos offset(int dx, int dy, int dz) { return new BlockPos(getX() + dx, getY() + dy, getZ() + dz); }
                    }"""),
            Map.entry("net/minecraft/tags/TagKey.java", """
                    package net.minecraft.tags;
                    public final class TagKey<T> {}"""),
            Map.entry("net/minecraft/tags/FluidTags.java", """
                    package net.minecraft.tags;
                    import net.minecraft.world.level.material.Fluid;
                    public final class FluidTags { public static final TagKey<Fluid> WATER = new TagKey<>(); }"""),
            Map.entry("net/minecraft/world/level/material/Fluid.java", """
                    package net.minecraft.world.level.material;
                    public class Fluid {}"""),
            Map.entry("net/minecraft/world/level/material/FluidState.java", """
                    package net.minecraft.world.level.material;
                    import net.minecraft.tags.FluidTags;
                    import net.minecraft.tags.TagKey;
                    public final class FluidState {
                        private final boolean water;
                        public FluidState(boolean water) { this.water = water; }
                        public boolean is(TagKey<Fluid> tag) { return water && tag == FluidTags.WATER; }
                    }"""),
            Map.entry("net/minecraft/world/level/Level.java", """
                    package net.minecraft.world.level;
                    import net.minecraft.core.BlockPos;
                    import net.minecraft.world.level.material.FluidState;
                    public class Level {
                        private final java.util.Set<String> water;
                        public Level(java.util.Set<String> water) { this.water = water; }
                        public FluidState getFluidState(BlockPos pos) { return new FluidState(water.contains(pos.toString())); }
                    }"""),
            Map.entry("net/minecraft/world/entity/ai/control/MoveControl.java", """
                    package net.minecraft.world.entity.ai.control;
                    public class MoveControl {
                        public String wanted;
                        public void setWantedPosition(double x, double y, double z, double speed) {
                            wanted = (int) x + "," + (int) y + "," + (int) z;
                        }
                    }"""),
            Map.entry("net/minecraft/world/entity/ai/goal/Goal.java", """
                    package net.minecraft.world.entity.ai.goal;
                    public abstract class Goal {
                        public abstract boolean canUse();
                        public void start() {}
                    }"""),
            Map.entry("net/minecraft/world/entity/Entity.java", """
                    package net.minecraft.world.entity;
                    import net.minecraft.core.BlockPos;
                    import net.minecraft.world.level.Level;
                    public class Entity {
                        private final Level level;
                        private final BlockPos pos;
                        public Entity(Level level, int x, int y, int z) { this.level = level; this.pos = new BlockPos(x, y, z); }
                        public Level level() { return level; }
                        public BlockPos blockPosition() { return pos; }
                        public boolean onGround() { return true; }
                    }"""),
            Map.entry("net/minecraft/world/entity/Mob.java", """
                    package net.minecraft.world.entity;
                    import net.minecraft.world.entity.ai.control.MoveControl;
                    import net.minecraft.world.level.Level;
                    public class Mob extends Entity {
                        private final MoveControl control = new MoveControl();
                        public Mob(Level level, int x, int y, int z) { super(level, x, y, z); }
                        public MoveControl getMoveControl() { return control; }
                    }"""),
            Map.entry("net/minecraft/world/entity/PathfinderMob.java", """
                    package net.minecraft.world.entity;
                    import net.minecraft.world.level.Level;
                    public class PathfinderMob extends Mob {
                        public PathfinderMob(Level level, int x, int y, int z) { super(level, x, y, z); }
                    }"""));
}
