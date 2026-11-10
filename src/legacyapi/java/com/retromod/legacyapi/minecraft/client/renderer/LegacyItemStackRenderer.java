/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.MultiBufferSource;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Minecraft 1.21.1's ItemRenderer, which 26.1 removed: its renderStatic draws a stack into the old draw Retromod runs. */
public class LegacyItemStackRenderer {

    // Mods keep the renderer in a field from their constructors on, so one object answers every getItemRenderer().
    private static final LegacyItemStackRenderer INSTANCE = new LegacyItemStackRenderer();
    private static final LegacyFramePool<ItemSubmit> SUBMITS = new LegacyFramePool<>(ItemSubmit::new);
    private static volatile boolean notedOutside;

    protected LegacyItemStackRenderer() {}

    /** {@code Minecraft.getItemRenderer()} and the renderer contexts' {@code getItemRenderer()}: the one stand-in, at any time. */
    public static LegacyItemStackRenderer get(Object owner) {
        return INSTANCE;
    }

    /** Draws {@code stack} as the item model shows it in {@code context}, inside the old draw that handed out {@code buffers}. */
    public void renderStatic(ItemStack stack, ItemDisplayContext context, int light, int overlay, PoseStack poseStack,
            MultiBufferSource buffers, Level level, int seed) {
        draw(null, stack, context, light, overlay, poseStack, buffers, level, seed);
    }

    /** The same for a stack an entity holds; the display context already says which hand, as 26.x item models read it. */
    public void renderStatic(LivingEntity entity, ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack poseStack,
            MultiBufferSource buffers, Level level, int light, int overlay, int seed) {
        draw(entity, stack, context, light, overlay, poseStack, buffers, level, seed);
    }

    /** The model for the stack: Minecraft 26.x resolves the stack itself when that model is drawn or asked about. */
    public BlockStateModel getModel(ItemStack stack, Level level, LivingEntity entity, int seed) {
        // Built by the handle, so loading this renderer never needs Minecraft's block model class.
        return LegacyItemModelHandle.of(stack, level, entity, seed);
    }

