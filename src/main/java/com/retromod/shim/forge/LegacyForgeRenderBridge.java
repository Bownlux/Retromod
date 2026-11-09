/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.tree.ClassNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Rebuilds Forge 1.20.1's RenderLevelStageEvent inside each old mod, on Forge hosts that draw the world through frame passes. */
public final class LegacyForgeRenderBridge {

    static final String OLD_EVENT = "net/minecraftforge/client/event/RenderLevelStageEvent";
    static final String OLD_STAGE = OLD_EVENT + "$Stage";
    static final String OLD_REGISTER_STAGE = OLD_EVENT + "$RegisterStageEvent";
    /** Forge 26.2's replacement, through which the rebuilt stages draw. */
    static final String ADD_FRAME_PASS = "net/minecraftforge/client/event/AddFramePassEvent";

    static final String ROOT = RebuiltApiClasses.ROOT + "forge/client/";
    static final String EVENT = ROOT + "event/RenderLevelStageEvent";
    static final String LEVEL_STAGES = ROOT + "LegacyLevelStages";
    static final String MATRIX = "()Lorg/joml/Matrix4f;";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    /** Each removed class an old mod names, and the class rebuilt in its place. */
    static final Map<String, String> REBUILT = rebuilt();

    private LegacyForgeRenderBridge() {}

    private static Map<String, String> rebuilt() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(OLD_EVENT, EVENT);
        names.put(OLD_STAGE, EVENT + "$Stage");
        names.put(OLD_REGISTER_STAGE, EVENT + "$RegisterStageEvent");
        return names;
    }

    /** Registers the rebuilt stage event where the Forge host has frame passes and no stage event; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // Forge 26.2 fires no stage event and lets mods add frame passes instead; a host with the event needs nothing.
        if (!ForgeHostShape.matches(List.of(ADD_FRAME_PASS), List.of(OLD_EVENT), ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            return;
        }
        RebuiltApiClasses.registerTree(t, EVENT);
        // The rebuilt classes note what they could not do through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);

        REBUILT.forEach((old, rebuilt) -> {
            t.registerClassRedirect(old, rebuilt);
            // A mod built for a host that has these names again keeps them.
            t.limitClassRedirectToModsBefore(old, ForgeHostShape.FIRST_CHECKED_VERSION);
        });
        // Forge 1.21 kept the same event but returned the model-view matrix from getPoseStack().
        t.registerMethodRedirect(OLD_EVENT, "getPoseStack", MATRIX, EVENT, "getModelViewMatrix", MATRIX);
        t.limitRedirectToModsBefore(OLD_EVENT, "getPoseStack", MATRIX, ForgeHostShape.FIRST_CHECKED_VERSION);

        // The events keep Forge 1.20.1's priority and listener order rules in Retromod's dispatch.
        LegacyEventBaseAdapter.dispatchAsLegacy(t, List.of(OLD_EVENT, OLD_REGISTER_STAGE));
        // Each jar's copy joins the one shared frame pass while its mod constructs.
        LegacyClientRenderBootstrapInjector.addPart(t, new LegacyClientRenderBootstrapInjector.Part("level-stages",
                REBUILT.keySet(), LEVEL_STAGES));
    }
}
