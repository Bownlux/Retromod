/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Minecraft 1.21.1's ItemModelShaper, which 1.21.4 removed: it hands out the same item models ItemRenderer.getModel does. */
public class LegacyItemModelShaper {

    static final LegacyItemModelShaper INSTANCE = new LegacyItemModelShaper();

    public LegacyItemModelShaper() {}

    // Old mods built their own from the model manager; every shaper answers the same.
    public LegacyItemModelShaper(ModelManager modelManager) {
        this();
    }

    public BlockStateModel getItemModel(ItemStack stack) {
        return LegacyItemModelHandle.of(stack, null, null, 0);
    }

    public BlockStateModel getItemModel(Item item) {
        return getItemModel(new ItemStack(item));
    }

    public ModelManager getModelManager() {
        return Minecraft.getInstance().getModelManager();
    }
}
