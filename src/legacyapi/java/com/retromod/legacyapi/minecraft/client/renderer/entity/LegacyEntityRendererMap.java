/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.entity;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;

/** Minecraft 1.20.1's public EntityRenderDispatcher.renderers, which later versions made private, as a view read from the game. */
public final class LegacyEntityRendererMap extends AbstractMap<EntityType<?>, EntityRenderer<?, ?>> {

    private final EntityRenderDispatcher dispatcher;

    private LegacyEntityRendererMap(EntityRenderDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** dispatcher.renderers: a view, because the game builds new renderers on every resource reload. */
    public static Map<EntityType<?>, EntityRenderer<?, ?>> of(EntityRenderDispatcher dispatcher) {
        return new LegacyEntityRendererMap(dispatcher);
    }

    @Override
    public EntityRenderer<?, ?> get(Object key) {
        return key instanceof EntityType<?> type ? renderer(type) : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public Set<Entry<EntityType<?>, EntityRenderer<?, ?>>> entrySet() {
        Map<EntityType<?>, EntityRenderer<?, ?>> now = new LinkedHashMap<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            EntityRenderer<?, ?> renderer = renderer(type);
            if (renderer != null) now.put(type, renderer);
        }
        return Collections.unmodifiableMap(now).entrySet();
    }

    // A state that is not a player's picks its renderer by entity type alone, so a bare state asks for one type.
    private EntityRenderer<?, ?> renderer(EntityType<?> type) {
        EntityRenderState probe = new EntityRenderState();
        probe.entityType = type;
        return dispatcher.getRenderer(probe);
    }
}
