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
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Draws a block entity renderer written for Minecraft 1.21.4 or earlier, which drew into a buffer source, from 26.x's submit. */
public final class LegacyBlockEntityRenderers {

    // A renderer that threw stays off until a resource reload builds the renderers again.
    private static final Set<Object> BROKEN = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private LegacyBlockEntityRenderers() {}

    /** The old draw call, which Retromod has an old renderer implement with the method it already declares. */
    public interface OldRenderer {
        void render(BlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay);
    }

    /** The state an old renderer gets: the block entity and partial tick its old render takes, for this frame only. */
    public static final class State extends BlockEntityRenderState {
        BlockEntity blockEntity;
        float partialTick;
    }

    /** The createRenderState Retromod gives an old renderer. */
    public static BlockEntityRenderState createRenderState() {
        return new State();
    }

    /** The extractRenderState Retromod gives an old renderer: the host's base fields, then what the old render takes. */
    public static void extractRenderState(BlockEntity blockEntity, BlockEntityRenderState state, float partialTick,
            ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress);
        if (state instanceof State legacy) {
            legacy.blockEntity = blockEntity;
            legacy.partialTick = partialTick;
        }
    }

    /** The submit Retromod gives an old renderer: its old render, run with the block entity the state was made from. */
    public static void submit(OldRenderer renderer, BlockEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState camera) {
        if (!(state instanceof State legacy) || legacy.blockEntity == null || BROKEN.contains(renderer)) return;
        LegacyItemGeometry geometry = LegacyItemGeometry.begin(poseStack, state.lightCoords, OverlayTexture.NO_OVERLAY, camera);
        SubmitNodeCollector drawn = null;
        try {
            renderer.render(legacy.blockEntity, legacy.partialTick, geometry.pose(), geometry.buffers(), state.lightCoords,
                    OverlayTexture.NO_OVERLAY);
            drawn = collector;
        } catch (Exception | LinkageError failure) {
            // Minecraft 1.20.1 stopped the game here; a renderer that reaches an API Minecraft removed now leaves its block undrawn.
            BROKEN.add(renderer);
            LegacyLog.warnOnce("block entity renderer " + renderer.getClass().getName(), "The old block entity renderer "
                    + renderer.getClass().getName() + " threw " + failure + ", so Retromod stops drawing it until the next resource reload");
        } finally {
            // What a renderer that threw wrote is dropped, so a half-drawn block never reaches the frame.
            geometry.finish(drawn);
        }
    }
}
