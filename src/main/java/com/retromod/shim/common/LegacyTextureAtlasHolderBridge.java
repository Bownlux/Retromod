/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Rebuilds TextureAtlasHolder, the base of a mod's own sprite atlas such as JEI's GUI sprites, which 1.21.11 and 26.x lack. */
public final class LegacyTextureAtlasHolderBridge {

    static final String OLD = "net/minecraft/client/resources/TextureAtlasHolder";
    public static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/client/resources/LegacyTextureAtlasHolder";
    static final String SPRITE_LOADER = "net/minecraft/client/renderer/texture/SpriteLoader";
    static final String LOAD_AND_STITCH_DESC = "(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/Identifier;"
            + "ILjava/util/concurrent/Executor;Ljava/util/Set;)Ljava/util/concurrent/CompletableFuture;";

    private LegacyTextureAtlasHolderBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostStitchesFromCode(host)) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        // The rebuilt base keeps the old constructors and getSprite, so a mod's subclass links unchanged.
        t.registerClassRedirect(OLD, REBUILT);
    }

    /** Whether the host lost the class but still lets code stitch an atlas with the sprite loader. */
    static boolean hostStitchesFromCode(Function<String, ClassNode> host) {
        ClassNode loader = host.apply(SPRITE_LOADER);
        // A dedicated server or an offline run without a client jar reads neither; the 1.21.11 or 26.1 layer that calls this stands for the host.
        if (loader == null) return host.apply(OLD) == null;
        if (host.apply(OLD) != null) return false;
        return loader.methods.stream().anyMatch(m -> m.name.equals("loadAndStitch") && m.desc.equals(LOAD_AND_STITCH_DESC));
    }
}
