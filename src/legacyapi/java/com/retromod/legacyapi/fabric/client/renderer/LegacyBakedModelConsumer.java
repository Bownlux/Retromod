/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import java.util.function.Consumer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.state.BlockState;

/** The old render context's {@code BakedModelConsumer}: draws another model where the current one is drawn. */
public interface LegacyBakedModelConsumer extends Consumer<BlockStateModel> {
    @Override
    void accept(BlockStateModel model);

    void accept(BlockStateModel model, BlockState state);
}
