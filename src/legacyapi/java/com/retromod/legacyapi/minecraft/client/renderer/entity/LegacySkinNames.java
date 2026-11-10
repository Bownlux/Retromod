/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.entity;

import net.minecraft.world.entity.player.PlayerModelType;

/** The skin names Minecraft 1.20.1 keyed player renderers by, "default" and "slim", and the model types that replaced them. */
public final class LegacySkinNames {

    private LegacySkinNames() {}

    /** The model type for "default" or "slim"; a later mod's model type passes through, and anything else is null. */
    public static PlayerModelType modelType(Object key) {
        if (key instanceof PlayerModelType type) return type;
        if ("default".equals(key)) return PlayerModelType.WIDE;
        if ("slim".equals(key)) return PlayerModelType.SLIM;
        return null;
    }

    /** The 1.20.1 name of a model type: "default" for wide arms, the model's own name otherwise. */
    public static String name(PlayerModelType type) {
        return type == PlayerModelType.WIDE ? "default" : type.getSerializedName();
    }
}
