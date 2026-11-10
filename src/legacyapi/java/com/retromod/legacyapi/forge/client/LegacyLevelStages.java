/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Lists this copy's old world render stages with the one frame pass every copy shares; safe to call on a server. */
public final class LegacyLevelStages {

    // Each translated jar carries its own relocated classes, so the slots hold only JDK and host types.
    // Forge refuses a second pass under one name, so a protocol 2 also needs a pass id of its own.
    /** The slots' layout; a copy from a Retromod build with another layout keeps slots of its own. */
    public static final int PROTOCOL = 1;
    // A request is its name, then its arguments: listens(), stage(Integer index, Object[] frame),
    // register(String namespace), owners() and protocol().
    /** {@code List<Function<Object[], Object>>}: one endpoint per copy, in install order. */
    public static final String ENDPOINTS = "forge.levelStages.endpoints";
    /** {@code AtomicInteger}: whether the shared frame pass is not yet asked for, waiting for its event, or added. */
    public static final String PASS = "forge.levelStages.pass";
    /** {@code List<Runnable>}: what runs after every stage, as Forge 1.20.1 ended its buffer batches between stages. */
    public static final String FLUSHERS = "forge.levelStages.flushers";

    /** The stages one frame fires, in order: Forge 1.20.1's eleven, then the stages mods registered. */
    public static final int STAGES = 12;
    /** The index of the stages mods registered through RegisterStageEvent, which fire after AFTER_LEVEL. */
    public static final int REGISTERED_STAGES = 11;
    /** The index of AFTER_WEATHER, the one stage Forge 1.20.1 fired with the camera rotation in the model-view matrix. */
    public static final int AFTER_WEATHER = 9;

    // A frame: the members of RenderLevelStageEvent, built once and handed to every copy.
    public static final int LEVEL_RENDERER = 0;
    public static final int POSE_STACK = 1;
    public static final int PROJECTION = 2;
    public static final int RENDER_TICK = 3;
    public static final int PARTIAL_TICK = 4;
    public static final int CAMERA = 5;
    public static final int FRUSTUM = 6;
    public static final int MODEL_VIEW = 7;
    public static final int FRAME_SIZE = 8;

    private static boolean attached;

    private LegacyLevelStages() {}

    /** Called first in each old {@code @Mod} constructor of a jar that uses RenderLevelStageEvent: lists this copy, once. */
    public static void install() {
        LegacyClientRenderBootstrap.install();
        synchronized (LegacyLevelStages.class) {
            if (attached || !LegacyClientRenderBootstrap.isClient()) return;
            attached = true;
        }
        try {
            // The pass and the event name client classes, so a dedicated server never reaches them.
            LegacyLevelStagePass.attach();
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod did not set up the old RenderLevelStageEvent of "
                    + LegacyClientRenderBootstrap.namespaces() + ": " + failure);
        }
    }

    /** Runs {@code flush} after each stage of every frame, for whatever buffers old stage listeners fill. */
    public static void flushAfterEachStage(Runnable flush) {
        flushers().add(flush);
    }

    @SuppressWarnings("unchecked")
    public static List<Function<Object[], Object>> endpoints() {
        return LegacySharedSlots.get(ENDPOINTS, PROTOCOL, List.class, CopyOnWriteArrayList::new);
    }

    @SuppressWarnings("unchecked")
    public static List<Runnable> flushers() {
        return LegacySharedSlots.get(FLUSHERS, PROTOCOL, List.class, CopyOnWriteArrayList::new);
    }
}
