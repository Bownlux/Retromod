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
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/Entity",
            "getWorld",
            "()Lnet/minecraft/world/level/Level;",
            "net/minecraft/world/entity/Entity",
            "getEntityWorld",
            "()Lnet/minecraft/world/level/Level;"
        );

        // Entity.level() is deliberately not redirected. getEntityWorld is a Yarn name: every
        // Mojang-named host from 1.21.9 through 26.2 still declares level() and has no
        // getEntityWorld(). Shims register per host, so the old level() rewrite reached every
        // transformed Forge and NeoForge mod and failed each call with NoSuchMethodError (#251).
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.forge.embedded.EntityShim"
        };
    }
}
