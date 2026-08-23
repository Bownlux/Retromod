/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/**
 * Bridge for Fabric's removed {@code FabricBlockSettings} builder (deprecated 1.20.5, gone by 26.1).
 *
 * <p>It was a {@code BlockBehaviour.Properties} subclass with Yarn-named builder aliases, so a class
 * redirect {@code FabricBlockSettings -> Properties} resolves the type and every builder whose name
 * already matches {@code Properties}. A synthetic subclass can't substitute: {@code Properties}' only
 * constructor is private, behind a static {@code of()} factory.
 *
 * <p>Renamed builders (Yarn->Mojang) get a method redirect keyed on the post-class-redirect owner. For
 * the few with an MC-typed parameter we register both the intermediary and Mojang descriptor variants,
 * since the CLI path skips the intermediary->Mojang member remap.
 *
 * <p>Not yet bridged: signature-changing builders ({@code luminance(int)} -> {@code lightLevel(ToIntFunction)},
 * needs a constant-function adapter) and predicate-typed ones; a block calling those hits
 * {@code NoSuchMethodError}. The common property set is covered.
 */
public class FabricBlockSettingsShim implements AuxiliaryVersionShim {

    private static final String FBS = "net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings";
    // pre-0.50 Fabric API (~1.16), before the object-builder-v1 module
    private static final String FBS_OLD = "net/fabricmc/fabric/api/block/FabricBlockSettings";
    private static final String PROPS = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String L_PROPS = "L" + PROPS + ";";

    // MC param types: intermediary (CLI/audit) + Mojang (runtime)
    private static final String SOUND_INT = "Lnet/minecraft/class_2498;";
    private static final String SOUND_MOJ = "Lnet/minecraft/world/level/block/SoundType;";
    private static final String MAPCOLOR_INT = "Lnet/minecraft/class_3620;";
    private static final String MAPCOLOR_MOJ = "Lnet/minecraft/world/level/material/MapColor;";
    private static final String DYE_INT = "Lnet/minecraft/class_1767;";
    private static final String DYE_MOJ = "Lnet/minecraft/world/item/DyeColor;";

    @Override public String getShimName() { return "Fabric BlockSettings → BlockBehaviour.Properties"; }
    @Override public String getSourceVersion() { return "0.50.0"; }
    @Override public String getTargetVersion() { return "0.100.0"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerClassRedirect(FBS, PROPS);
        transformer.registerClassRedirect(FBS_OLD, PROPS);

        renameStatic(transformer, "create", "()" + L_PROPS, "of");

        // renamed instance builders, no MC-typed parameter
        rename(transformer, "hardness", "(F)" + L_PROPS, "destroyTime");
        rename(transformer, "resistance", "(F)" + L_PROPS, "explosionResistance");
        rename(transformer, "breakInstantly", "()" + L_PROPS, "instabreak");
        rename(transformer, "ticksRandomly", "()" + L_PROPS, "randomTicks");
        rename(transformer, "requiresTool", "()" + L_PROPS, "requiresCorrectToolForDrops");
        rename(transformer, "nonOpaque", "()" + L_PROPS, "noOcclusion");
        rename(transformer, "slipperiness", "(F)" + L_PROPS, "friction");
        rename(transformer, "velocityMultiplier", "(F)" + L_PROPS, "speedFactor");
        rename(transformer, "jumpVelocityMultiplier", "(F)" + L_PROPS, "jumpFactor");
        rename(transformer, "dropsNothing", "()" + L_PROPS, "noLootTable");
        rename(transformer, "dynamicBounds", "()" + L_PROPS, "dynamicShape");
        rename(transformer, "noBlockBreakParticles", "()" + L_PROPS, "noTerrainParticles");

        // MC-typed-parameter builders: register both intermediary and Mojang descriptors
        rename(transformer, "sounds", "(" + SOUND_INT + ")" + L_PROPS, "sound");
        rename(transformer, "sounds", "(" + SOUND_MOJ + ")" + L_PROPS, "sound");
        rename(transformer, "materialColor", "(" + MAPCOLOR_INT + ")" + L_PROPS, "mapColor");
        rename(transformer, "materialColor", "(" + MAPCOLOR_MOJ + ")" + L_PROPS, "mapColor");
        rename(transformer, "materialColor", "(" + DYE_INT + ")" + L_PROPS, "mapColor");
        rename(transformer, "materialColor", "(" + DYE_MOJ + ")" + L_PROPS, "mapColor");
    }

    // owner/return are Properties (the class redirect already rewrote FabricBlockSettings);
    // devirtualize=false keeps the INVOKEVIRTUAL on the same receiver
    private static void rename(RetromodTransformer t, String fbsName, String desc, String propsName) {
        t.registerMethodRedirect(PROPS, fbsName, desc, PROPS, propsName, desc, false);
    }

    private static void renameStatic(RetromodTransformer t, String fbsName, String desc, String propsName) {
        t.registerMethodRedirect(PROPS, fbsName, desc, PROPS, propsName, desc, false);
    }
}
