/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.shim.common.embedded.MultiBufferSource;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** Minecraft 1.21.1's BlockRenderDispatcher, which 26.1 removed: block models at any time, single blocks drawn inside Retromod's draws. */
public class LegacyBlockRenderDispatcher {

    // Mods keep the dispatcher from reload listeners and constructors on, so one object answers every getBlockRenderer().
    private static final LegacyBlockRenderDispatcher INSTANCE = new LegacyBlockRenderDispatcher();
    private static final LegacyFramePool<BlockSubmit> SUBMITS = new LegacyFramePool<>(BlockSubmit::new);
    /** The model old code gets for a state while Minecraft has no models yet: no parts and no particle. */
    static final BlockStateModel EMPTY = new Empty();
    // Minecraft's block models may pick a look per display context; old single-block draws get one of their own.
    private static BlockDisplayContext display;
    private static BlockModelResolver resolver;
    private static ModelManager resolverModels;
    private static ModelBlockRenderer modelRenderer;

    protected LegacyBlockRenderDispatcher() {}

    /** {@code Minecraft.getBlockRenderer()}: the one stand-in, at any time. */
    public static LegacyBlockRenderDispatcher get(Object owner) {
        return INSTANCE;
    }

    public BlockStateModel getBlockModel(BlockState state) {
        return LegacyBlockModelShaper.INSTANCE.getBlockModel(state);
    }

    public LegacyBlockModelShaper getBlockModelShaper() {
        return LegacyBlockModelShaper.INSTANCE;
    }

    /** Minecraft's own block model renderer, whose old members Retromod answers for old mods. */
    public synchronized ModelBlockRenderer getModelRenderer() {
        if (modelRenderer == null) modelRenderer = new ModelBlockRenderer(true, true, Minecraft.getInstance().getBlockColors());
        return modelRenderer;
    }

    /** Draws the state's block model, as Minecraft draws a falling or displayed block, inside the old draw that handed out {@code buffers}. */
    public void renderSingleBlock(BlockState state, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        if (state == null) return;
        Consumer<Consumer<SubmitNodeCollector>> sink = LegacyItemGeometry.sinkFor(buffers);
        if (sink == null) {
            LegacyItemStackRenderer.noteOutside();
            return;
        }
        BlockSubmit submit = SUBMITS.next();
        resolver().update(submit.state, state, display);
        if (submit.state.isEmpty()) return;
        submit.set(poseStack, light, overlay);
        sink.accept(submit);
    }

    private static synchronized BlockModelResolver resolver() {
        ModelManager models = Minecraft.getInstance().getModelManager();
        // The resolver reads the manager it was built with, which a resource reload keeps.
        if (resolver == null || resolverModels != models) {
            resolver = new BlockModelResolver(models);
            resolverModels = models;
        }
        if (display == null) display = BlockDisplayContext.create();
        return resolver;
    }

    /** One block to submit: its resolved model and a copy of the pose it was drawn at. */
    private static final class BlockSubmit implements Consumer<SubmitNodeCollector> {
        final BlockModelRenderState state = new BlockModelRenderState();
        private final PoseStack pose = new PoseStack();
        private int light;
        private int overlay;

        void set(PoseStack from, int light, int overlay) {
            // A submit that threw last frame may have left poses pushed.
            while (!pose.isEmpty()) pose.popPose();
            pose.last().set(from.last());
            this.light = light;
            this.overlay = overlay;
        }

        @Override
        public void accept(SubmitNodeCollector collector) {
            state.submit(pose, collector, light, overlay, 0);
        }
    }

    /** A block model with nothing to draw. */
    private static final class Empty implements BlockStateModel {
        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        }

        @Override
        public Material.Baked particleMaterial() {
            return null;
        }

        @Override
        public int materialFlags() {
            return 0;
        }
    }
}
