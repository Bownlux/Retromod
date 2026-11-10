/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.MultiBufferSource;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;

/** Draws an entity renderer written for Minecraft 1.21.1 or earlier, which drew an entity into a buffer source, from 26.x's submit. */
public final class LegacyEntityRenderers {

    // A renderer that threw stays off until a resource reload builds the renderers again.
    private static final Set<Object> BROKEN = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    // Whether the old render asked for the base draw (name tag and leash) with super.render; draws run one after another.
    private static boolean wantsBase;

    private LegacyEntityRenderers() {}

    /** The old draw call, which Retromod has an old renderer implement with the method it already declares. */
    public interface OldRenderer {
        void render(Entity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light);
    }

    /** The state an old renderer gets: the entity, its yaw and the partial tick its old render takes, for this frame only. */
    public static final class State extends EntityRenderState {
        Entity entity;
        float yaw;
        float partialTick;
    }

    /** The createRenderState Retromod gives an old renderer. */
    public static EntityRenderState createRenderState() {
        return new State();
    }

    /** What the extractRenderState Retromod gives an old renderer adds after the host's: what the old render takes. */
    public static void extractRenderState(Entity entity, EntityRenderState state, float partialTick) {
        if (!(state instanceof State legacy)) return;
        legacy.entity = entity;
        legacy.partialTick = partialTick;
        // The yaw Minecraft 1.21.1's level renderer handed every entity renderer.
        legacy.yaw = entity.yRotO + partialTick * (entity.getYRot() - entity.yRotO);
    }

    /** The entity an old renderer's state holds for this frame, or null before the host fills it. */
    public static Entity entity(EntityRenderState state) {
        return state instanceof State legacy ? legacy.entity : null;
    }

    /** The partial tick an old renderer's state holds for this frame. */
    public static float partialTick(EntityRenderState state) {
        return state instanceof State legacy ? legacy.partialTick : 0.0F;
    }

    /** {@code super.shouldShowName(entity)} in an old renderer: 1.20.1's base showed a custom name its entity made visible. */
    public static boolean baseShouldShowName(Object renderer, Entity entity) {
        return entity.shouldShowName() && entity.hasCustomName();
    }

    /** {@code super.render(...)} in an old render: the base draw, which the submit then asks the host for. */
    public static void superRender(Object renderer, Entity entity, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource buffers, int light) {
        wantsBase = true;
    }

    /** The submit Retromod gives an old renderer: its old render with the state's entity; true when the host's base draw should follow. */
    public static boolean submit(OldRenderer renderer, EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState camera) {
        if (!(state instanceof State legacy) || legacy.entity == null || BROKEN.contains(renderer)) return false;
        LegacyItemGeometry geometry = LegacyItemGeometry.begin(poseStack, state.lightCoords, OverlayTexture.NO_OVERLAY, camera);
        SubmitNodeCollector drawn = null;
        boolean base = false;
        wantsBase = false;
        try {
            renderer.render(legacy.entity, legacy.yaw, legacy.partialTick, geometry.pose(), geometry.buffers(), state.lightCoords);
            drawn = collector;
            base = wantsBase;
        } catch (Exception | LinkageError failure) {
            // Minecraft 1.20.1 stopped the game here; a renderer that reaches an API Minecraft removed now leaves its entity undrawn.
            BROKEN.add(renderer);
            LegacyLog.warnOnce("entity renderer " + renderer.getClass().getName(), "The old entity renderer " + renderer.getClass().getName()
                    + " threw " + failure + ", so Retromod stops drawing it until the next resource reload");
        } finally {
            // What a renderer that threw wrote is dropped, so a half-drawn entity never reaches the frame.
            geometry.finish(drawn);
            wantsBase = false;
        }
        return base;
    }
}
