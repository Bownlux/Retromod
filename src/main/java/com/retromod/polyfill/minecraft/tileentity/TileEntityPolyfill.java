/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.tileentity;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Polyfill for TileEntity -> BlockEntity class renames.
 *
 * The entire {@code net.minecraft.tileentity} package was renamed to
 * {@code net.minecraft.world.level.block.entity} and all classes changed
 * from the "TileEntity" naming convention to "BlockEntity" in Mojang mappings.
 *
 * Also handles the ITickableTileEntity interface removal: in 1.17+ the tickable
 * tile entity pattern was replaced by the BlockEntityTicker functional interface
 * registered via BlockEntityType. Old mods implementing ITickableTileEntity need
 * the interface reference removed (handled by the class redirect to a marker).
 */
public class TileEntityPolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "TileEntity to BlockEntity Renames";
    }

    @Override
    public String getCategory() {
        return "minecraft_vanilla";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            "net/minecraft/tileentity/TileEntity",
            "net/minecraft/tileentity/TileEntityChest",
            "net/minecraft/tileentity/TileEntityFurnace",
            "net/minecraft/tileentity/TileEntityHopper",
            "net/minecraft/tileentity/TileEntitySign",
            "net/minecraft/tileentity/TileEntityBanner",
            "net/minecraft/tileentity/TileEntityBeacon",
            "net/minecraft/tileentity/TileEntityBrewingStand",
            "net/minecraft/tileentity/TileEntityDispenser",
            "net/minecraft/tileentity/TileEntityDropper",
            "net/minecraft/tileentity/TileEntityEnchantmentTable",
            "net/minecraft/tileentity/TileEntityEnderChest",
            "net/minecraft/tileentity/TileEntityPiston",
            "net/minecraft/tileentity/TileEntitySkull",
            "net/minecraft/tileentity/TileEntityShulkerBox",
            "net/minecraft/tileentity/TileEntityCommandBlock",
            "net/minecraft/tileentity/ITickableTileEntity"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        // No embedded stubs needed; pure class redirects
        return new String[]{};
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // TileEntity -> BlockEntity renames
        // net.minecraft.tileentity.* -> net.minecraft.world.level.block.entity.*

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntity",
            "net/minecraft/world/level/block/entity/BlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityChest",
            "net/minecraft/world/level/block/entity/ChestBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityFurnace",
            "net/minecraft/world/level/block/entity/FurnaceBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityHopper",
            "net/minecraft/world/level/block/entity/HopperBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntitySign",
            "net/minecraft/world/level/block/entity/SignBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityBanner",
            "net/minecraft/world/level/block/entity/BannerBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityBeacon",
            "net/minecraft/world/level/block/entity/BeaconBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityBrewingStand",
            "net/minecraft/world/level/block/entity/BrewingStandBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityDispenser",
            "net/minecraft/world/level/block/entity/DispenserBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityDropper",
            "net/minecraft/world/level/block/entity/DropperBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityEnchantmentTable",
            "net/minecraft/world/level/block/entity/EnchantingTableBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityEnderChest",
            "net/minecraft/world/level/block/entity/EnderChestBlockEntity");

        // Lives in the piston package, not block.entity, and has since before 1.21.1 (checked
        // against Mojang's 1.21.1 mappings and the 26.3 jar). The old destination resolved to
        // nothing, so this traded one missing class for another.
        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityPiston",
            "net/minecraft/world/level/block/piston/PistonMovingBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntitySkull",
            "net/minecraft/world/level/block/entity/SkullBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityShulkerBox",
            "net/minecraft/world/level/block/entity/ShulkerBoxBlockEntity");

        transformer.registerClassRedirect(
            "net/minecraft/tileentity/TileEntityCommandBlock",
            "net/minecraft/world/level/block/entity/CommandBlockEntity");

        // ITickableTileEntity has no direct equivalent in modern MC.
        // In 1.17+ the tick pattern uses BlockEntityTicker<T> registered via
        // Block.getTicker(). We redirect the interface reference so old mods
        // that implement it don't crash with ClassNotFoundException. The actual
        // tick() call wiring requires a version shim at the block level.
        transformer.registerClassRedirect(
            "net/minecraft/tileentity/ITickableTileEntity",
            "net/minecraft/world/level/block/entity/BlockEntity");
    }
}
