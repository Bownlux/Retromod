/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.material;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.function.Function;

/** The block material class 1.20 removed, rebuilt under its old name for mods built before it. */
public class LegacyMaterial {

    // Every old material by name, with its map color and a trait code read by of(). Letters, in any order:
    // w you walk through it, g light passes, n not solid, r replaceable, f burns, l liquid, p pops when pushed, x immovable.
    public static final LegacyMaterial AIR = of(MapColor.NONE, "wgnr");
    public static final LegacyMaterial AMETHYST = of(MapColor.COLOR_PURPLE, "");
    public static final LegacyMaterial BAMBOO = of(MapColor.WOOD, "fp");
    public static final LegacyMaterial BAMBOO_SAPLING = of(MapColor.WOOD, "wfp");
    public static final LegacyMaterial BARRIER = of(MapColor.NONE, "x");
    public static final LegacyMaterial BUBBLE_COLUMN = of(MapColor.WATER, "wgnrlp");
    public static final LegacyMaterial BUILDABLE_GLASS = of(MapColor.NONE, "");
    public static final LegacyMaterial CACTUS = of(MapColor.PLANT, "gp");
    public static final LegacyMaterial CAKE = of(MapColor.NONE, "p");
    public static final LegacyMaterial CLAY = of(MapColor.CLAY, "");
    public static final LegacyMaterial CLOTH_DECORATION = of(MapColor.WOOL, "wgnf");
    public static final LegacyMaterial DECORATED_POT = of(MapColor.TERRACOTTA_RED, "p");
    public static final LegacyMaterial DECORATION = of(MapColor.NONE, "wgnp");
    public static final LegacyMaterial DIRT = of(MapColor.DIRT, "");
    public static final LegacyMaterial EGG = of(MapColor.PLANT, "p");
    public static final LegacyMaterial EXPLOSIVE = of(MapColor.FIRE, "gf");
    public static final LegacyMaterial FIRE = of(MapColor.NONE, "wgnrp");
    public static final LegacyMaterial FROGLIGHT = of(MapColor.NONE, "");
    public static final LegacyMaterial FROGSPAWN = of(MapColor.WATER, "wgnp");
    public static final LegacyMaterial GLASS = of(MapColor.NONE, "g");
    public static final LegacyMaterial GRASS = of(MapColor.GRASS, "");
    public static final LegacyMaterial HEAVY_METAL = of(MapColor.METAL, "x");
    public static final LegacyMaterial ICE = of(MapColor.ICE, "g");
    public static final LegacyMaterial ICE_SOLID = of(MapColor.ICE, "");
    public static final LegacyMaterial LAVA = of(MapColor.FIRE, "wgnrlp");
    public static final LegacyMaterial LEAVES = of(MapColor.PLANT, "gfp");
    public static final LegacyMaterial METAL = of(MapColor.METAL, "");
    public static final LegacyMaterial MOSS = of(MapColor.PLANT, "p");
    public static final LegacyMaterial NETHER_WOOD = of(MapColor.WOOD, "");
    public static final LegacyMaterial PISTON = of(MapColor.STONE, "x");
    public static final LegacyMaterial PLANT = of(MapColor.PLANT, "wgnp");
    public static final LegacyMaterial PORTAL = of(MapColor.NONE, "wgnx");
    public static final LegacyMaterial POWDER_SNOW = of(MapColor.SNOW, "gn");
    public static final LegacyMaterial REPLACEABLE_FIREPROOF_PLANT = of(MapColor.PLANT, "wgnrp");
    public static final LegacyMaterial REPLACEABLE_PLANT = of(MapColor.PLANT, "wgnrfp");
    public static final LegacyMaterial REPLACEABLE_WATER_PLANT = of(MapColor.WATER, "wgnrp");
    public static final LegacyMaterial SAND = of(MapColor.SAND, "");
    public static final LegacyMaterial SCULK = of(MapColor.COLOR_BLACK, "");
    public static final LegacyMaterial SHULKER_SHELL = of(MapColor.COLOR_PURPLE, "");
    public static final LegacyMaterial SNOW = of(MapColor.SNOW, "");
    public static final LegacyMaterial SPONGE = of(MapColor.COLOR_YELLOW, "");
    public static final LegacyMaterial STONE = of(MapColor.STONE, "");
    public static final LegacyMaterial STRUCTURAL_AIR = of(MapColor.NONE, "wgnr");
    public static final LegacyMaterial TOP_SNOW = of(MapColor.SNOW, "wgnrp");
    public static final LegacyMaterial VEGETABLE = of(MapColor.PLANT, "p");
    public static final LegacyMaterial WATER = of(MapColor.WATER, "wgnrlp");
    public static final LegacyMaterial WATER_PLANT = of(MapColor.WATER, "wgnp");
    public static final LegacyMaterial WEB = of(MapColor.WOOL, "wgp");
    public static final LegacyMaterial WOOD = of(MapColor.WOOD, "f");
    public static final LegacyMaterial WOOL = of(MapColor.WOOL, "f");

