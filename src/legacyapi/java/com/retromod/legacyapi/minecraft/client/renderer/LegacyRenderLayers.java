/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.legacyapi.minecraft.client.renderer.entity.RenderStateEntities;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.MultiBufferSource;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;

/** Draws a render layer written for Minecraft 1.21.1 or earlier, which drew an entity into a buffer source, from 26.x's submit. */
public final class LegacyRenderLayers {

    // A layer that threw stays off until a resource reload builds the renderers, and the mod its layers, again.
    private static final Set<Object> BROKEN = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private LegacyRenderLayers() {}

    /** The old draw call, which Retromod has an old layer implement with the method it already declares. */
    public interface OldLayer {
        void render(PoseStack poseStack, MultiBufferSource buffers, int light, Entity entity, float limbSwing, float limbSwingAmount,
                float partialTick, float ageInTicks, float netHeadYaw, float headPitch);
    }

    /** The submit Retromod gives an old layer: its old render, run with the entity the state was made from. */
    public static void submit(OldLayer layer, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, EntityRenderState state,
            float yRot, float xRot) {
        if (BROKEN.contains(layer)) return;
        Entity entity = RenderStateEntities.entity(state);
        // A state no single entity in the level matches, such as a preview in a menu, has nothing to hand the old layer.
        if (entity == null) return;
        float limbSwing = 0;
        float limbSwingAmount = 0;
        if (state instanceof LivingEntityRenderState living) {
            limbSwing = living.walkAnimationPos;
            limbSwingAmount = living.walkAnimationSpeed;
        }
        LegacyItemGeometry geometry = LegacyItemGeometry.begin(poseStack, lightCoords, OverlayTexture.NO_OVERLAY);
        SubmitNodeCollector drawn = null;
        try {
            layer.render(geometry.pose(), geometry.buffers(), lightCoords, entity, limbSwing, limbSwingAmount, partialTick(),
                    state.ageInTicks, yRot, xRot);
            drawn = collector;
        } catch (Exception | LinkageError failure) {
            // Forge 1.20.1 stopped the game here; a layer that reaches an API Minecraft removed now leaves its entity undecorated.
            BROKEN.add(layer);
            LegacyLog.warnOnce("render layer " + layer.getClass().getName(), "The old render layer " + layer.getClass().getName()
                    + " threw " + failure + ", so Retromod stops drawing it until the next resource reload");
        } finally {
            // What a layer that threw wrote is dropped, so a half-drawn layer never reaches the frame.
            geometry.finish(drawn);
        }
    }

    // The partial tick the frame draws with, which old layers took as an argument.
    private static float partialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }
}
