/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.random.Random;

/** Old item and block model calls link and answer while mods initialize, as Carry On's and Farmer's Delight's renderers make them. */
public final class Test76OldBakedModelCalls implements Test {

    @Override
    public String description() {
        return "getBlockRenderManager at any time; old block and item models answer; glint buffer; model draws outside a draw are quiet";
    }

    @Override
    public TestResult run() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return TestResult.success();
        try {
            BlockRenderManager blocks = MinecraftClient.getInstance().getBlockRenderManager();
            if (blocks == null || blocks != MinecraftClient.getInstance().getBlockRenderManager()) {
                return TestResult.fail("getBlockRenderManager() did not answer one object");
            }
            // Mods run before the game has models, so the block's model has nothing to draw yet but is never null.
            BlockState stone = Blocks.STONE.getDefaultState();
            BakedModel block = blocks.getModel(stone);
            if (block == null) return TestResult.fail("no block model");
            List<BakedQuad> quads = block.getQuads(stone, null, Random.create());
            if (quads == null) return TestResult.fail("a block model's quads were null");
            blocks.getModels().getModelParticleSprite(stone);
            // An empty stack, since this runs while mods initialize; the calls still have to link and answer.
            ItemRenderer items = MinecraftClient.getInstance().getItemRenderer();
            BakedModel item = items.getModel(ItemStack.EMPTY, null, null, 0);
            if (item == null) return TestResult.fail("getModel answered null");
            item.hasDepth();
            item.isSideLit();
            item.getParticleSprite();
            if (item.getQuads(null, null, Random.create()) == null) return TestResult.fail("an item model's quads were null");
            if (items.getModels().getModel(ItemStack.EMPTY) == null) return TestResult.fail("the item model shaper answered null");
            VertexConsumer plain = ItemRenderer.getItemGlintConsumer(layer -> null, null, true, false);
            if (plain != null) return TestResult.fail("a buffer without glint was not the buffer asked for");
            items.renderItem(ItemStack.EMPTY, ModelTransformationMode.GROUND, false, new MatrixStack(), layer -> null, 15, 0, item);
            blocks.renderBlockAsEntity(stone, new MatrixStack(), layer -> null, 15, 0);
            return TestResult.success();
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return TestResult.fail(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }
}
