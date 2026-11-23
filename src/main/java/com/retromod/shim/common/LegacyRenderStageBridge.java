/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Bridges NeoForge 1.21.1's stage-filtered RenderLevelStageEvent and the removed DimensionSpecialEffects on 26.x hosts. */
public final class LegacyRenderStageBridge {

    /** Turns on the pass that moves a listener on the abstract stage event onto each 26.x stage event. */
    public static final String FLAG = "neoforge-render-stage-listeners";

    static final String EVENT = "net/neoforged/neoforge/client/event/RenderLevelStageEvent";
    static final String OLD_STAGE = EVENT + "$Stage";
    static final String EVENT_ROOT = RebuiltApiClasses.ROOT + "neoforge/client/event/";
    static final String STAGE = EVENT_ROOT + "LegacyRenderLevelStage";
    static final String CALLS = EVENT_ROOT + "LegacyRenderLevelStageCalls";
    static final String OLD_EFFECTS = "net/minecraft/client/renderer/DimensionSpecialEffects";
    static final String EFFECTS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyDimensionSpecialEffects";
    static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";

    private LegacyRenderStageBridge() {}

    /** NeoForge 26.x: the old Stage, its getters, and listeners on the now abstract event. */
    public static void registerStages(RetromodTransformer t) {
        registerStages(t, ClassResourceInspector::read);
    }

    static void registerStages(RetromodTransformer t, java.util.function.Function<String, ClassNode> host) {
        if (!stagesSplit(host.apply(EVENT), host.apply(EVENT + "$AfterOpaqueFeatures"))) return;
        RebuiltApiClasses.registerTree(t, STAGE);
        RebuiltApiClasses.registerTree(t, CALLS);
        t.registerClassRedirect(OLD_STAGE, STAGE);
        String event = "(L" + EVENT + ";)";
        call(t, "getStage", "()L" + OLD_STAGE + ";", event + "L" + STAGE + ";");
        call(t, "getPartialTick", "()Lnet/minecraft/client/DeltaTracker;", event + "Lnet/minecraft/client/DeltaTracker;");
        call(t, "getCamera", "()Lnet/minecraft/client/Camera;", event + "Lnet/minecraft/client/Camera;");
        call(t, "getProjectionMatrix", "()Lorg/joml/Matrix4f;", event + "Lorg/joml/Matrix4f;");
        call(t, "getFrustum", "()Lnet/minecraft/client/renderer/culling/Frustum;",
                event + "Lnet/minecraft/client/renderer/culling/Frustum;");
        call(t, "getModelViewMatrix", "()Lorg/joml/Matrix4f;", event + "Lorg/joml/Matrix4f;");
        call(t, "getRenderTick", "()I", event + "I");
        // A listener on the abstract event is refused by the bus; the pass gives it one listener per stage event,
        // and an addListener lambda goes through the shared helper that adds it to each.
        RebuiltApiClasses.registerTree(t, com.retromod.shim.forge.NeoForgeAbstractEventListeners.HELPER);
        t.enablePostProcess(FLAG);
    }

    /** 26.x on every loader: DimensionSpecialEffects as rebuilt values, and ClientLevel.effects() answered from them. */
    public static void registerDimensionEffects(RetromodTransformer t) {
        if (ClassResourceInspector.read(OLD_EFFECTS) != null) return;
        RebuiltApiClasses.registerTree(t, EFFECTS);
        t.registerClassRedirect(OLD_EFFECTS, EFFECTS);
        for (String nested : new String[]{"SkyType", "OverworldEffects", "NetherEffects", "EndEffects"}) {
            t.registerClassRedirect(OLD_EFFECTS + "$" + nested, EFFECTS + "$" + nested);
        }
        t.registerMethodRedirect(CLIENT_LEVEL, "effects", "()L" + OLD_EFFECTS + ";", EFFECTS, "effects",
                "(L" + CLIENT_LEVEL + ";)L" + EFFECTS + ";", true);
    }

    private static void call(RetromodTransformer t, String name, String oldDesc, String helperDesc) {
        t.registerMethodRedirect(EVENT, name, oldDesc, CALLS, name, helperDesc, true);
    }

    /** Whether the host posts one event class per stage below an abstract RenderLevelStageEvent (NeoForge 26.1 on). */
    static boolean stagesSplit(ClassNode event, ClassNode opaqueFeatures) {
        if (event == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
        return (event.access & Opcodes.ACC_ABSTRACT) != 0 && opaqueFeatures != null;
    }
}
