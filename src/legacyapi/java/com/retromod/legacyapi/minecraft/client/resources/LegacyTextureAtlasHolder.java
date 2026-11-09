/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.resources;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/** Minecraft 1.20.1's TextureAtlasHolder: a mod's own sprite atlas, stitched on every resource reload, which 1.21.11 and 26.x lack. */
public class LegacyTextureAtlasHolder implements PreparableReloadListener, AutoCloseable {

    private static final System.Logger LOG = System.getLogger("Retromod");

    private final TextureAtlas textureAtlas;
    private final Identifier atlasInfoLocation;
    private final Set<MetadataSectionType<?>> metadataSections;

    /** {@code atlasInfoLocation} names {@code atlases/<path>.json}, which lists the atlas's sprite sources. */
    public LegacyTextureAtlasHolder(TextureManager textureManager, Identifier atlasLocation, Identifier atlasInfoLocation) {
        this(textureManager, atlasLocation, atlasInfoLocation, Set.of());
    }

    /** The 1.20.2 and later form, which also names the sprite metadata sections the atlas reads. */
    public LegacyTextureAtlasHolder(TextureManager textureManager, Identifier atlasLocation, Identifier atlasInfoLocation,
            Set<?> metadataSections) {
        this.atlasInfoLocation = atlasInfoLocation;
        this.metadataSections = sectionTypes(metadataSections);
        this.textureAtlas = new TextureAtlas(ownAtlasId(atlasLocation));
        // Drawing finds the atlas by its texture location, so the holder registers it as a texture of its own.
        textureManager.register(textureAtlas.location(), textureAtlas);
    }

    /** The sprite at {@code location} in the latest stitch, or the atlas's missing sprite. */
    public TextureAtlasSprite getSprite(Identifier location) {
        return textureAtlas.getSprite(location);
    }

    @Override
    public CompletableFuture<Void> reload(SharedState state, Executor prepareExecutor, PreparationBarrier barrier,
            Executor applyExecutor) {
        // Sprites load and stitch off the game's thread; the upload waits for every listener to finish preparing.
        return SpriteLoader.create(textureAtlas)
                .loadAndStitch(state.resourceManager(), atlasInfoLocation, 0, prepareExecutor, metadataSections)
                .thenCompose(preparations -> preparations.readyForUpload().thenApply(ready -> preparations))
                .thenCompose(barrier::wait)
                .thenAcceptAsync(this::apply, applyExecutor);
    }

    /** Uploads a finished stitch; runs on the game's thread. */
    protected void apply(SpriteLoader.Preparations preparations) {
        textureAtlas.upload(preparations);
    }

    @Override
    public void close() {
        textureAtlas.clearTextureData();
    }

    // Registering over one of Minecraft's own atlases would replace it for the whole game. The texture manager has no
    // lookup that leaves a missing texture unloaded, so every id in Minecraft's namespace counts as taken.
    private static Identifier ownAtlasId(Identifier requested) {
        if (!requested.getNamespace().equals("minecraft")) return requested;
        Identifier renamed = Identifier.fromNamespaceAndPath("retromod", "legacy_atlas/minecraft/" + requested.getPath());
        LOG.log(System.Logger.Level.WARNING, "Registered an old TextureAtlasHolder atlas as {0} instead of {1}, which is"
                + " Minecraft''s own id. Its sprites draw from the renamed atlas, and Minecraft keeps its own.", renamed, requested);
        return renamed;
    }

    // Mods built for 1.20.2 to 1.21.3 passed metadata serializers, which 26.x replaced; only current section types are read.
    private static Set<MetadataSectionType<?>> sectionTypes(Set<?> sections) {
        Set<MetadataSectionType<?>> types = new LinkedHashSet<>();
        for (Object section : sections) {
            if (section instanceof MetadataSectionType<?> type) types.add(type);
        }
        return Set.copyOf(types);
    }
}
