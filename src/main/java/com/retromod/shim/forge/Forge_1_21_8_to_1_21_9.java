/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Forge shim for 1.21.9: bridges the authlib 7 GameProfile record and the old getWorld() alias. */
public class Forge_1_21_8_to_1_21_9 implements VersionShim {
    
    @Override
    public String getShimName() {
        return "Forge 1.21.8 to 1.21.9";
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
        return "forge";
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
        // EntityDataSerializers.COMPOUND_TAG is gone; a rebuilt serializer joins the loader's serializer registry.
        com.retromod.shim.common.LegacyEntityDataSerializerBridge.register(transformer);
        // Reload listeners take a shared state, so a 1.21.2 to 1.21.8 listener gets the host's reload.
        com.retromod.shim.common.LegacyReloadListenerBridge.register(transformer);
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/Entity",
            "getWorld",
            "()Lnet/minecraft/world/level/Level;",
            "net/minecraft/world/entity/Entity",
            "getEntityWorld",
            "()Lnet/minecraft/world/level/Level;"
        );

        // 1.21.9 replaced KeyMapping's String category with a Category record, in vanilla and in
        // Forge's own conflict-context constructors.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerStringCategoryKeyMappings(
                transformer);

        // The input rework moved Screen's static modifier checks onto Minecraft and made
        // InputConstants.isKeyDown take the Window. Listener calls and overrides that took
        // primitives are rebuilt by LegacyInputEventCallAdapter and LegacyGuiOverrideAdapter.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerScreenModifierKeys(transformer);
        com.retromod.shim.common.LegacyGuiDrawBridge.registerWindowHandleKeyPoll(transformer);

        // Listener.setGain(float) is gone, so the old call becomes a no-op.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerListenerSetGainNoOp(transformer);

        // Entity.level() is deliberately not redirected. getEntityWorld is a Yarn name: every
        // Mojang-named host from 1.21.9 through 26.2 still declares level() and has no
        // getEntityWorld(). Shims register per host, so the old level() rewrite reached every
        // transformed Forge and NeoForge mod and failed each call with NoSuchMethodError (#251).
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.forge.embedded.EntityShim",
            "com.retromod.shim.fabric.embedded.ItemSafetyShim"
        };
    }
}
