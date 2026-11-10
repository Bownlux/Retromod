/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import com.retromod.legacyapi.minecraft.client.renderer.entity.LegacySkinNames;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelType;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Forge 1.20.1's AddLayers lookups by skin name and entity type, on NeoForge, which keys player renderers by model type. */
public final class LegacyAddLayers {

    private LegacyAddLayers() {}

    /** getSkin(String): the player renderer for "default" or "slim", or null for another name, as 1.20.1 gave. */
    public static LivingEntityRenderer<?, ?, ?> getSkin(EntityRenderersEvent.AddLayers event, String name) {
        PlayerModelType type = LegacySkinNames.modelType(name);
        return type == null ? null : event.getPlayerRenderer(type);
    }

    /** getSkins(): the 1.20.1 names of the model types, where NeoForge's own getSkins() returns the types. */
    public static Set<String> getSkins(EntityRenderersEvent.AddLayers event) {
        Set<String> names = new LinkedHashSet<>();
        for (PlayerModelType type : event.getSkins()) names.add(LegacySkinNames.name(type));
        return names;
    }

    /** getRenderer(EntityType): the type's living renderer, or null for any other, such as the ender dragon's, as 1.20.1 gave. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static LivingEntityRenderer<?, ?, ?> getRenderer(EntityRenderersEvent.AddLayers event, EntityType<?> type) {
        Object renderer = event.getRenderer((EntityType) type);
        return renderer instanceof LivingEntityRenderer<?, ?, ?> living ? living : null;
    }
}
