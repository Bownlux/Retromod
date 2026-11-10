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
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** Minecraft 1.20.1's EntityRenderDispatcher.getSkinMap(): the player renderers keyed "default" and "slim", read from the game on each use. */
public final class LegacySkinMap extends AbstractMap<String, EntityRenderer<?, ?>> {

    private final EntityRenderDispatcher dispatcher;

    private LegacySkinMap(EntityRenderDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** getSkinMap(): a view, because the game builds new player renderers on every resource reload. */
    public static Map<String, EntityRenderer<?, ?>> of(EntityRenderDispatcher dispatcher) {
        return new LegacySkinMap(dispatcher);
    }

    /** The renderer for a 1.20.1 skin name, or for the model type a later mod passes. */
    @Override
    public EntityRenderer<?, ?> get(Object key) {
        PlayerModelType type = LegacySkinNames.modelType(key);
        return type == null ? null : renderer(dispatcher, type);
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public Set<Entry<String, EntityRenderer<?, ?>>> entrySet() {
        Map<String, EntityRenderer<?, ?>> now = new LinkedHashMap<>();
        for (PlayerModelType type : PlayerModelType.values()) {
            EntityRenderer<?, ?> renderer = renderer(dispatcher, type);
            if (renderer != null) now.put(LegacySkinNames.name(type), renderer);
        }
        return Collections.unmodifiableMap(now).entrySet();
    }

    // The game keeps its player renderers private and picks one by the skin a player's state carries.
    static EntityRenderer<?, ?> renderer(EntityRenderDispatcher dispatcher, PlayerModelType type) {
        AvatarRenderState probe = new AvatarRenderState();
        probe.skin = PlayerSkin.insecure(null, null, null, type);
        return dispatcher.getRenderer(probe);
    }
}
