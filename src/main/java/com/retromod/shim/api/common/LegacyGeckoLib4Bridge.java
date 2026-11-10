/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Registers the rebuilt textures and queries GeckoLib 4 needs to run translated on Minecraft 1.21.11 and newer. */
public final class LegacyGeckoLib4Bridge {

    public static final String TEXTURES = RebuiltApiClasses.ROOT + "geckolib/LegacyGeoTextures";
    public static final String QUERIES = RebuiltApiClasses.ROOT + "geckolib/LegacyGeoQueries";
    public static final String FLAG = "geckolib4.textures";
    public static final String OUTLINES_GONE_FLAG = "geckolib4.outlines-gone";
    static final String OUTLINE_GENERATOR = "net/minecraft/client/renderer/OutlineBufferSource$EntityOutlineGenerator";
    static final String FIRST_HOST_WITHOUT_OUTLINE_BUFFERS = "26.2";
    static final String RENDER_TYPES = "net/minecraft/client/renderer/rendertype/RenderTypes";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String FIRST_HOST = "1.21.11";

    private LegacyGeckoLib4Bridge() {}

    /** Mojang names: Forge and NeoForge from 1.21.11, and Fabric from 26.1. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    /** The same, reading the host's classes through {@code host}. */
    public static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostHasRenderTypes(host)) return;
        // The adapter rewrites GeckoLib 4's own classes to call these, and runs only where they are registered.
        RebuiltApiClasses.registerTree(t, TEXTURES);
        RebuiltApiClasses.registerTree(t, QUERIES);
        t.enablePostProcess(FLAG);
        // 26.2 removed the outline and paired buffers GeckoLib checks for, and the outline buffer source with them.
        if (hostLostOutlineBuffers(host)) t.enablePostProcess(OUTLINES_GONE_FLAG);
    }

    static boolean hostLostOutlineBuffers(Function<String, ClassNode> host) {
        if (host.apply(MINECRAFT) == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST_WITHOUT_OUTLINE_BUFFERS) >= 0;
        }
        return host.apply(OUTLINE_GENERATOR) == null;
    }

    /** The rebuilt textures draw through RenderTypes, which 1.21.11 introduced; offline the target decides. */
    static boolean hostHasRenderTypes(Function<String, ClassNode> host) {
        if (host.apply(MINECRAFT) == null && host.apply(RENDER_TYPES) == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) >= 0;
        }
        return host.apply(RENDER_TYPES) != null;
    }
}