    /** Draws the stack as {@code model} shows it; a block model or a mod's own model draws its quads with its display transform. */
    public void render(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack poseStack, MultiBufferSource buffers,
            int light, int overlay, BlockStateModel model) {
        if (stack == null || stack.isEmpty() || model == null) return;
        if (LegacyBakedModelCalls.drawItem(model, stack, context, poseStack, buffers, light, overlay)) return;
        if (!insideDraw(buffers)) return;
        poseStack.pushPose();
        try {
            BlockStateModel shown = model;
            if (model.getClass().getName().startsWith("net.minecraft.")) {
                // Minecraft's block models keep no display transforms; the stack's item model, which a block item takes
                // from its block, has them, centring included.
                LegacyItemModelHandle.applyStackTransform(stack, context, poseStack, leftHand);
            } else {
                shown = LegacyBakedModelCalls.applyTransform(model, context, poseStack, leftHand);
                poseStack.translate(-0.5f, -0.5f, -0.5f);
            }
            int[] tints = LegacyItemModelHandle.tintsOf(stack);
            for (BlockStateModel pass : LegacyBakedModelCalls.getRenderPasses(shown, stack, true)) {
                List<BakedQuad> quads = LegacyBakedModelCalls.allQuads(pass);
                for (RenderType type : LegacyBakedModelCalls.getRenderTypes(pass, stack, true)) {
                    VertexConsumer out = LegacyQuadDraws.foil(buffers, type, true, stack.hasFoil());
                    LegacyQuadDraws.put(poseStack.last(), out, quads, tints, light, overlay);
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Writes the model's quads into {@code out} at the pose, tinted as the stack's item model tints them. */
    public void renderModelLists(BlockStateModel model, ItemStack stack, int light, int overlay, PoseStack poseStack, VertexConsumer out) {
        if (model == null) return;
        int[] tints = model instanceof LegacyItemModelHandle item ? item.tints() : LegacyItemModelHandle.tintsOf(stack);
        LegacyQuadDraws.put(poseStack.last(), out, LegacyBakedModelCalls.allQuads(model), tints, light, overlay);
    }

    /** Writes {@code quads} into {@code out} at the pose, tinted as the stack's item model tints them. */
    public void renderQuadList(PoseStack poseStack, VertexConsumer out, List<BakedQuad> quads, ItemStack stack, int light, int overlay) {
        if (quads == null || quads.isEmpty()) return;
        LegacyQuadDraws.put(poseStack.last(), out, quads, LegacyItemModelHandle.tintsOf(stack), light, overlay);
    }

    public static VertexConsumer getFoilBuffer(MultiBufferSource buffers, RenderType type, boolean item, boolean foil) {
        return LegacyQuadDraws.foil(buffers, type, item, foil);
    }

    public static VertexConsumer getFoilBufferDirect(MultiBufferSource buffers, RenderType type, boolean item, boolean foil) {
        return LegacyQuadDraws.foil(buffers, type, item, foil);
    }

    /** Minecraft 1.21.1's armor glint buffer. */
    public static VertexConsumer getArmorFoilBuffer(MultiBufferSource buffers, RenderType type, boolean foil) {
        return LegacyQuadDraws.armorFoil(buffers, type, foil);
    }

    /** Minecraft 1.20.1's armor glint buffer, which also took whether the armor was an item. */
    public static VertexConsumer getArmorFoilBuffer(MultiBufferSource buffers, RenderType type, boolean item, boolean foil) {
        return LegacyQuadDraws.armorFoil(buffers, type, foil);
    }

    public static VertexConsumer getCompassFoilBuffer(MultiBufferSource buffers, RenderType type, PoseStack.Pose pose) {
        return LegacyQuadDraws.compassFoil(buffers, type, pose);
    }

    public static VertexConsumer getCompassFoilBufferDirect(MultiBufferSource buffers, RenderType type, PoseStack.Pose pose) {
        return LegacyQuadDraws.compassFoil(buffers, type, pose);
    }

    /** The old item model shaper, whose getItemModel answers the same models getModel does. */
    public LegacyItemModelShaper getItemModelShaper() {
        return LegacyItemModelShaper.INSTANCE;
    }

    static void draw(LivingEntity owner, ItemStack stack, ItemDisplayContext context, int light, int overlay, PoseStack poseStack,
            Object buffers, Level level, int seed) {
        if (stack == null || stack.isEmpty()) return;
        // Only a draw Retromod runs knows the frame's collector; an old call anywhere else has nowhere to draw on 26.x.
        Consumer<Consumer<SubmitNodeCollector>> sink = LegacyItemGeometry.sinkFor(buffers);
        if (sink == null) {
            noteOutside();
            return;
        }
        ItemSubmit submit = SUBMITS.next();
        ItemModelResolver resolver = Minecraft.getInstance().getItemModelResolver();
        if (owner != null) {
            resolver.updateForLiving(submit.state, stack, context, owner);
        } else {
            resolver.updateForTopItem(submit.state, stack, context, level, null, seed);
        }
        if (submit.state.isEmpty()) return;
        submit.set(poseStack, light, overlay);
        sink.accept(submit);
    }

    /** Whether {@code buffers} belong to a draw Retromod runs; quads written anywhere else would reach no frame, which is noted once. */
    public static boolean insideDraw(Object buffers) {
        if (LegacyItemGeometry.sinkFor(buffers) != null) return true;
        noteOutside();
        return false;
    }

    static void noteOutside() {
        if (notedOutside) return;
        notedOutside = true;
        LegacyLog.debug("An old mod drew an item outside any renderer Retromod runs, where 26.x has no buffer to draw into,"
                + " so Retromod skipped it");
    }

    /** One item to submit: its resolved model and a copy of the pose it was drawn at. */
    private static final class ItemSubmit implements Consumer<SubmitNodeCollector> {
        final ItemStackRenderState state = new ItemStackRenderState();
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
}
