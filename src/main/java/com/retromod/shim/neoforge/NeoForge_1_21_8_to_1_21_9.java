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

    /** FMLLoader's statics on a host where 1.21.9 made it an instance; Forge mods on NeoForge take these too. */
    public static void registerLoaderStatics(RetromodTransformer transformer) {
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
        // The loader instance kept both names, so a mod built for 1.21.9 or newer keeps its own instance calls.
        transformer.limitRedirectToModsBefore(FML_LOADER, "getDist", DIST_DESC, "1.21.9");
        transformer.limitRedirectToModsBefore(FML_LOADER, "isProduction", "()Z", "1.21.9");

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
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // authlib 7 made GameProfile a record; vanilla, so every loader needs it.
        com.retromod.shim.common.LegacyGameProfileBridge.register(transformer);
        // WorldVersion.getPackVersion(PackType) became packVersion(PackType) with a major and minor number.
        com.retromod.shim.common.LegacyPackVersionBridge.register(transformer);
        // EntityRenderDispatcher stopped caching the camera orientation; the camera still has it.
        com.retromod.shim.common.LegacyRenderCallBridge.registerCameraOrientation(transformer);
        // Keyframe's constructors took Vector3fc.
        com.retromod.shim.common.JomlParameterWidening.register1_21_9(transformer);
        // Entity.getServer() is gone; the entity's level still has the server.
        com.retromod.shim.common.LegacyPlayerServerBridge.registerGetServer(transformer);
        // Style.withFont took a FontDescription; a font file is its Resource form.
        com.retromod.shim.common.LegacyStyleFontBridge.register(transformer);
        // EntityDataSerializers.COMPOUND_TAG is gone; a rebuilt serializer joins NeoForge's serializer registry.
        com.retromod.shim.common.LegacyEntityDataSerializerBridge.register(transformer);
        // Reload listeners take a shared state, so a 1.21.2 to 1.21.8 listener gets the host's reload.
        com.retromod.shim.common.LegacyReloadListenerBridge.register(transformer);

        // The Transfer API rework left IItemHandler alone. Checked against the loader jars:
        // getSlots, getStackInSlot, insertItem, extractItem, getSlotLimit and isItemValid have the
        // same signatures on 26.1 as on 1.21.1, and ItemStackHandler and ComponentItemHandler are
        // both still there, so a call site needs no repair. What did change is serialization,
        // INBTSerializable<CompoundTag> to ValueIOSerializable, which is a semantic migration and
        // not something a call redirect can express.
        // The rework did replace the capabilities that held those handlers (checked on NeoForge
        // 1.21.11), so the bridge restores the old ones beside the new. It reads the host to decide.
        LegacyHandlerCapabilityBridge.register(transformer);

        // RenderHighlightEvent was removed in favour of ExtractBlockOutlineRenderStateEvent. That
        // is a render-state rework rather than a rename, so a mod using it still needs a real port.

        // 1.21.9 turned FMLLoader from a static utility into an instance reached through
        // FMLLoader.getCurrent(), and replaced FMLEnvironment's two static fields with static
        // methods. A mod built against the old shape links fine and then dies on its first call
        // with IncompatibleClassChangeError naming a method that plainly exists (#248).

        registerLoaderStatics(transformer);

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

        // Not bridged, because 1.21.9 removed them outright rather than moving them:
        // beginModScan, completeScan, modLauncherModList, addAccessTransformer, beforeStart,
        // getLauncherInfo, launcherHandlerName, progressWindowTick, and the backgroundScanHandler
        // field. Those are loader plumbing, so a mod reaching them needs a real port.

        // The same release dropped SecureJarHandler, and with it a mod file's findResource and getSecureJar.
        LegacyModFileBridge.register(transformer);

        // KeyMapping dropped its String-category constructors for a Category record, the 3- and
        // 4-argument vanilla forms and NeoForge's conflict-context forms alike. The older
        // KeyMappingShim that handled only the 4-argument form is kept, like every old shim.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerStringCategoryKeyMappings(
            transformer);

        // The input rework moved Screen's static modifier checks onto Minecraft and made
        // InputConstants.isKeyDown take the Window. Listener calls and overrides that took
        // primitives are rebuilt by LegacyInputEventCallAdapter and LegacyGuiOverrideAdapter.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerScreenModifierKeys(transformer);
        com.retromod.shim.common.LegacyGuiDrawBridge.registerWindowHandleKeyPoll(transformer);

        // Listener.setGain(float) is gone, so the old call becomes a no-op.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerListenerSetGainNoOp(transformer);

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
            "com.retromod.shim.neoforge.embedded.KeyMappingShim",
            "com.retromod.shim.fabric.embedded.ItemSafetyShim"
        };
    }
}
