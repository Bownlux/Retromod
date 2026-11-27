/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge.legacy118;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraftforge.client.IItemRenderProperties;

/** #408: an item renderer handed out through Forge 1.18.2's IItemRenderProperties, as Monke Madness's pan flute is. */
public class Era118RenderProperties implements IItemRenderProperties {
    @Override
    public BlockEntityWithoutLevelRenderer getItemStackRenderer() {
        return null;
    }
}
