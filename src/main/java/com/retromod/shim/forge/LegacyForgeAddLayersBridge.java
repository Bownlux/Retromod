/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Points Forge 1.20.1's AddLayers getSkin, getSkins and getRenderer at rebuilt lookups on Forge 26.2 and newer. */
public final class LegacyForgeAddLayersBridge {

    static final String ADD_LAYERS = "net/minecraftforge/client/event/EntityRenderersEvent$AddLayers";
    static final String LIVING_RENDERER = "net/minecraft/client/renderer/entity/LivingEntityRenderer";
    static final String GET_SKIN_DESC = "(Ljava/lang/String;)L" + LIVING_RENDERER + ";";
    static final String GET_SKINS_DESC = "()Ljava/util/Set;";
    static final String GET_RENDERER_DESC = "(Lnet/minecraft/world/entity/EntityType;)L" + LIVING_RENDERER + ";";
    // 1.20.2 keyed the skins by PlayerSkin.Model, so only a mod built before it names them by string.
    static final String FIRST_VERSION_WITHOUT_NAMES = "1.20.2";
    public static final String REBUILT = RebuiltApiClasses.ROOT + "forge/client/event/LegacyAddLayers";

    private LegacyForgeAddLayersBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostKeysPlayersByModelType(host)) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        // Curios, Supplementaries and Sophisticated Backpacks add their player layers through these while renderers are built.
        t.registerMethodRedirect(ADD_LAYERS, "getSkin", GET_SKIN_DESC, REBUILT, "getSkin", rebuiltDesc(GET_SKIN_DESC), true);
        t.registerMethodRedirect(ADD_LAYERS, "getSkins", GET_SKINS_DESC, REBUILT, "getSkins", rebuiltDesc(GET_SKINS_DESC), true);
        t.limitRedirectToModsBefore(ADD_LAYERS, "getSkins", GET_SKINS_DESC, FIRST_VERSION_WITHOUT_NAMES);
        t.registerMethodRedirect(ADD_LAYERS, "getRenderer", GET_RENDERER_DESC, REBUILT, "getRenderer", rebuiltDesc(GET_RENDERER_DESC), true);
    }

    /** Whether a Forge host lacks getSkin(String); ForgeHostShape answers for other loaders, an unreadable host and the target version. */
    static boolean hostKeysPlayersByModelType(Function<String, ClassNode> host) {
        return ForgeHostMembers.lacksMethod(ADD_LAYERS, "getSkin", GET_SKIN_DESC, ForgeHostShape.FIRST_CHECKED_VERSION, host);
    }

    // The helper takes the event first, then the old call's arguments.
    static String rebuiltDesc(String oldDesc) {
        return "(L" + ADD_LAYERS + ";" + oldDesc.substring(1);
    }
}
