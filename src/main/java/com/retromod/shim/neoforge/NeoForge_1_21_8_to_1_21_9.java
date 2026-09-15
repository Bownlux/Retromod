/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 * 
 * NeoForge 1.21.9 changes:
 * https://neoforged.net/news/21.9release/
 * https://neoforged.net/news/21.9-transfer-rework/
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/**
 * Shim for NeoForge 1.21.8 mods on 1.21.9+: the FMLLoader static-to-instance move,
 * and KeyMapping category records.
 */
public class NeoForge_1_21_8_to_1_21_9 implements VersionShim {
    
    @Override
    public String getShimName() {
        return "NeoForge 1.21.8 to 1.21.9";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.21.8";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.9";
    }
    
    @Override
    public String getModLoaderType() {
        return "neoforge";
    }
    
    private static final String FML_LOADER = "net/neoforged/fml/loading/FMLLoader";
    private static final String FML_ENVIRONMENT = "net/neoforged/fml/loading/FMLEnvironment";
    private static final String DIST_DESC = "()Lnet/neoforged/api/distmarker/Dist;";

    /**
     * Rewrites one old {@code FMLLoader} static into {@code FMLLoader.getCurrent().newName()}.
     *
     * <p>Emitting {@code getCurrent()} unconditionally is safe here because a shim only registers
     * when its target version is at or below the host, so the accessor this leans on exists
     * wherever these redirects can apply.
     */
    private static void registerLoaderInstanceCall(
            RetromodTransformer transformer, String oldName, String desc, String newName) {
        transformer.registerSingletonStaticRedirect(
            FML_LOADER, oldName, desc,
            FML_LOADER, "getCurrent", "()L" + FML_LOADER + ";",
            newName, desc
        );
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {

        // The Transfer API rework left IItemHandler alone. Checked against the loader jars:
        // getSlots, getStackInSlot, insertItem, extractItem, getSlotLimit and isItemValid have the
        // same signatures on 26.1 as on 1.21.1, and ItemStackHandler and ComponentItemHandler are
        // both still there, so a call site needs no repair. What did change is serialization,
        // INBTSerializable<CompoundTag> to ValueIOSerializable, which is a semantic migration and
        // not something a call redirect can express.

        // RenderHighlightEvent was removed in favour of ExtractBlockOutlineRenderStateEvent. That
        // is a render-state rework rather than a rename, so a mod using it still needs a real port.

        // 1.21.9 turned FMLLoader from a static utility into an instance reached through
        // FMLLoader.getCurrent(), and replaced FMLEnvironment's two static fields with static
        // methods. A mod built against the old shape links fine and then dies on its first call
        // with IncompatibleClassChangeError naming a method that plainly exists (#248).

        // Dist and the production flag kept an exact static form on FMLEnvironment, whose bodies
        // are literally FMLLoader.getCurrent().getDist() and .isProduction(). A static-to-static
        // redirect is the whole repair for these two, and it stays a direct call, which matters
        // because a side check is the kind of thing a mod runs every tick.
        transformer.registerMethodRedirect(
            FML_LOADER, "getDist", DIST_DESC,
            FML_ENVIRONMENT, "getDist", DIST_DESC
        );
        transformer.registerMethodRedirect(
            FML_LOADER, "isProduction", "()Z",
            FML_ENVIRONMENT, "isProduction", "()Z"
        );

        // The same two on FMLEnvironment were public static final fields, so a read has to become
        // a call. Both were final, so no mod can hold a write for the rewrite to get wrong.
        transformer.registerFieldRedirect(
            FML_ENVIRONMENT, "dist", "Lnet/neoforged/api/distmarker/Dist;",
            FML_ENVIRONMENT, "getDist", DIST_DESC
        );
        transformer.registerFieldRedirect(
            FML_ENVIRONMENT, "production", "Z",
            FML_ENVIRONMENT, "isProduction", "()Z"
        );

        // The rest gained no static form, so each call has to pick up the loader instance first.
        // Names and return types survived except the two NeoForge renamed on the way.
        registerLoaderInstanceCall(transformer, "getLoadingModList",
            "()Lnet/neoforged/fml/loading/LoadingModList;", "getLoadingModList");
        registerLoaderInstanceCall(transformer, "getGamePath",
            "()Ljava/nio/file/Path;", "getGameDir");
        registerLoaderInstanceCall(transformer, "versionInfo",
            "()Lnet/neoforged/fml/loading/VersionInfo;", "getVersionInfo");
        registerLoaderInstanceCall(transformer, "getGameLayer",
            "()Ljava/lang/ModuleLayer;", "getGameLayer");
        registerLoaderInstanceCall(transformer, "getBindings",
            "()Lnet/neoforged/fml/IBindingsProvider;", "getBindings");

        // Not bridged, because 1.21.9 removed them outright rather than moving them:
        // beginModScan, completeScan, modLauncherModList, addAccessTransformer, beforeStart,
        // getLauncherInfo, launcherHandlerName, progressWindowTick, and the backgroundScanHandler
        // field. Those are loader plumbing, so a mod reaching them needs a real port.

        // KeyMapping dropped its String-category constructor for a Category record;
        // the shim builds the record from the old string.
        transformer.registerConstructorRedirect(
            "net/minecraft/client/KeyMapping",
            "(Ljava/lang/String;Lcom/mojang/blaze3d/platform/InputConstants$Type;ILjava/lang/String;)V",
            "com/retromod/shim/neoforge/embedded/KeyMappingShim", "create",
            "(Ljava/lang/String;Ljava/lang/Object;ILjava/lang/String;)Ljava/lang/Object;"
        );

        // Entity.getWorld() -> getEntityWorld().
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/Entity", "getWorld", "()Lnet/minecraft/world/level/Level;",
            "net/minecraft/world/entity/Entity", "getEntityWorld", "()Lnet/minecraft/world/level/Level;"
        );
        
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/LivingEntity", "getWorld", "()Lnet/minecraft/world/level/Level;",
            "net/minecraft/world/entity/LivingEntity", "getEntityWorld", "()Lnet/minecraft/world/level/Level;"
        );
        
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/player/Player", "getWorld", "()Lnet/minecraft/world/level/Level;",
            "net/minecraft/world/entity/player/Player", "getEntityWorld", "()Lnet/minecraft/world/level/Level;"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.neoforge.embedded.KeyMappingShim"
        };
    }
}
