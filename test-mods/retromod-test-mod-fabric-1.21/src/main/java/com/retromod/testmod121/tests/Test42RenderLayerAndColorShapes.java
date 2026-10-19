/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.tests;

import com.retromod.testmod121.RetromodTestMod121;
import com.retromod.testmod121.Test;
import com.retromod.testmod121.TestResult;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.class_11515;
import net.minecraft.client.color.block.BlockColorProvider;
import net.minecraft.client.color.item.ItemColorProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

/** Render layer and color provider shapes from 1.20.5 to 1.21.11, run on both sides. */
public final class Test42RenderLayerAndColorShapes implements Test {

    static final int BLOCK_TINT = 0x5588AA;
    static final int ITEM_TINT = 0x80336699;

    @Override
    public String description() {
        return "1.21 render layer and color provider shapes, with the 1.21.6 static layer map";
    }

    @Override
    public TestResult run() {
        Identifier id = Identifier.of(RetromodTestMod121.MOD_ID, "r1_glass");
        Block glass = Registry.register(Registries.BLOCK, id, new Block(AbstractBlock.Settings.create().nonOpaque()));
        Item glassItem = Registry.register(Registries.ITEM, id, new BlockItem(glass, new Item.Settings()));

        // The instance map until 1.21.5, and the static one from 1.21.6, built against stand-ins.
        BlockRenderLayerMap.INSTANCE.putBlock(glass, cutout());
        BlockRenderLayerMap.INSTANCE.putItem(glassItem, cutout());
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlocks(sectionLayer(), glass);
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(glass, sectionLayer());
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putFluid(Fluids.LAVA, sectionLayer());
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putFluids(sectionLayer(), Fluids.FLOWING_LAVA);
        // Section layers 26.x removed: CUTOUT_MIPPED (1.21.6 to 1.21.10) and TRIPWIRE.
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putFluid(Fluids.WATER, removedLayer(true));
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putFluid(Fluids.FLOWING_WATER, removedLayer(false));
        for (Object put : new Object[]{glass, glassItem, Fluids.LAVA, Fluids.FLOWING_LAVA, Fluids.WATER,
                Fluids.FLOWING_WATER}) {
            if (!recorded(put)) return TestResult.fail("the render layer put for " + put + " was not recorded");
        }
        // On a client the removed layers read as the layers that took over their blocks.
        if (client() && (layerOf(Fluids.WATER) != class_11515.field_60925
                || layerOf(Fluids.FLOWING_WATER) != class_11515.field_60926)) {
            return TestResult.fail("CUTOUT_MIPPED or TRIPWIRE did not read as CUTOUT or TRANSLUCENT");
        }

        // A 1.21 color provider pair; the item color keeps the alpha a 1.21 mod gave it.
        BlockColorProvider blockColor = new GlassColor();
        ColorProviderRegistry.BLOCK.register(blockColor, glass);
        if (ColorProviderRegistry.BLOCK.get(glass) != blockColor) return TestResult.fail("BLOCK.get missed");
        int color = ColorProviderRegistry.BLOCK.get(glass).getColor(glass.getDefaultState(), null, null, 1);
        if (color != BLOCK_TINT + 1) return TestResult.fail("the block color answered " + Integer.toHexString(color));
        ItemColorProvider itemColor = (stack, tintIndex) -> ITEM_TINT + tintIndex;
        ColorProviderRegistry.ITEM.register(itemColor, glassItem);
        ItemColorProvider got = ColorProviderRegistry.ITEM.get(glass);
        if (got != itemColor) return TestResult.fail("ITEM.get did not answer for the block's item");
        // 26.x binds item components after initialization, so only the empty stack exists yet.
        int itemTint = got.getColor(ItemStack.EMPTY, 2);
        return itemTint == ITEM_TINT + 2 ? TestResult.success()
                : TestResult.fail("the item color answered " + Integer.toHexString(itemTint));
    }

    /** The old block color as a class; a server cannot build a lambda of it on 26.x. */
    static final class GlassColor implements BlockColorProvider {
        @Override
        public int getColor(BlockState state, BlockRenderView world, BlockPos pos, int tintIndex) {
            return BLOCK_TINT + tintIndex;
        }
    }

    private static boolean client() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    // Render types and section layers are client classes; a dedicated server has none to pass.
    private static RenderLayer cutout() {
        return client() ? RenderLayer.getCutout() : null;
    }

    private static class_11515 sectionLayer() {
        return client() ? class_11515.field_60925 : null;
    }

    private static class_11515 removedLayer(boolean mipped) {
        if (!client()) return null;
        return mipped ? class_11515.field_60924 : class_11515.field_60927;
    }

    /** Asks Retromod's runtime by reflection, so this mod needs no compile dependency on it. */
    private static boolean recorded(Object put) {
        return Boolean.TRUE.equals(askLayers("hasLayer", put));
    }

    private static Object layerOf(Object put) {
        return askLayers("layerOf", put);
    }

    private static Object askLayers(String method, Object put) {
        try {
            Class<?> layers = Class.forName("com.retromod.loaderapi.fabric.v26.render.LegacyRenderLayers");
            return layers.getMethod(method, Object.class).invoke(null, put);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
