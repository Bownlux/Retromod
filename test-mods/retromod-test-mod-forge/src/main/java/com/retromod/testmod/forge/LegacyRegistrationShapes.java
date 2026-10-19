/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * #283: Scorched Guns 1.20.1 builds its content with calls that later versions reshaped, and each
 * one stopped its registration on NeoForge 1.21.1. Kept out of the mod class, so a shape a host
 * cannot take fails only its own check instead of the whole mod.
 */
final class LegacyRegistrationShapes {

    private LegacyRegistrationShapes() {}

    /** Built with Forge's {@code Rarity.create}, which NeoForge 1.21 adds as an enum extension. */
    static final class Rarities {
        static final Rarity TEST = Rarity.create("retromod_test_mod_forge.test", ChatFormatting.BLUE);
    }

    /** A vanilla rarity comes back only when the rarity was not added. */
    static boolean rarityWasAdded() {
        return Rarities.TEST != null && Rarities.TEST != Rarity.COMMON;
    }

    /** Declares its modifier with the UUID as text, as 1.20.1 effects did in their constructor. */
    static final class SlowingEffect extends MobEffect {
        SlowingEffect() {
            super(MobEffectCategory.HARMFUL, 0x445500);
            addAttributeModifier(Attributes.MOVEMENT_SPEED, "7107DE5E-7CE8-4030-940E-514C1F160890",
                    -0.1, AttributeModifier.Operation.MULTIPLY_TOTAL);
        }
    }

    static boolean effectDeclaresItsModifier() {
        return new SlowingEffect() != null;
    }

    /** A cauldron that hands its parent a plain interaction map, as Scorched Guns' acid cauldron does. */
    static final class AcidCauldron extends AbstractCauldronBlock {
        AcidCauldron(BlockBehaviour.Properties properties) {
            super(properties, Map.of());
        }

        @Override
        protected double getContentHeight(BlockState state) {
            return 0.9375;
        }

        @Override
        public boolean isFull(BlockState state) {
            return true;
        }
    }

    /** The 1.20.1 block constructors 1.20.3 and NeoForge 1.21 reshaped. */
    static void buildOldBlocks() {
        new DropExperienceBlock(BlockBehaviour.Properties.of());
        new DropExperienceBlock(BlockBehaviour.Properties.of(), UniformInt.of(2, 5));
        new AmethystClusterBlock(3, 4, BlockBehaviour.Properties.of());
        new StairBlock(() -> Blocks.STONE.defaultBlockState(), BlockBehaviour.Properties.of());
        new AcidCauldron(BlockBehaviour.Properties.of());
    }

    /** The vanilla table a mod adds its bucket to; reading it proves the field links. */
    static void readEmptyCauldronTable() {
        Map<Item, CauldronInteraction> table = CauldronInteraction.EMPTY;
    }

    /** A vanilla armor material read as the interface, which 1.20.5 made a record. */
    static boolean vanillaArmorIsAMaterial() {
        ArmorMaterial material = ArmorMaterials.LEATHER;
        return material != null;
    }

    /** A block's menu provider asked through another block, which 1.21 made protected. */
    static void menuThroughAnotherBlock() {
        BlockState state = Blocks.CRAFTING_TABLE.defaultBlockState();
        state.getBlock().getMenuProvider(state, null, BlockPos.ZERO);
    }
}
