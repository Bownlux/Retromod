/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import com.retromod.legacyapi.minecraft.client.renderer.entity.RenderStateEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RenderArmEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.client.settings.KeyModifier;

/** Client event reads that Forge 1.20.1 mods make, answered on Forge 26.2 and newer, whose events carry a render state. */
public final class LegacyForgeClientEventReads {

    private LegacyForgeClientEventReads() {}

    /** {@code RenderLivingEvent.getPartialTick()}: the game's partial tick for this frame. */
    public static float livingPartialTick(RenderLivingEvent<?, ?, ?> event) {
        return framePartialTick();
    }

    /** {@code RenderLivingEvent.getPackedLight()}: the light the render state was made with. */
    public static int livingLight(RenderLivingEvent<?, ?, ?> event) {
        return event.getState().lightCoords;
    }

    /** {@code RenderNameTagEvent.getEntity()}: the entity the render state was made from, or null when none matches it. */
    public static Entity nameTagEntity(RenderNameTagEvent event) {
        EntityRenderState state = event.getState();
        return RenderStateEntities.entity(state);
    }

    /** {@code RenderNameTagEvent.getPackedLight()}: the light the render state was made with. */
    public static int nameTagLight(RenderNameTagEvent event) {
        EntityRenderState state = event.getState();
        return state.lightCoords;
    }

    /** {@code RenderNameTagEvent.getPartialTick()}: the game's partial tick for this frame. */
    public static float nameTagPartialTick(RenderNameTagEvent event) {
        return framePartialTick();
    }

    /** {@code KeyModifier.getActiveModifier()}: the first of Shift, Control and Alt held, in the order Forge 1.20.1 checked them, else NONE. */
    public static KeyModifier activeModifier() {
        for (KeyModifier modifier : new KeyModifier[] {KeyModifier.SHIFT, KeyModifier.CONTROL, KeyModifier.ALT}) {
            if (modifier.isActive(null)) return modifier;
        }
        return KeyModifier.NONE;
    }

    /** {@code RenderArmEvent.getPlayer()}: the first person arm is always the local player's. */
    public static AbstractClientPlayer armPlayer(RenderArmEvent event) {
        // Read as an Object, so this class never needs the local player's type to verify.
        Object player = Minecraft.getInstance().player;
        return player instanceof AbstractClientPlayer clientPlayer ? clientPlayer : null;
    }

    /** {@code CreateSkullModels.getEntityModelSet()}: the game's own model set. */
    public static EntityModelSet skullModelSet(EntityRenderersEvent.CreateSkullModels event) {
        return Minecraft.getInstance().getEntityModels();
    }

    /** {@code CreateSkullModels.registerSkullModel(type, model)}: the event now takes a factory, which hands out the built model. */
    public static void registerSkullModel(EntityRenderersEvent.CreateSkullModels event, SkullBlock.Type type, SkullModelBase model) {
        event.registerSkullModel(type, models -> model);
    }

    private static float framePartialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }
}
