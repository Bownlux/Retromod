/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodTransformer.MethodKey;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Minecraft.getFrameTime() and getDeltaFrameTime(), which 1.21 removed, go to a helper that reads the host's own delta tracker getter. */
class LegacyFrameTimeBridgeTest {

    private static final String TRACKER = "()Lnet/minecraft/client/DeltaTracker;";
    private static final String INTERMEDIARY_TRACKER = "()Lnet/minecraft/class_9779;";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A client's getter decides: getDeltaTracker on 26.x, getTimer on 1.21.1, nothing where getFrameTime remains")
    void followsTheClientShape() {
        assertEquals("getDeltaTracker", mojangGetter(single("getDeltaTracker", TRACKER)));
        assertEquals("getTimer", mojangGetter(single("getTimer", TRACKER)), "Forge and NeoForge 1.21.1");
        assertNull(mojangGetter(single("getFrameTime", "()F")), "1.20.1 keeps its own method");
        assertEquals("method_60646", intermediaryGetter(intermediary("method_60646")), "a Fabric 1.21.1 client");
    }

    @Test
    @DisplayName("Offline and on a dedicated server the target decides, for the versions checked")
    void offlineFollowsTheTarget() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertEquals("getDeltaTracker", mojangGetter(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertEquals("getTimer", mojangGetter(name -> null));
        assertEquals("method_60646", intermediaryGetter(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "1.21.5";
        assertNull(mojangGetter(name -> null), "an unchecked name is read from the client at run time");
        assertNull(intermediaryGetter(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        assertNull(mojangGetter(name -> null));
    }

    @Test
    @DisplayName("Registering sends the instance call to the static helper and adds it")
    void registersTheHelper() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyFrameTimeBridge.register(transformer);
        var target = transformer.getMethodRedirects().get(new MethodKey("net/minecraft/client/Minecraft", "getFrameTime", "()F"));
        assertNotNull(target);
        assertEquals(LegacyFrameTimeBridge.MOJANG.helper(), target.owner());
        assertEquals("(Lnet/minecraft/client/Minecraft;)F", target.desc());
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyFrameTimeBridge.MOJANG.helper()));
        var delta = transformer.getMethodRedirects().get(
                new MethodKey("net/minecraft/client/Minecraft", "getDeltaFrameTime", "()F"));
        assertNotNull(delta, "Jade's overlay reads getDeltaFrameTime on the render thread");
        assertEquals(LegacyFrameTimeBridge.MOJANG.helper(), delta.owner());
        assertEquals("(Lnet/minecraft/client/Minecraft;)F", delta.desc());
    }

    @Test
    @DisplayName("On a 26.x client an old mod's getDeltaFrameTime reads the tracker's game time delta in ticks")
    void deltaTrackerClientAnswersTheTickDelta(@TempDir Path dir) throws Throwable {
        Map<String, String> old = new HashMap<>();
        old.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft getInstance() { return null; }
                    public float getDeltaFrameTime() { return 0; }
                }""");
        old.put("mod/Overlay.java", """
                package mod;
                public final class Overlay {
                    public static float fade() { return net.minecraft.client.Minecraft.getInstance().getDeltaFrameTime() * 2; }
                }""");
        Map<String, String> host = new HashMap<>();
        host.put("net/minecraft/client/DeltaTracker.java", """
                package net.minecraft.client;
                public interface DeltaTracker {
                    float getGameTimeDeltaTicks();
                    float getGameTimeDeltaPartialTick(boolean runsNormally);
                }""");
        host.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    public static Minecraft getInstance() { return INSTANCE; }
                    public DeltaTracker getDeltaTracker() {
                        return new DeltaTracker() {
                            public float getGameTimeDeltaTicks() { return 1.25f; }
                            public float getGameTimeDeltaPartialTick(boolean runsNormally) { return 0.5f; }
                        };
                    }
                }""");
        Map<String, byte[]> mod = BridgeHarness.transform("26.2", LegacyFrameTimeBridge::register,
                BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), old), "mod/"));
        BridgeHarness.Run run = new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), host), mod);
        assertEquals(2.5f, run.call("mod/Overlay", "fade"));
    }

    @Test
    @DisplayName("On a 1.21.1 client an old mod's getFrameTime reads the pause-aware partial tick through getTimer")
    void timerClientAnswersThePartialTick(@TempDir Path dir) throws Throwable {
        Map<String, String> old = new HashMap<>();
        old.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft getInstance() { return null; }
                    public float getFrameTime() { return 0; }
                }""");
        old.put("mod/Frames.java", """
                package mod;
                public final class Frames {
                    public static float now() { return net.minecraft.client.Minecraft.getInstance().getFrameTime(); }
                }""");
        Map<String, String> host = new HashMap<>();
        host.put("net/minecraft/client/DeltaTracker.java", """
                package net.minecraft.client;
                public interface DeltaTracker { float getGameTimeDeltaPartialTick(boolean runsNormally); }""");
        host.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    public static Minecraft getInstance() { return INSTANCE; }
                    public DeltaTracker getTimer() { return runsNormally -> runsNormally ? 0.75f : 1f; }
                }""");
        Map<String, byte[]> mod = BridgeHarness.transform("1.21.1", LegacyFrameTimeBridge::register,
                BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), old), "mod/"));
        BridgeHarness.Run run = new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), host), mod);
        assertEquals(0.75f, run.call("mod/Frames", "now"));
    }

    private static String mojangGetter(Function<String, ClassNode> host) {
        return LegacyFrameTimeBridge.trackerGetter(LegacyFrameTimeBridge.MOJANG, host);
    }

    private static String intermediaryGetter(Function<String, ClassNode> host) {
        return LegacyFrameTimeBridge.trackerGetter(LegacyFrameTimeBridge.INTERMEDIARY, host);
    }

    private static Function<String, ClassNode> single(String method, String desc) {
        return host(LegacyFrameTimeBridge.MOJANG.minecraft(), method, desc);
    }

    private static Function<String, ClassNode> intermediary(String method) {
        return host(LegacyFrameTimeBridge.INTERMEDIARY.minecraft(), method, INTERMEDIARY_TRACKER);
    }

    private static Function<String, ClassNode> host(String owner, String method, String desc) {
        ClassNode minecraft = new ClassNode();
        minecraft.name = owner;
        minecraft.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, method, desc, null, null));
        return Map.of(owner, minecraft)::get;
    }
}
