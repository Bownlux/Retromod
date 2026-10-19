/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.client.color.block.BlockColorProvider;
import net.minecraft.client.color.item.ItemColorProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/** Old block render layers and color providers, set the way a 1.20.1 client mod sets them. */
public final class Test41LegacyRenderLayersAndColors implements Test {

    public static final int BLOCK_TINT = 0x3366CC;
    public static final int ITEM_TINT = 0x22AA44;
    // Test43ClientHostTints reads the host tint this block's color gave the client.
    public static Block tintedLeaves;
    public static Item tintedLeavesItem;

    @Override
    public String description() {
        return "old render layer puts and block and item color providers on the host";
    }

    @Override
    public TestResult run() {
        Identifier id = new Identifier("retromod_test_mod", "r1_tinted_leaves");
        tintedLeaves = Registry.register(Registries.BLOCK, id,
                new Block(AbstractBlock.Settings.create().strength(0.2f).nonOpaque()));
        tintedLeavesItem = Registry.register(Registries.ITEM, id, new BlockItem(tintedLeaves, new Item.Settings()));

        // Render layers: 26.x picks each layer from the texture, so Retromod records them only.
        BlockRenderLayerMap.INSTANCE.putBlock(tintedLeaves, cutoutMipped());
        BlockRenderLayerMap.INSTANCE.putBlocks(cutoutMipped(), tintedLeaves);
        BlockRenderLayerMap.INSTANCE.putItem(tintedLeavesItem, cutoutMipped());
        BlockRenderLayerMap.INSTANCE.putItems(cutoutMipped(), Items.GLASS_PANE);
        BlockRenderLayerMap.INSTANCE.putFluid(Fluids.WATER, translucent());
        BlockRenderLayerMap.INSTANCE.putFluids(translucent(), Fluids.FLOWING_WATER);
        for (Object put : new Object[]{tintedLeaves, tintedLeavesItem, Items.GLASS_PANE, Fluids.WATER,
                Fluids.FLOWING_WATER}) {
            if (!recorded(put)) return TestResult.fail("the render layer put for " + put + " was not recorded");
        }

        // Block color, read back with get() and called through the old type. A lambda on the client,
        // as mods wrote it; a server cannot build one, because 26.x made the level type client-only.
        BlockColorProvider blockColor = client() ? lambdaBlockColor() : new LeafBlockColor();
        ColorProviderRegistry.BLOCK.register(blockColor, tintedLeaves);
        BlockColorProvider gotBlock = ColorProviderRegistry.BLOCK.get(tintedLeaves);
        if (gotBlock != blockColor) return TestResult.fail("BLOCK.get returned " + gotBlock);
        int color = gotBlock.getColor(tintedLeaves.getDefaultState(), null, null, 2);
        if (color != BLOCK_TINT + 2) return TestResult.fail("the block color answered " + Integer.toHexString(color));

        // Item color: a class of the mod's own, registered for the block, which stands for its item.
        ItemColorProvider itemColor = new LeafItemColor();
        ColorProviderRegistry.ITEM.register(itemColor, tintedLeaves);
        if (ColorProviderRegistry.ITEM.get(tintedLeavesItem) != itemColor) {
            return TestResult.fail("ITEM.get did not answer for the block's item");
        }
        // 26.x binds item components after initialization, so only the empty stack exists yet.
        int itemTint = ColorProviderRegistry.ITEM.get(tintedLeaves).getColor(ItemStack.EMPTY, 1);
        return itemTint == ITEM_TINT + 1 ? TestResult.success()
                : TestResult.fail("the item color answered " + Integer.toHexString(itemTint));
    }

    private static BlockColorProvider lambdaBlockColor() {
        return (state, world, pos, tintIndex) -> BLOCK_TINT + tintIndex;
    }

    /** The old block color, as a class that implements the removed interface. */
    static final class LeafBlockColor implements BlockColorProvider {
        @Override
        public int getColor(net.minecraft.block.BlockState state, net.minecraft.world.BlockRenderView world,
                net.minecraft.util.math.BlockPos pos, int tintIndex) {
            return BLOCK_TINT + tintIndex;
        }
    }

    /** The old item color, as a class that implements the removed interface. */
    static final class LeafItemColor implements ItemColorProvider {
        @Override
        public int getColor(ItemStack stack, int tintIndex) {
            return ITEM_TINT + tintIndex;
        }
    }

    private static boolean client() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    // Render types are client classes; a dedicated server has none to pass.
    private static RenderLayer cutoutMipped() {
        return client() ? RenderLayer.getCutoutMipped() : null;
    }

    private static RenderLayer translucent() {
        return client() ? RenderLayer.getTranslucent() : null;
    }

    /** Asks Retromod's runtime by reflection, so this mod needs no compile dependency on it. */
    private static boolean recorded(Object put) {
        try {
            Class<?> layers = Class.forName("com.retromod.loaderapi.fabric.v26.render.LegacyRenderLayers");
            return (Boolean) layers.getMethod("hasLayer", Object.class).invoke(null, put);
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }
}
