/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;

import java.util.function.Supplier;

/** Lootr's chest reads the chest shape through its own class; 1.21.5 rebuilt block shapes without the constant. */
public class Test72ChestShapes implements Test {

    @Override
    public String description() {
        return "ChestBlock's single chest shape read through a chest subclass";
    }

    @Override
    public TestResult run() {
        VoxelShape shape = ShapeProbe.single();
        return shape.getMax(Direction.Axis.Y) == 14.0 / 16.0 && shape.getMin(Direction.Axis.X) == 1.0 / 16.0
                ? TestResult.success() : TestResult.fail("the shape is " + shape);
    }

    /** Never constructed: it only reads the inherited constant. */
    static final class ShapeProbe extends ChestBlock {
        private ShapeProbe(Settings settings, Supplier<BlockEntityType<? extends ChestBlockEntity>> type) {
            super(settings, type);
        }

        static VoxelShape single() {
            return SINGLE_SHAPE;
        }
    }
}
