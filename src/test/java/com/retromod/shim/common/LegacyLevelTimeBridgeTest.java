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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A mod's LevelTimeAccess reads answer from the environment attributes that replaced them, calls and method references alike. */
class LegacyLevelTimeBridgeTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On a 26.x host the moon phase, its brightness, the time of day and the day time come from the sky the host draws")
    void clockHostAnswersTheOldReads(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "getOverworldClockTime", "26.2");
        assertEquals(5, run.call("mod/Sky", "phase"));
        assertEquals(0.25f, run.call("mod/Sky", "brightness"));
        assertEquals(0.25f, run.call("mod/Sky", "timeOfDay"));
        assertEquals(12345L, run.call("mod/Sky", "dayTime"));
        assertEquals(5.0, run.call("mod/Sky", "phaseReference"), "GeckoLib's level::getMoonPhase method reference");
    }

    @Test
    @DisplayName("On a 1.21.11 host the day time is still getDayTime")
    void dayTimeHostReadsGetDayTime(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "getDayTime", "1.21.11");
        assertEquals(12345L, run.call("mod/Sky", "dayTime"));
        assertEquals(5, run.call("mod/Sky", "phase"));
    }

    @Test
    @DisplayName("A host that still has LevelTimeAccess, or no environment attributes, keeps the old calls")
    void olderHostsAreLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.10";
        assertFalse(LegacyLevelTimeBridge.hostReplacedTimeAccess(name -> null), "offline below 1.21.11");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertTrue(LegacyLevelTimeBridge.hostReplacedTimeAccess(name -> null), "offline from 1.21.11");
        assertFalse(LegacyLevelTimeBridge.hostReplacedTimeAccess(name -> new ClassNode()), "a host with LevelTimeAccess");
        assertFalse(LegacyLevelTimeBridge.hostReplacedTimeAccess(
                name -> name.equals(LegacyLevelTimeBridge.LEVEL) ? new ClassNode() : null), "a host without the attributes");
    }

    private static BridgeHarness.Run run(Path dir, String clockName, String target) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld(clockName));
        Function<String, ClassNode> reader = name -> {
            if (name.equals(LegacyLevelTimeBridge.TIME_ACCESS)) return null;
            byte[] bytes = host.get(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        };
        Map<String, byte[]> mod = BridgeHarness.transform(target, t -> LegacyLevelTimeBridge.register(t, reader),
                BridgeHarness.only(old, "mod/"), host);
        return new BridgeHarness.Run(host, mod);
    }

    // 1.20.1's shape: the level types inherit the time members from LevelTimeAccess.
    private static Map<String, String> oldWorld() {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/world/level/LevelTimeAccess.java", """
                package net.minecraft.world.level;
                public interface LevelTimeAccess {
                    long dayTime();
                    default float getTimeOfDay(float partialTick) { return 0f; }
                    default int getMoonPhase() { return 0; }
                    default float getMoonBrightness() { return 0f; }
                }""");
        s.put("net/minecraft/world/level/Level.java", """
                package net.minecraft.world.level;
                public abstract class Level implements LevelTimeAccess {
                    public long dayTime() { return 0L; }
                }""");
        s.put("net/minecraft/client/multiplayer/ClientLevel.java", """
                package net.minecraft.client.multiplayer;
                public class ClientLevel extends net.minecraft.world.level.Level {
                    public static ClientLevel get() { return null; }
                }""");
        s.put("mod/Sky.java", """
                package mod;
                import net.minecraft.client.multiplayer.ClientLevel;
                import java.util.function.DoubleSupplier;
                public final class Sky {
                    public static int phase() { return ClientLevel.get().getMoonPhase(); }
                    public static float brightness() { return ClientLevel.get().getMoonBrightness(); }
                    public static float timeOfDay() { return ClientLevel.get().getTimeOfDay(0.5f); }
                    public static long dayTime() { return ClientLevel.get().dayTime(); }
                    public static double phaseReference() {
                        DoubleSupplier phase = ClientLevel.get()::getMoonPhase;
                        return phase.getAsDouble();
                    }
                }""");
        return s;
    }

    // 1.21.11 and 26.x: the sky's values are environment attributes, read through the level as a LevelReader.
    private static Map<String, String> newWorld(String clockName) {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/world/level/MoonPhase.java", """
                package net.minecraft.world.level;
                public enum MoonPhase {
                    FULL_MOON, WANING_GIBBOUS, THIRD_QUARTER, WANING_CRESCENT, NEW_MOON, WAXING_CRESCENT, FIRST_QUARTER, WAXING_GIBBOUS;
                    public int index() { return ordinal(); }
                }""");
        s.put("net/minecraft/world/attribute/EnvironmentAttribute.java", """
                package net.minecraft.world.attribute;
                public class EnvironmentAttribute<Value> {}""");
        s.put("net/minecraft/world/attribute/EnvironmentAttributes.java", """
                package net.minecraft.world.attribute;
                public final class EnvironmentAttributes {
                    public static final EnvironmentAttribute<Float> SUN_ANGLE = new EnvironmentAttribute<>();
                    public static final EnvironmentAttribute<net.minecraft.world.level.MoonPhase> MOON_PHASE = new EnvironmentAttribute<>();
                }""");
        s.put("net/minecraft/world/attribute/EnvironmentAttributeReader.java", """
                package net.minecraft.world.attribute;
                public interface EnvironmentAttributeReader {
                    <Value> Value getDimensionValue(EnvironmentAttribute<Value> attribute);
                }""");
        s.put("net/minecraft/world/level/dimension/DimensionType.java", """
                package net.minecraft.world.level.dimension;
                public final class DimensionType {
                    public static final float[] MOON_BRIGHTNESS_PER_PHASE = {1f, 0.75f, 0.5f, 0.25f, 0f, 0.25f, 0.5f, 0.75f};
                }""");
        s.put("net/minecraft/world/level/LevelReader.java", """
                package net.minecraft.world.level;
                public interface LevelReader {
                    net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes();
                }""");
        s.put("net/minecraft/world/level/Level.java", """
                package net.minecraft.world.level;
                import net.minecraft.world.attribute.*;
                public abstract class Level implements LevelReader {
                    public EnvironmentAttributeReader environmentAttributes() {
                        return new EnvironmentAttributeReader() {
                            @SuppressWarnings("unchecked")
                            public <Value> Value getDimensionValue(EnvironmentAttribute<Value> attribute) {
                                if (attribute == EnvironmentAttributes.MOON_PHASE) return (Value) MoonPhase.WAXING_CRESCENT;
                                return (Value) Float.valueOf(90f);
                            }
                        };
                    }
                    public long CLOCK() { return 12345L; }
                }""".replace("CLOCK", clockName));
        s.put("net/minecraft/client/multiplayer/ClientLevel.java", """
                package net.minecraft.client.multiplayer;
                public class ClientLevel extends net.minecraft.world.level.Level {
                    private static final ClientLevel LEVEL = new ClientLevel();
                    public static ClientLevel get() { return LEVEL; }
                }""");
        return s;
    }
}
