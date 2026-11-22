/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.resources.model;

import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;

/** {@code ModelManager.getAtlas(id)}, which the game's atlas manager answers since atlases left the model manager. */
public final class LegacyModelAtlases {

    // The atlas manager moved package in 26.1, so it is reached by name rather than by type.
    private static volatile Method atlasManager;
    private static volatile Method atlasOrThrow;

    private LegacyModelAtlases() {}

    // Old code named an atlas by its texture, such as textures/atlas/blocks.png; the atlas manager keys them by id.
    static final String ATLAS_TEXTURE_PREFIX = "textures/atlas/";

    /** The atlas with that texture location or atlas id; like the old call, an unknown one fails rather than answering null. */
    public static TextureAtlas getAtlas(ModelManager manager, Identifier id) {
        // Each atlas is registered with the texture manager under its texture location, which the old lookup used.
        // Other ids are left out here, since the texture manager would load a new texture for an unknown one.
        if (id != null && id.getPath().startsWith(ATLAS_TEXTURE_PREFIX)
                && Minecraft.getInstance().getTextureManager().getTexture(id) instanceof TextureAtlas atlas) {
            return atlas;
        }
        try {
            Object atlases = atlasManager().invoke(Minecraft.getInstance());
            return (TextureAtlas) atlasOrThrow(atlases).invoke(atlases, id);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            throw new IllegalStateException("Retromod could not find the texture atlas " + id + " for an old mod. It accepts"
                    + " an atlas texture such as minecraft:textures/atlas/blocks.png or an atlas id such as minecraft:blocks."
                    + " Report this line with the mod.", cause);
        }
    }

    /** {@code minecraft.getTextureAtlas(id)}: the atlas's sprite lookup, from the same atlas the model manager answered. */
    public static java.util.function.Function<Identifier, TextureAtlasSprite> getTextureAtlas(Minecraft minecraft, Identifier id) {
        TextureAtlas atlas = getAtlas(null, id);
        return atlas::getSprite;
    }

    private static Method atlasManager() throws NoSuchMethodException {
        Method method = atlasManager;
        if (method == null) atlasManager = method = Minecraft.class.getMethod("getAtlasManager");
        return method;
    }

    private static Method atlasOrThrow(Object atlases) throws NoSuchMethodException {
        Method method = atlasOrThrow;
        if (method == null) atlasOrThrow = method = atlases.getClass().getMethod("getAtlasOrThrow", Identifier.class);
        return method;
    }
}
