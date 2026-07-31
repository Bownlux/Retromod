/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

/**
 * 1.3.0: client-environment state whose old public homes were removed in 26.x.
 *
 * <p><b>{@code Minecraft.ON_OSX}</b> (removed at 26.1; the successor {@code InputQuirks.ON_OSX} is
 * {@code private static final}, so no field redirect can reach it): {@link #isOsx()} recomputes the
 * same value from {@code os.name}, which is exactly how vanilla's {@code Util.getPlatform()} decides
 * {@code OS.OSX}. Corpus: 10 mods, 45 sites, all {@code GETSTATIC} (framebuffer flip-Y quirks and
 * Cmd-vs-Ctrl key handling), zero writes.
 *
 * <p><b>{@code Options.hideGui}</b> (removed at 26.2; the state moved to {@code Hud.isHidden}, a
 * private field with public {@code isHidden()}/{@code toggle()} and NO absolute setter): the old
 * {@code GETFIELD}/{@code PUTFIELD} become static calls here that consume the (now-useless)
 * {@code Options} receiver as an ignored argument. A write is expressed as the conditional toggle
 * {@code if (isHidden() != desired) toggle();}, the only mutation 26.2 offers.
 *
 * <p><b>Zero compile-time Minecraft dependency</b> (Retromod builds without MC, and a per-mod
 * embedded copy must load with only what the mod's module sees): Minecraft is reached reflectively,
 * public members only (no {@code setAccessible}, so JPMS named modules on NeoForge stay happy). The
 * {@code Hud} instance and its accessors are cached after first resolution ({@code Minecraft.gui}
 * and {@code Gui.hud} are both {@code public final}, so the instances are process-stable); reads on
 * per-frame HUD paths cost one cached {@code Method.invoke}.
 *
 * <p><b>Fail-safe:</b> a structural failure (dedicated server, unit test, future MC reshuffle)
 * latches and reads as {@code false} / writes as a no-op, the standard soft-fail posture. A
 * too-early call (before the client is FULLY constructed: the singleton publishes early in
 * {@code Minecraft.<init>} while {@code gui} is assigned much later, and mod init runs in between)
 * does not latch and is retried on the next call.
 */
public final class RetroClientEnv {

    private RetroClientEnv() {}

    private static final boolean OSX = computeOsx();

    // Cached Hud instance + accessors; published via the volatile `hud` write (assign methods first).
    private static volatile Object hud;
    private static volatile java.lang.reflect.Method isHiddenMethod;
    private static volatile java.lang.reflect.Method toggleMethod;
    // Latched only on a structural failure (no Minecraft class / reshuffled fields), never on
    // "client not started yet", so per-frame callers don't pay reflection for a hopeless env.
    private static volatile boolean hudUnresolvable;

    /** Replacement for {@code GETSTATIC Minecraft.ON_OSX:Z}. */
    public static boolean isOsx() {
        return OSX;
    }

    private static boolean computeOsx() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            // Vanilla Util.getPlatform() keys OSX off os.name containing "mac"; include the
            // historical spellings for safety.
            return os.contains("mac") || os.contains("darwin") || os.contains("os x");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Replacement for {@code GETFIELD Options.hideGui:Z}; the receiver arrives as an ignored arg. */
    public static boolean isHideGui(Object optionsIgnored) {
        try {
            Object h = hud();
            return h != null && (Boolean) isHiddenMethod.invoke(h);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Replacement for {@code PUTFIELD Options.hideGui:Z}: 26.2 exposes only {@code Hud.toggle()},
     * so an absolute write is the conditional toggle.
     */
    public static void setHideGui(Object optionsIgnored, boolean hidden) {
        try {
            Object h = hud();
            if (h != null && (Boolean) isHiddenMethod.invoke(h) != hidden) {
                toggleMethod.invoke(h);
            }
        } catch (Throwable t) {
            // soft-fail: the HUD visibility stays as-is
        }
    }

    // Cached GameRenderer.lightmap field (private final Lightmap on 26.x); null until resolved.
    private static volatile java.lang.reflect.Field lightmapField;
    private static volatile boolean lightmapUnresolvable;

    /**
     * Replacement for the deleted {@code GameRenderer.lightTexture()} accessor: returns the
     * renderer's {@code Lightmap} instance (the receiver arrives as arg 0 via the auto-devirtualized
     * redirect). The only old-API calls on the result ({@code turnOn/turnOffLightLayer}) are
     * neutralized, so a {@code null} fail-safe return is harmless (the receiver gets popped).
     * Resolution scans GameRenderer's declared fields for the one TYPED Lightmap (name-independent)
     * and needs {@code setAccessible} (fine on Fabric's unnamed-module runtime; on a sealed JPMS
     * module it fails closed to null).
     */
    public static Object getLightmap(Object gameRenderer) {
        if (gameRenderer == null || lightmapUnresolvable) {
            return null;
        }
        try {
            java.lang.reflect.Field f = lightmapField;
            if (f == null) {
                for (java.lang.reflect.Field cand : gameRenderer.getClass().getDeclaredFields()) {
                    if (cand.getType().getName().equals("net.minecraft.client.renderer.Lightmap")) {
                        cand.setAccessible(true);
                        lightmapField = f = cand;
                        break;
                    }
                }
                if (f == null) {
                    lightmapUnresolvable = true;
                    return null;
                }
            }
            return f.get(gameRenderer);
        } catch (Throwable t) {
            lightmapUnresolvable = true;
            return null;
        }
    }

    private static Object hud() {
        Object h = hud;
        if (h != null || hudUnresolvable) {
            return h;
        }
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            if (mc == null) {
                return null; // client not constructed yet; retry on a later call, don't latch
            }
            Object gui = mcClass.getField("gui").get(mc);
            if (gui == null) {
                // Mid-constructor window: 26.2's Minecraft.<init> publishes the singleton (insn
                // ~133) long before it assigns gui (insn ~2579), and mod init/mixins provably run
                // inside that window. Transient, not structural: retry later, don't latch.
                return null;
            }
            Object hd = gui.getClass().getField("hud").get(gui);
            if (hd == null) {
                return null; // same transient shape one hop further
            }
            java.lang.reflect.Method isHidden = hd.getClass().getMethod("isHidden");
            java.lang.reflect.Method toggle = hd.getClass().getMethod("toggle");
            isHiddenMethod = isHidden;
            toggleMethod = toggle;
            hud = hd; // publish last: readers that see hud != null also see the methods
            return hd;
        } catch (Throwable t) {
            hudUnresolvable = true;
            return null;
        }
    }
}