    private final MapColor color;
    private final PushReaction pushReaction;
    private final boolean blocksMotion;
    private final boolean flammable;
    private final boolean liquid;
    private final boolean solidBlocking;
    private final boolean replaceable;
    private final boolean solid;

    /** The old constructor, in its parameter order: color, liquid, solid, blocks motion, solid blocking, flammable, replaceable, push. */
    public LegacyMaterial(MapColor color, boolean liquid, boolean solid, boolean blocksMotion, boolean solidBlocking, boolean flammable,
            boolean replaceable, PushReaction pushReaction) {
        this.color = color;
        this.liquid = liquid;
        this.solid = solid;
        this.blocksMotion = blocksMotion;
        this.solidBlocking = solidBlocking;
        this.flammable = flammable;
        this.replaceable = replaceable;
        this.pushReaction = pushReaction;
    }

    private static LegacyMaterial of(MapColor color, String traits) {
        PushReaction push = traits.indexOf('x') >= 0 ? Reactions.IMMOVABLE : traits.indexOf('p') >= 0 ? Reactions.POPPED : Reactions.PUSHED;
        return new LegacyMaterial(color, traits.indexOf('l') >= 0, traits.indexOf('n') < 0, traits.indexOf('w') < 0, traits.indexOf('g') < 0,
                traits.indexOf('f') >= 0, traits.indexOf('r') >= 0, push);
    }

    public boolean isLiquid() {
        return liquid;
    }

    public boolean isSolid() {
        return solid;
    }

    public boolean blocksMotion() {
        return blocksMotion;
    }

    public boolean isFlammable() {
        return flammable;
    }

    public boolean isReplaceable() {
        return replaceable;
    }

    public boolean isSolidBlocking() {
        return solidBlocking;
    }

    public PushReaction getPushReaction() {
        return pushReaction;
    }

    public MapColor getColor() {
        return color;
    }

