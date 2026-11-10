/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyBlockModelShaper;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyBlockRenderDispatcher;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemModelHandle;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemModelShaper;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemStackRenderer;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyQuadDraws;
import com.retromod.shim.common.embedded.MultiBufferSource;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Forge 1.20.1's item and block renderer members that take or hand out its BakedModel, which Retromod rebuilt for Forge 26.2. */
public final class LegacyForgeItemDraws {

    private static final Direction[] SIDES = Direction.values();
    // Forge 1.20.1's item renderer seeded every side's random with 42.
    private static final long ITEM_SEED = 42L;

    private LegacyForgeItemDraws() {}

    /** ItemRenderer.getModel: an old mod's own model when one replaced the item's, with its overrides applied; else the stack's model. */
    public static BakedModel getModel(LegacyItemStackRenderer renderer, ItemStack stack, Level level, LivingEntity entity, int seed) {
        if (itemModel(stack) instanceof LegacyItemModel adapter) {
            BakedModel legacy = adapter.legacy();
            ClientLevel client = level instanceof ClientLevel clientLevel ? clientLevel : null;
            BakedModel resolved = legacy.getOverrides().resolve(legacy, stack, client, entity, seed);
            return resolved != null ? resolved : legacy;
        }
        return new LegacyItemStackModel(new LegacyItemModelHandle(stack, level, entity, seed));
    }

    /** ItemRenderer.render: the stack as Minecraft resolves it, or an old mod's own model drawn quad by quad. */
    public static void render(LegacyItemStackRenderer renderer, ItemStack stack, ItemDisplayContext context, boolean leftHand,
            PoseStack poseStack, MultiBufferSource buffers, int light, int overlay, BakedModel model) {
        if (stack == null || stack.isEmpty() || model == null) return;
        if (model instanceof LegacyItemStackModel item) {
            item.handle().draw(stack, context, poseStack, buffers, light, overlay);
            return;
        }
        // The stack's own item model, or an old model drawn by an item renderer: Minecraft draws the stack itself. A view
        // of another item model draws the quads it can read below, which a select or special model has none of.
        boolean ownModel = model instanceof LegacyItemModelView view && view.host() == itemModel(stack);
        if (ownModel || model.isCustomRenderer()) {
            new LegacyItemModelHandle(stack, null, null, 0).draw(stack, context, poseStack, buffers, light, overlay);
            return;
        }
        if (!LegacyItemStackRenderer.insideDraw(buffers)) return;
        poseStack.pushPose();
        try {
            BakedModel shown = model;
            if (model instanceof LegacyBlockStateModelView) {
                // Minecraft's block models keep no display transforms; the stack's item model, which a block item takes
                // from its block, has them, centring included.
                LegacyItemModelHandle.applyStackTransform(stack, context, poseStack, leftHand);
            } else {
                shown = model.applyTransform(context, poseStack, leftHand);
                if (shown == null) shown = model;
                poseStack.translate(-0.5f, -0.5f, -0.5f);
            }
            int[] tints = LegacyItemModelHandle.tintsOf(stack);
            for (BakedModel pass : shown.getRenderPasses(stack, true)) {
                List<BakedQuad> quads = quads(pass);
                for (RenderType type : pass.getRenderTypes(stack, true)) {
                    VertexConsumer out = LegacyQuadDraws.foil(buffers, type, true, stack.hasFoil());
                    LegacyQuadDraws.put(poseStack.last(), out, quads, tints, light, overlay);
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** ItemRenderer.renderModelLists: the model's quads at the pose, tinted as the stack's item model tints them. */
    public static void renderModelLists(LegacyItemStackRenderer renderer, BakedModel model, ItemStack stack, int light, int overlay,
            PoseStack poseStack, VertexConsumer out) {
        if (model == null) return;
        LegacyQuadDraws.put(poseStack.last(), out, quads(model), LegacyItemModelHandle.tintsOf(stack), light, overlay);
    }

    /** ItemModelShaper.getItemModel: the same model getModel answers, without an owner. */
    public static BakedModel getItemModel(LegacyItemModelShaper shaper, ItemStack stack) {
        return getModel(null, stack, null, null, 0);
    }

    public static BakedModel getItemModel(LegacyItemModelShaper shaper, Item item) {
        return getItemModel(shaper, new ItemStack(item));
    }

    /** BlockRenderDispatcher.getBlockModel: an old mod's own block model when one replaced the state's, else a view of Minecraft's. */
    public static BakedModel getBlockModel(LegacyBlockRenderDispatcher dispatcher, BlockState state) {
        return LegacyModelMaps.blockView(dispatcher.getBlockModel(state));
    }

    public static BakedModel getBlockModel(LegacyBlockModelShaper shaper, BlockState state) {
        return LegacyModelMaps.blockView(shaper.getBlockModel(state));
    }

    /** ModelBlockRenderer.renderModel: every quad of the model, the tinted ones in the given color. */
    public static void renderModel(ModelBlockRenderer renderer, PoseStack.Pose pose, VertexConsumer out, BlockState state, BakedModel model,
            float red, float green, float blue, int light, int overlay) {
        if (model == null) return;
        int tint = 0xFF000000 | channel(red) << 16 | channel(green) << 8 | channel(blue);
        for (BakedQuad quad : quads(model)) {
            BakedQuad.MaterialInfo info = quad.materialInfo();
            LegacyQuadDraws.put(pose, out, quad, info != null && info.isTinted() ? tint : -1, light, overlay);
        }
    }

    // Every side's quads and the unculled ones, each with the random reseeded, as Forge 1.20.1's item renderer read them.
    private static List<BakedQuad> quads(BakedModel model) {
        if (model instanceof LegacyItemStackModel item) return item.getQuads(null, null, null);
        RandomSource random = RandomSource.create(ITEM_SEED);
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction side : SIDES) {
            random.setSeed(ITEM_SEED);
            add(quads, model.getQuads(null, side, random));
        }
        random.setSeed(ITEM_SEED);
        add(quads, model.getQuads(null, null, random));
        return quads;
    }

    private static void add(List<BakedQuad> into, List<BakedQuad> quads) {
        if (quads == null) return;
        for (BakedQuad quad : quads) if (quad != null) into.add(quad);
    }

    private static ItemModel itemModel(ItemStack stack) {
        Identifier id = stack == null ? null : stack.get(DataComponents.ITEM_MODEL);
        Minecraft minecraft = Minecraft.getInstance();
        return id == null || minecraft == null || minecraft.getModelManager() == null ? null : minecraft.getModelManager().getItemModel(id);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, (int) (value * 255f)));
    }
}
