/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Supplier;

/** Lootr's chest reads ChestBlock's AABB through its own class; 1.21.5 removed the constant. */
final class ChestShapeCase extends ChestBlock {

    private ChestShapeCase(Properties properties, Supplier<BlockEntityType<? extends ChestBlockEntity>> type) {
        super(properties, type);
    }

    /** The single chest box, one pixel in from each side and 14 pixels tall. */
    static boolean singleChestShape() {
        VoxelShape shape = AABB;
        return shape.max(Direction.Axis.Y) == 14.0 / 16.0 && shape.min(Direction.Axis.X) == 1.0 / 16.0;
    }
}
