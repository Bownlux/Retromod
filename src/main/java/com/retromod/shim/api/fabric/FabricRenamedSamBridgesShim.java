/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges for Fabric API event interfaces whose 26.1 successor renamed the functional method.
 * A plain class redirect makes {@code LambdaMetafactory} throw when a mod registers a listener,
 * so each old interface gets a synthetic (built by {@link SamBridgeSynthetics}) carrying the old
 * SAM name and delegating to the new one. Gated 26.1+ (#9): the old interfaces exist on pre-26.1
 * hosts, and the synthetics' Mojang-name descriptors only resolve on 26.1.
 */
public class FabricRenamedSamBridgesShim implements VersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String GEN = "com/retromod/generated/legacyevents/";
    private static final String FAPI = "net/fabricmc/fabric/api/";

    @Override public String getShimName() { return "Fabric renamed-SAM event bridges (26.1)"; }
    @Override public String getSourceVersion() { return "0.40.0"; }
    @Override public String getTargetVersion() { return "0.145.0"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer t) {
        if (!com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            LOGGER.debug("[Retromod] renamed-SAM bridges skipped (host {} < 26.1 - old APIs still present)",
                    com.retromod.core.RetromodVersion.TARGET_MC_VERSION);
            return;
        }

        String cweOld = FAPI + "client/event/lifecycle/v1/ClientWorldEvents";
        String cweNewDot = "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents";
        String cweSynth = GEN + "ClientWorldEvents";
        t.registerSyntheticClass(cweSynth, SamBridgeSynthetics.eventHolder(cweSynth, new String[][]{
                {"AFTER_CLIENT_WORLD_CHANGE", cweNewDot, "AFTER_CLIENT_LEVEL_CHANGE"}
        }));
        t.registerSyntheticClass(cweSynth + "$AfterClientWorldChange", SamBridgeSynthetics.samInterface(
                cweSynth + "$AfterClientWorldChange",
                FAPI + "client/event/lifecycle/v1/ClientLevelEvents$AfterClientLevelChange",
                "afterWorldChange", "afterLevelChange",
                "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/ClientLevel;)V",
                null, null));
        t.registerClassRedirect(cweOld, cweSynth);
        t.registerClassRedirect(cweOld + "$AfterClientWorldChange", cweSynth + "$AfterClientWorldChange");

        String sewOld = FAPI + "entity/event/v1/ServerEntityWorldChangeEvents";
        String sewNewDot = "net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents";
        String sewSynth = GEN + "ServerEntityWorldChangeEvents";
        t.registerSyntheticClass(sewSynth, SamBridgeSynthetics.eventHolder(sewSynth, new String[][]{
                {"AFTER_ENTITY_CHANGE_WORLD", sewNewDot, "AFTER_ENTITY_CHANGE_LEVEL"},
                {"AFTER_PLAYER_CHANGE_WORLD", sewNewDot, "AFTER_PLAYER_CHANGE_LEVEL"}
        }));
        t.registerSyntheticClass(sewSynth + "$AfterEntityChange", SamBridgeSynthetics.samInterface(
                sewSynth + "$AfterEntityChange",
                FAPI + "entity/event/v1/ServerEntityLevelChangeEvents$AfterEntityChange",
                "afterChangeWorld", "afterChangeLevel",
                "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;"
                        + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerLevel;)V",
                null, null));
        t.registerSyntheticClass(sewSynth + "$AfterPlayerChange", SamBridgeSynthetics.samInterface(
                sewSynth + "$AfterPlayerChange",
                FAPI + "entity/event/v1/ServerEntityLevelChangeEvents$AfterPlayerChange",
                "afterChangeWorld", "afterChangeLevel",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerLevel;)V",
                null, null));
        // Redirect both the 1.21.x path and the older lifecycle path.
        t.registerClassRedirect(sewOld, sewSynth);
        t.registerClassRedirect(sewOld + "$AfterEntityChange", sewSynth + "$AfterEntityChange");
        t.registerClassRedirect(sewOld + "$AfterPlayerChange", sewSynth + "$AfterPlayerChange");
        String sewLifecycle = FAPI + "event/lifecycle/v1/ServerEntityWorldChangeEvents";
        t.registerClassRedirect(sewLifecycle, sewSynth);
        t.registerClassRedirect(sewLifecycle + "$AfterEntityChange", sewSynth + "$AfterEntityChange");
        t.registerClassRedirect(sewLifecycle + "$AfterPlayerChange", sewSynth + "$AfterPlayerChange");

        String lefOld = FAPI + "client/rendering/v1/LivingEntityFeatureRendererRegistrationCallback";
        String lefNew = FAPI + "client/rendering/v1/LivingEntityRenderLayerRegistrationCallback";
        String lefSynth = GEN + "LivingEntityFeatureRendererRegistrationCallback";
        t.registerSyntheticClass(lefSynth, SamBridgeSynthetics.samInterface(
                lefSynth, lefNew,
                "registerRenderers", "registerLayers",
                "(Lnet/minecraft/world/entity/EntityType;"
                        + "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;"
                        + "L" + lefNew + "$RegistrationHelper;"
                        + "Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)V",
                lefNew.replace('/', '.'), "EVENT"));
        t.registerClassRedirect(lefOld, lefSynth);
        // $RegistrationHelper kept its register SAM name, so plain redirect.
        t.registerClassRedirect(lefOld + "$RegistrationHelper", lefNew + "$RegistrationHelper");

        String disOld = FAPI + "client/rendering/v1/DrawItemStackOverlayCallback";
        String disNew = FAPI + "client/rendering/v1/ExtractItemDecorationsCallback";
        String disSynth = GEN + "DrawItemStackOverlayCallback";
        t.registerSyntheticClass(disSynth, SamBridgeSynthetics.samInterface(
                disSynth, disNew,
                "onDrawItemStackOverlay", "onExtractItemDecorations",
                "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;"
                        + "Lnet/minecraft/world/item/ItemStack;II)V",
                disNew.replace('/', '.'), "EVENT"));
        t.registerClassRedirect(disOld, disSynth);

        String ttcOld = FAPI + "client/rendering/v1/TooltipComponentCallback";
        String ttcNew = FAPI + "client/rendering/v1/ClientTooltipComponentCallback";
        String ttcSynth = GEN + "TooltipComponentCallback";
        t.registerSyntheticClass(ttcSynth, SamBridgeSynthetics.samInterface(
                ttcSynth, ttcNew,
                "getComponent", "getClientComponent",
                "(Lnet/minecraft/world/inventory/tooltip/TooltipComponent;)"
                        + "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;",
                ttcNew.replace('/', '.'), "EVENT"));
        t.registerClassRedirect(ttcOld, ttcSynth);

        // Outer holder survives; only the inner SAM was renamed.
        String sceOuter = FAPI + "event/lifecycle/v1/ServerChunkEvents";
        String sceSynth = GEN + "ServerChunkEvents$LevelTypeChange";
        t.registerSyntheticClass(sceSynth, SamBridgeSynthetics.samInterface(
                sceSynth, sceOuter + "$FullChunkStatusChange",
                "onChunkLevelTypeChange", "onFullChunkStatusChange",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;"
                        + "Lnet/minecraft/server/level/FullChunkStatus;Lnet/minecraft/server/level/FullChunkStatus;)V",
                null, null));
        t.registerClassRedirect(sceOuter + "$LevelTypeChange", sceSynth);
        t.registerFieldRedirect(sceOuter, "CHUNK_LEVEL_TYPE_CHANGE", sceOuter, "FULL_CHUNK_STATUS_CHANGE");

        String lpOld = FAPI + "registry/LandPathNodeTypesRegistry";
        String lpNew = FAPI + "registry/LandPathTypeRegistry";
        t.registerClassRedirect(lpOld, lpNew);
        t.registerClassRedirect(lpOld + "$PathNodeTypeProvider", lpNew + "$PathTypeProvider");
        // Provider SAMs renamed getPathNodeType to getPathType, so they need synthetics too.
        t.registerSyntheticClass(GEN + "LandStaticPathProvider", SamBridgeSynthetics.samInterface(
                GEN + "LandStaticPathProvider", lpNew + "$StaticPathTypeProvider",
                "getPathNodeType", "getPathType",
                "(Lnet/minecraft/world/level/block/state/BlockState;Z)"
                        + "Lnet/minecraft/world/level/pathfinder/PathType;",
                null, null));
        t.registerClassRedirect(lpOld + "$StaticPathNodeTypeProvider", GEN + "LandStaticPathProvider");
        t.registerSyntheticClass(GEN + "LandDynamicPathProvider", SamBridgeSynthetics.samInterface(
                GEN + "LandDynamicPathProvider", lpNew + "$DynamicPathTypeProvider",
                "getPathNodeType", "getPathType",
                "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;"
                        + "Lnet/minecraft/core/BlockPos;Z)Lnet/minecraft/world/level/pathfinder/PathType;",
                null, null));
        t.registerClassRedirect(lpOld + "$DynamicPathNodeTypeProvider", GEN + "LandDynamicPathProvider");
        // Static getters renamed (register/registerDynamic kept their names); keyed on the new
        // owner because class redirects run before method redirects.
        t.registerMethodRedirect(
                lpNew, "getPathNodeType",
                "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;"
                        + "Lnet/minecraft/core/BlockPos;Z)Lnet/minecraft/world/level/pathfinder/PathType;",
                lpNew, "getPathType",
                "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;"
                        + "Lnet/minecraft/core/BlockPos;Z)Lnet/minecraft/world/level/pathfinder/PathType;");
        t.registerMethodRedirect(
                lpNew, "getPathNodeTypeProvider",
                "(Lnet/minecraft/world/level/block/Block;)L" + lpNew + "$PathTypeProvider;",
                lpNew, "getPathTypeProvider",
                "(Lnet/minecraft/world/level/block/Block;)L" + lpNew + "$PathTypeProvider;");

        LOGGER.debug("Installed seven Fabric callback bridges. "
                + "An in-game verification run is still pending.");
    }
}