    /** {@code state.getMaterial()}: the material whose job the state does now, read from its fluid, tags and flags. */
    public static LegacyMaterial fromState(Object value) {
        BlockState state = (BlockState) value;
        if (state.isAir()) return AIR;
        FluidState fluid = state.getFluidState();
        if (state.liquid() && fluid.is(FluidTags.LAVA)) return LAVA;
        if (state.liquid()) return WATER;
        if (state.is(BlockTags.LEAVES)) return LEAVES;
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)) return state.ignitedByLava() ? WOOD : NETHER_WOOD;
        if (state.is(BlockTags.WOOL_CARPETS)) return CLOTH_DECORATION;
        if (state.is(BlockTags.WOOL)) return WOOL;
        if (state.is(BlockTags.FIRE)) return FIRE;
        if (state.is(BlockTags.ICE)) return ICE;
        if (state.is(Blocks.SNOW)) return TOP_SNOW;
        if (state.is(BlockTags.SNOW)) return SNOW;
        if (state.is(Blocks.GRASS_BLOCK)) return GRASS;
        if (state.is(BlockTags.SAND)) return SAND;
        if (state.is(BlockTags.DIRT)) return DIRT;
        if (state.is(BlockTags.IMPERMEABLE)) return GLASS;
        // Anything else is judged by its flags: a plant you can walk through, a soft block, or stone.
        if (!state.isSolid()) {
            if (!fluid.isEmpty()) return state.canBeReplaced() ? REPLACEABLE_WATER_PLANT : WATER_PLANT;
            if (state.canBeReplaced()) return state.ignitedByLava() ? REPLACEABLE_PLANT : REPLACEABLE_FIREPROOF_PLANT;
            return state.is(BlockTags.FLOWERS) || state.is(BlockTags.CROPS) ? PLANT : DECORATION;
        }
        if (state.getPistonPushReaction() == Reactions.IMMOVABLE) return HEAVY_METAL;
        return state.ignitedByLava() ? WOOD : STONE;
    }

    /** {@code Properties.of(material)}: block settings that do what the material did, as 1.20 spelled them out. */
    public static BlockBehaviour.Properties properties(LegacyMaterial material) {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of().mapColor(material.color).pushReaction(material.pushReaction);
        if (!material.blocksMotion) properties = properties.noCollision();
        else if (!material.solidBlocking) properties = properties.noOcclusion();
        if (material.flammable) properties = properties.ignitedByLava();
        if (material.liquid) properties = properties.liquid();
        if (material.replaceable) properties = properties.replaceable();
        // A material marked not solid kept its block out of the solid checks, whatever its shape.
        return material.solid ? properties : properties.forceSolidOff();
    }

    public static BlockBehaviour.Properties properties(LegacyMaterial material, MapColor color) {
        return properties(material).mapColor(color);
    }

    public static BlockBehaviour.Properties properties(LegacyMaterial material, DyeColor color) {
        return properties(material).mapColor(color);
    }

    public static BlockBehaviour.Properties properties(LegacyMaterial material, Function<BlockState, MapColor> color) {
        return properties(material).mapColor(color);
    }

    // 26.3 renamed the push reactions, so each is found by its name on either host.
    static final class Reactions {
        static final PushReaction PUSHED = reaction("NORMAL", "PUSH_PULL");
        static final PushReaction POPPED = reaction("DESTROY", "POPPED");
        static final PushReaction IMMOVABLE = reaction("BLOCK", "IMMOVEABLE");

        private static PushReaction reaction(String name, String renamed) {
            try {
                return PushReaction.valueOf(name);
            } catch (IllegalArgumentException gone) {
                return PushReaction.valueOf(renamed);
            }
        }
    }

    /** The old material builder, which starts from a solid, pushable block that does not burn. */
    public static class Builder {
        private final MapColor color;
        private PushReaction pushReaction = Reactions.PUSHED;
        private boolean blocksMotion = true;
        private boolean flammable;
        private boolean liquid;
        private boolean replaceable;
        private boolean solid = true;
        private boolean solidBlocking = true;

        public Builder(MapColor color) {
            this.color = color;
        }

        public Builder liquid() {
            liquid = true;
            return this;
        }

        public Builder nonSolid() {
            solid = false;
            return this;
        }

        public Builder noCollider() {
            blocksMotion = false;
            return this;
        }

        public Builder notSolidBlocking() {
            solidBlocking = false;
            return this;
        }

        public Builder flammable() {
            flammable = true;
            return this;
        }

        public Builder replaceable() {
            replaceable = true;
            return this;
        }

        public Builder destroyOnPush() {
            pushReaction = Reactions.POPPED;
            return this;
        }

        public Builder notPushable() {
            pushReaction = Reactions.IMMOVABLE;
            return this;
        }

        public LegacyMaterial build() {
            return new LegacyMaterial(color, liquid, solid, blocksMotion, solidBlocking, flammable, replaceable, pushReaction);
        }
    }
}
