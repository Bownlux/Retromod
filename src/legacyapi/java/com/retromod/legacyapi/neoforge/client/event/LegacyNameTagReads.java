/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.legacyapi.minecraft.client.renderer.entity.RenderStateEntities;
import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

/** Name tag event reads old mods make, answered from the render state NeoForge 1.21.11 and newer build name tags from. */
public final class LegacyNameTagReads {

    private LegacyNameTagReads() {}

    /** {@code RenderNameTagEvent.getContent()}: the name tag the render state carries. */
    public static Component nameTagContent(RenderNameTagEvent event) {
        return event.getEntityRenderState().nameTag;
    }

    /** {@code RenderNameTagEvent.getEntity()}: the entity the render state was made from, or null when none matches it. */
    public static Entity nameTagEntity(RenderNameTagEvent event) {
        return RenderStateEntities.entity(event.getEntityRenderState());
    }

    /** {@code RenderNameTagEvent.getPackedLight()}: the light the render state was made with. */
    public static int nameTagLight(RenderNameTagEvent event) {
        return event.getEntityRenderState().lightCoords;
    }

    /** {@code RenderNameTagEvent.getPoseStack()}: a fresh pose stack, since the event no longer carries one. */
    public static PoseStack nameTagPoseStack(RenderNameTagEvent event) {
        LegacyLog.warnOnce("name tag pose stack", "An old mod drew beside a name tag, which this NeoForge version draws"
                + " from a render state, so that drawing does not show.");
        return new PoseStack();
    }
}
