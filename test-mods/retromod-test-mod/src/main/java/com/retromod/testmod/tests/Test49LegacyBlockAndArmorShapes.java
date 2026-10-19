/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.AmethystClusterBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ExperienceDroppingBlock;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.item.ArmorMaterials;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.intprovider.UniformIntProvider;

/**
 * #283: block constructors 1.20.3 reshaped, a vanilla armor material read as the old interface,
 * and a block's menu provider asked through another block, which 1.21 made protected. Each one
 * stopped Scorched Guns' registration. Blocks built outside registration only prove linkage.
 */
public final class Test49LegacyBlockAndArmorShapes implements Test {

    @Override
    public String description() {
        return "#283 old block constructors, a vanilla armor material, and a protected block call";
    }

    @Override
    public TestResult run() {
        // Each part is checked, so one host gap does not hide the others.
        java.util.List<String> failures = new java.util.ArrayList<>();
        String blocks = linkage("ExperienceDroppingBlock and AmethystClusterBlock constructors", Shapes::buildOldBlocks);
        if (blocks != null) failures.add(blocks);
        try {
            if (!Shapes.vanillaArmorIsAMaterial()) failures.add("ArmorMaterials.LEATHER read as null");
        } catch (ClassCastException e) {
            failures.add("ArmorMaterials.LEATHER was cast to the 1.21 holder class: " + e.getMessage());
        } catch (LinkageError e) {
            failures.add("ArmorMaterials.LEATHER did not link: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        String menu = linkage("a menu provider asked through another block", Shapes::menuThroughAnotherBlock);
        if (menu != null) failures.add(menu);
        return failures.isEmpty() ? TestResult.success() : TestResult.fail(String.join("; ", failures));
    }

    /** Holds the calls, so a type a host lacks fails this test instead of loading the suite. */
    private static final class Shapes {
        static void buildOldBlocks() {
            new ExperienceDroppingBlock(AbstractBlock.Settings.create());
            new ExperienceDroppingBlock(AbstractBlock.Settings.create(), UniformIntProvider.create(2, 5));
            new AmethystClusterBlock(3, 4, AbstractBlock.Settings.create());
        }

        static boolean vanillaArmorIsAMaterial() {
            ArmorMaterial material = ArmorMaterials.LEATHER;
            return material != null;
        }

        static void menuThroughAnotherBlock() {
            BlockState state = Blocks.CRAFTING_TABLE.getDefaultState();
            state.getBlock().createScreenHandlerFactory(state, null, BlockPos.ORIGIN);
        }
    }

    /** Only a linkage error counts: anything thrown after the call links is the missing world. */
    private static String linkage(String label, Runnable probe) {
        try {
            probe.run();
            return null;
        } catch (IncompatibleClassChangeError | NoClassDefFoundError | VerifyError e) {
            return label + " did not link: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (Throwable linkedThenRefused) {
            return null;
        }
    }
}
