/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.block;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Polyfill for block class renames from The Flattening (1.13+).
 *
 * Pre-flattening mods reference block classes in the old {@code net.minecraft.block}
 * package; these were renamed and relocated to {@code net.minecraft.world.level.block}.
 * This provider registers the class redirects.
 */
public class BlockPolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "Minecraft Block Class Renames";
    }

    @Override
    public String getCategory() {
        return "minecraft_vanilla";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            "net/minecraft/block/BlockContainer",
            "net/minecraft/block/BlockBush",
            "net/minecraft/block/BlockCrops",
            "net/minecraft/block/BlockDoor",
            "net/minecraft/block/BlockFence",
            "net/minecraft/block/BlockFenceGate",
            "net/minecraft/block/BlockLeaves",
            "net/minecraft/block/BlockLog",
            "net/minecraft/block/BlockOre",
            "net/minecraft/block/BlockSlab",
            "net/minecraft/block/BlockStairs",
            "net/minecraft/block/BlockWall",
            "net/minecraft/block/BlockPane",
            "net/minecraft/block/BlockTorch",
            "net/minecraft/block/BlockRedstoneOre",
            "net/minecraft/block/BlockGrass",
            "net/minecraft/block/BlockFlower",
            "net/minecraft/block/BlockButton",
            "net/minecraft/block/BlockPressurePlate",
            "net/minecraft/block/BlockTrapDoor",
            "net/minecraft/block/BlockChest",
            "net/minecraft/block/BlockFurnace",
            "net/minecraft/block/BlockWorkbench",
            "net/minecraft/block/BlockAnvil",
            "net/minecraft/block/BlockGlass",
            "net/minecraft/block/BlockStainedGlass",
            "net/minecraft/block/BlockCauldron",
            "net/minecraft/block/BlockHopper"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        // class redirects handle these renames, no stubs needed
        return new String[]{};
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // 1.13 Flattening renames, Mojang official names
        transformer.registerClassRedirect(
            "net/minecraft/block/BlockContainer",
            "net/minecraft/world/level/block/BaseEntityBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockBush",
            "net/minecraft/world/level/block/BushBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockCrops",
            "net/minecraft/world/level/block/CropBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockDoor",
            "net/minecraft/world/level/block/DoorBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockFence",
            "net/minecraft/world/level/block/FenceBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockFenceGate",
            "net/minecraft/world/level/block/FenceGateBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockLeaves",
            "net/minecraft/world/level/block/LeavesBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockLog",
            "net/minecraft/world/level/block/RotatedPillarBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockOre",
            "net/minecraft/world/level/block/DropExperienceBlock");

        // OreBlock is the 1.13-1.18 post-Flattening name; 1.19 renamed it to DropExperienceBlock
        // (ore-XP moved onto the block). A 1.18.x Forge/NeoForge mod that extends or references it
        // dies NoClassDefFoundError on a 1.19+ host (#145, Isle of Berk). Same target as BlockOre
        // above. (If a mod also calls the old super constructor, a follow-on NoSuchMethodError may
        // surface, since DropExperienceBlock's ctor signature differs; that is a separate bridge.)
        transformer.registerClassRedirect(
            "net/minecraft/world/level/block/OreBlock",
            "net/minecraft/world/level/block/DropExperienceBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockSlab",
            "net/minecraft/world/level/block/SlabBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockStairs",
            "net/minecraft/world/level/block/StairBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockWall",
            "net/minecraft/world/level/block/WallBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockPane",
            "net/minecraft/world/level/block/IronBarsBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockTorch",
            "net/minecraft/world/level/block/TorchBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockRedstoneOre",
            "net/minecraft/world/level/block/RedStoneOreBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockGrass",
            "net/minecraft/world/level/block/GrassBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockFlower",
            "net/minecraft/world/level/block/FlowerBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockButton",
            "net/minecraft/world/level/block/ButtonBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockPressurePlate",
            "net/minecraft/world/level/block/PressurePlateBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockTrapDoor",
            "net/minecraft/world/level/block/TrapDoorBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockChest",
            "net/minecraft/world/level/block/ChestBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockFurnace",
            "net/minecraft/world/level/block/FurnaceBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockWorkbench",
            "net/minecraft/world/level/block/CraftingTableBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockAnvil",
            "net/minecraft/world/level/block/AnvilBlock");

        // GlassBlock was folded into TransparentBlock, which keeps the same
        // (BlockBehaviour.Properties) constructor. Verified absent on 26.1 through 26.3, and
        // already gone in Mojang's 1.21.1 mappings, so the old destination never resolved there.
        transformer.registerClassRedirect(
            "net/minecraft/block/BlockGlass",
            "net/minecraft/world/level/block/TransparentBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockStainedGlass",
            "net/minecraft/world/level/block/StainedGlassBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockCauldron",
            "net/minecraft/world/level/block/CauldronBlock");

        transformer.registerClassRedirect(
            "net/minecraft/block/BlockHopper",
            "net/minecraft/world/level/block/HopperBlock");
    }
}
