/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.legacyapi.forge.event.LegacyPlayerEvent;
import com.retromod.legacyapi.forge.eventbus.LegacyEvents;
import com.retromod.legacyapi.minecraft.client.renderer.entity.RenderStateEntities;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.event.InheritableEvent;

/** Forge 1.20.1's player render events, which Forge 26.2 replaced with avatar events; Retromod posts them from those. */
public abstract class RenderPlayerEvent extends LegacyPlayerEvent implements InheritableEvent {

    private final float partialTick;
    private final PoseStack poseStack;
    private final int packedLight;
    private AvatarRenderer<?> renderer;

    protected RenderPlayerEvent(Player player, float partialTick, PoseStack poseStack, int packedLight) {
        super(player);
        this.partialTick = partialTick;
        this.poseStack = poseStack;
        this.packedLight = packedLight;
    }

    /** The partial tick of the frame; 26.2 renders players from a state, so this is the frame's own. */
    public float getPartialTick() {
        return partialTick;
    }

    public PoseStack getPoseStack() {
        return poseStack;
    }

    public int getPackedLight() {
        return packedLight;
    }

    /** The avatar renderer drawing the player, which old mods name by 1.21.1's PlayerRenderer; null for an event a mod built itself. */
    public AvatarRenderer<?> getRenderer() {
        return renderer;
    }

    /** Posts this copy's Pre or Post for one avatar draw; a Pre answers whether it ends cancelled. No player, no post. */
    static boolean post(boolean pre, AvatarRenderState state, AvatarRenderer<?> renderer, PoseStack pose, float partialTick) {
        if (!(RenderStateEntities.livingEntity(state) instanceof Player player)) return false;
        return LegacyEvents.post(BusGroup.DEFAULT, create(pre, player, renderer, pose, partialTick, state.lightCoords));
    }

    /** A Pre or Post for one avatar draw, carrying the renderer drawing it. */
    static RenderPlayerEvent create(boolean pre, Player player, AvatarRenderer<?> renderer, PoseStack pose, float partialTick, int light) {
        RenderPlayerEvent event = pre ? new Pre(player, partialTick, pose, light) : new Post(player, partialTick, pose, light);
        event.renderer = renderer;
        return event;
    }

    /** Before a player draws; cancelling it skips the draw. */
    public static final class Pre extends RenderPlayerEvent {
        public Pre(Player player, float partialTick, PoseStack poseStack, int packedLight) {
            super(player, partialTick, poseStack, packedLight);
        }

        @Override
        public boolean isCancelable() {
            return true;
        }
    }

    /** After a player drew. */
    public static final class Post extends RenderPlayerEvent {
        public Post(Player player, float partialTick, PoseStack poseStack, int packedLight) {
            super(player, partialTick, poseStack, packedLight);
        }
    }
}
