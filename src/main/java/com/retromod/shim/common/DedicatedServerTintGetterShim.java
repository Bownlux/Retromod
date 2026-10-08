/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;

/**
 * Points {@code BlockAndTintGetter} at {@code BlockAndLightGetter} on a dedicated server.
 *
 * <p>Before 26.1, {@code BlockAndTintGetter} lived in {@code net.minecraft.world.level} and every
 * {@code Level} implemented it, so common code used it as a general "level with light" type. The
 * Forge fluid API does exactly that, and Porting Lib copies it: {@code FluidType.getLightLevel},
 * {@code getDensity}, {@code canBePlacedInLevel} and others take one. 26.1 moved the interface to
 * {@code net.minecraft.client.renderer.block}, kept the light and block-state members in the new
 * common {@code BlockAndLightGetter}, and ships the tint half on the client only. On a dedicated
 * server the verifier has to load the interface to check a {@code Level} argument against it, so a
 * library's first common class that mentions it fails with
 * {@code NoClassDefFoundError: net/minecraft/client/renderer/block/BlockAndTintGetter} (#249).
 *
 * <p>The redirect applies only where the host has no client tint getter and does have the common
 * light getter, which is a 26.1 or newer dedicated server. There no client class can see the
 * rewritten signatures, and the server's levels implement the light getter, so the block-state and
 * light calls a mod makes through the old type resolve for real. A client keeps the real type,
 * because its block color and renderer interfaces still use it.
 */
public class DedicatedServerTintGetterShim implements MinecraftVersionedApiShim {

    static final String CLIENT_TINT_GETTER = "net/minecraft/client/renderer/block/BlockAndTintGetter";
    static final String LIGHT_GETTER = "net/minecraft/world/level/BlockAndLightGetter";

    @Override
    public String getShimName() {
        return "Dedicated Server BlockAndTintGetter";
    }

    @Override
    public String getSourceVersion() {
        return "1.21.11";
    }

    @Override
    public String getTargetVersion() {
        return "26.1";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        if (appliesToHost(ClassResourceInspector.exists(CLIENT_TINT_GETTER),
                ClassResourceInspector.exists(LIGHT_GETTER))) {
            transformer.registerClassRedirect(CLIENT_TINT_GETTER, LIGHT_GETTER);
        }
    }

    /**
     * Only a host that can show the light getter but not the client tint getter is a dedicated
     * server. An offline transform sees neither and is left alone.
     */
    static boolean appliesToHost(boolean hostHasClientTintGetter, boolean hostHasLightGetter) {
        return hostHasLightGetter && !hostHasClientTintGetter;
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
