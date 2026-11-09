/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.util.McReflect;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Points Forge 1.20.1's AddLayers getSkin, getSkins and getRenderer at rebuilt lookups on NeoForge 26.x. */
public final class LegacyNeoForgeAddLayersBridge {

    static final String NEO_ADD_LAYERS = "net/neoforged/neoforge/client/event/EntityRenderersEvent$AddLayers";
    // A Forge mod's calls name Forge's event until the Forge-to-NeoForge rename reaches them.
    static final String FORGE_ADD_LAYERS = "net/minecraftforge/client/event/EntityRenderersEvent$AddLayers";
    static final String LIVING_RENDERER = "net/minecraft/client/renderer/entity/LivingEntityRenderer";
    static final String GET_SKIN_DESC = "(Ljava/lang/String;)L" + LIVING_RENDERER + ";";
    static final String GET_SKINS_DESC = "()Ljava/util/Set;";
    static final String GET_RENDERER_DESC = "(Lnet/minecraft/world/entity/EntityType;)L" + LIVING_RENDERER + ";";
    static final String PLAYER_RENDERER_DESC = "(Lnet/minecraft/world/entity/player/PlayerModelType;)"
            + "Lnet/minecraft/client/renderer/entity/player/AvatarRenderer;";
    static final String ANCHOR = "net/neoforged/neoforge/common/NeoForge";
    // NeoForge's own getSkins() returns model types now; only a mod built before 1.20.2 expects names from it.
    static final String FIRST_VERSION_WITHOUT_NAMES = "1.20.2";
    public static final String REBUILT = RebuiltApiClasses.ROOT + "neoforge/client/event/LegacyAddLayers";

    private LegacyNeoForgeAddLayersBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!McReflect.isNeoForge() || !hostKeysPlayersByModelType(host)) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        String helper = "(L" + NEO_ADD_LAYERS + ";";
        for (String owner : List.of(NEO_ADD_LAYERS, FORGE_ADD_LAYERS)) {
            t.registerMethodRedirect(owner, "getSkin", GET_SKIN_DESC, REBUILT, "getSkin", helper + GET_SKIN_DESC.substring(1), true);
            t.registerMethodRedirect(owner, "getSkins", GET_SKINS_DESC, REBUILT, "getSkins", helper + GET_SKINS_DESC.substring(1), true);
            t.limitRedirectToModsBefore(owner, "getSkins", GET_SKINS_DESC, FIRST_VERSION_WITHOUT_NAMES);
            t.registerMethodRedirect(owner, "getRenderer", GET_RENDERER_DESC, REBUILT, "getRenderer", helper + GET_RENDERER_DESC.substring(1), true);
        }
    }

    /** Whether the NeoForge host keys player renderers by model type and has no getSkin(String); offline, the 26.1 layer stands for it. */
    static boolean hostKeysPlayersByModelType(Function<String, ClassNode> host) {
        ClassNode event = host.apply(NEO_ADD_LAYERS);
        if (event == null) return host.apply(ANCHOR) == null;
        boolean oldSkins = event.methods.stream().anyMatch(m -> m.name.equals("getSkin") && m.desc.equals(GET_SKIN_DESC));
        boolean byType = event.methods.stream().anyMatch(m -> m.name.equals("getPlayerRenderer") && m.desc.equals(PLAYER_RENDERER_DESC));
        return !oldSkins && byType;
    }
}
