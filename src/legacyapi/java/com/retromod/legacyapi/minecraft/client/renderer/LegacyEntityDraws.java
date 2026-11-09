/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.MultiBufferSource;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;

/** EntityRenderDispatcher.render and setRenderShadow, which 26.1 removed: an entity drawn inside the old draw Retromod runs. */
public final class LegacyEntityDraws {

    private static final LegacyFramePool<EntitySubmit> SUBMITS = new LegacyFramePool<>(EntitySubmit::new);
    // Old code turned shadows off around its own entity draws; draws run on the render thread one after another.
    private static volatile boolean renderShadow = true;
    private static volatile boolean notedOutside;

    private LegacyEntityDraws() {}

    /** {@code dispatcher.setRenderShadow(on)}: whether the entities old code draws next keep their shadow. */
    public static void setRenderShadow(Object dispatcher, boolean on) {
        renderShadow = on;
    }

    /** {@code dispatcher.render(entity, x, y, z, yaw, partialTick, pose, buffers, light)}, at the camera-relative position. */
    public static void render(EntityRenderDispatcher dispatcher, Entity entity, double x, double y, double z, float yaw, float partialTick,
            PoseStack poseStack, MultiBufferSource buffers, int light) {
        if (entity == null) return;
        // Entity submits need the frame's camera, which only a block entity's or an entity's own draw is handed.
        Consumer<Consumer<SubmitNodeCollector>> sink = LegacyItemGeometry.sinkFor(buffers);
        CameraRenderState camera = LegacyItemGeometry.cameraFor(buffers);
        if (sink == null || camera == null) {
            noteOutside();
            return;
        }
        EntityRenderState state = dispatcher.extractEntity(entity, partialTick);
        // The old call took the light it was given, not the entity's own.
        state.lightCoords = light;
        if (!renderShadow) {
            state.shadowRadius = 0f;
            state.shadowPieces.clear();
        }
        EntitySubmit submit = SUBMITS.next();
        submit.set(dispatcher, state, camera, x, y, z, poseStack);
        sink.accept(submit);
    }

    private static void noteOutside() {
        if (notedOutside) return;
        notedOutside = true;
        LegacyLog.debug("An old mod drew an entity outside any block entity or entity renderer Retromod runs, where 26.x has no"
                + " buffer to draw into, so Retromod skipped it");
    }

    /** One entity to submit: its state, the frame's camera and a copy of the pose it was drawn at. */
    private static final class EntitySubmit implements Consumer<SubmitNodeCollector> {
        private final PoseStack pose = new PoseStack();
        private EntityRenderDispatcher dispatcher;
        private EntityRenderState state;
        private CameraRenderState camera;
        private double x;
        private double y;
        private double z;

        void set(EntityRenderDispatcher dispatcher, EntityRenderState state, CameraRenderState camera, double x, double y, double z,
                PoseStack from) {
            while (!pose.isEmpty()) pose.popPose();
            pose.last().set(from.last());
            this.dispatcher = dispatcher;
            this.state = state;
            this.camera = camera;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public void accept(SubmitNodeCollector collector) {
            dispatcher.submit(state, camera, x, y, z, pose, collector);
            // The state belongs to this frame; the pool entry should not keep the entity it names alive.
            state = null;
        }
    }
}
