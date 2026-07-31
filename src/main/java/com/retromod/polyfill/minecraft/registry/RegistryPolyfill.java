/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.registry;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Polyfill for registry, utility, and core class renames across major MC versions.
 *
 * Covers multiple waves of class relocations:
 * - The Flattening (1.13): EnumFacing, EnumHand, ResourceLocation, math classes
 * - 1.19.3: Registry package moved from util.registry to core
 * - 1.20.5: ResourceLocation constructors replaced with factory methods
 * - Various: GUI, inventory, sound, damage source relocations
 *
 * These are the "glue" renames that nearly every old mod needs, since utility
 * classes like BlockPos, ResourceLocation, and Direction are used everywhere.
 */
public class RegistryPolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "Registry and Utility Class Renames";
    }

    @Override
    public String getCategory() {
        return "minecraft_vanilla";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            // Enum/utility renames
            "net/minecraft/util/EnumFacing",
            "net/minecraft/util/EnumHand",
            "net/minecraft/util/EnumActionResult",
            "net/minecraft/util/ResourceLocation",
            // Math classes
            "net/minecraft/util/math/BlockPos",
            "net/minecraft/util/math/Vec3d",
            "net/minecraft/util/math/Vec3i",
            "net/minecraft/util/math/AxisAlignedBB",
            "net/minecraft/util/math/RayTraceResult",
            "net/minecraft/util/math/BlockRayTraceResult",
            "net/minecraft/util/math/EntityRayTraceResult",
            // Misc utility
            "net/minecraft/util/DamageSource",
            "net/minecraft/util/NonNullList",
            "net/minecraft/util/SoundEvent",
            "net/minecraft/util/SoundCategory",
            // World classes (also covered by WorldPolyfill, but class redirects are idempotent)
            "net/minecraft/world/WorldServer",
            "net/minecraft/world/World",
            "net/minecraft/world/WorldProvider",
            // Biome/chunk
            "net/minecraft/world/biome/Biome",
            "net/minecraft/world/chunk/Chunk",
            // Inventory
            "net/minecraft/inventory/Container",
            "net/minecraft/inventory/IInventory",
            "net/minecraft/inventory/Slot",
            // GUI
            "net/minecraft/client/gui/GuiScreen",
            "net/minecraft/client/gui/inventory/GuiContainer",
            // Registry (1.19.3 move)
            "net/minecraft/util/registry/Registry",
            "net/minecraft/util/registry/RegistryKey"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        // No embedded stubs needed; pure class and method redirects
        return new String[]{};
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // Enum/utility renames (The Flattening, 1.13+)

        transformer.registerClassRedirect(
            "net/minecraft/util/EnumFacing",
            "net/minecraft/core/Direction");

        transformer.registerClassRedirect(
            "net/minecraft/util/EnumHand",
            "net/minecraft/world/InteractionHand");

        transformer.registerClassRedirect(
            "net/minecraft/util/EnumActionResult",
            "net/minecraft/world/InteractionResult");

        transformer.registerClassRedirect(
            "net/minecraft/util/ResourceLocation",
            "net/minecraft/resources/ResourceLocation");

        // Math class renames

        transformer.registerClassRedirect(
            "net/minecraft/util/math/BlockPos",
            "net/minecraft/core/BlockPos");

        transformer.registerClassRedirect(
            "net/minecraft/util/math/Vec3d",
            "net/minecraft/world/phys/Vec3");

        transformer.registerClassRedirect(
            "net/minecraft/util/math/Vec3i",
            "net/minecraft/core/Vec3i");

        transformer.registerClassRedirect(
            "net/minecraft/util/math/AxisAlignedBB",
            "net/minecraft/world/phys/AABB");

        transformer.registerClassRedirect(
            "net/minecraft/util/math/RayTraceResult",
            "net/minecraft/world/phys/HitResult");

        transformer.registerClassRedirect(
            "net/minecraft/util/math/BlockRayTraceResult",
            "net/minecraft/world/phys/BlockHitResult");

        transformer.registerClassRedirect(
            "net/minecraft/util/math/EntityRayTraceResult",
            "net/minecraft/world/phys/EntityHitResult");

        // Misc utility renames

        transformer.registerClassRedirect(
            "net/minecraft/util/DamageSource",
            "net/minecraft/world/damagesource/DamageSource");

        transformer.registerClassRedirect(
            "net/minecraft/util/NonNullList",
            "net/minecraft/core/NonNullList");

        transformer.registerClassRedirect(
            "net/minecraft/util/SoundEvent",
            "net/minecraft/sounds/SoundEvent");

        transformer.registerClassRedirect(
            "net/minecraft/util/SoundCategory",
            "net/minecraft/sounds/SoundSource");

        // World/dimension renames

        transformer.registerClassRedirect(
            "net/minecraft/world/WorldServer",
            "net/minecraft/server/level/ServerLevel");

        transformer.registerClassRedirect(
            "net/minecraft/world/World",
            "net/minecraft/world/level/Level");

        transformer.registerClassRedirect(
            "net/minecraft/world/WorldProvider",
            "net/minecraft/world/level/dimension/DimensionType");

        // Biome/chunk renames

        transformer.registerClassRedirect(
            "net/minecraft/world/biome/Biome",
            "net/minecraft/world/level/biome/Biome");

        transformer.registerClassRedirect(
            "net/minecraft/world/chunk/Chunk",
            "net/minecraft/world/level/chunk/LevelChunk");

        // Inventory renames

        transformer.registerClassRedirect(
            "net/minecraft/inventory/Container",
            "net/minecraft/world/inventory/AbstractContainerMenu");

        transformer.registerClassRedirect(
            "net/minecraft/inventory/IInventory",
            "net/minecraft/world/Container");

        transformer.registerClassRedirect(
            "net/minecraft/inventory/Slot",
            "net/minecraft/world/inventory/Slot");

        // GUI renames

        transformer.registerClassRedirect(
            "net/minecraft/client/gui/GuiScreen",
            "net/minecraft/client/gui/screens/Screen");

        transformer.registerClassRedirect(
            "net/minecraft/client/gui/inventory/GuiContainer",
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen");

        // Registry package move (1.19.3)
        // net.minecraft.util.registry -> net.minecraft.core / net.minecraft.resources

        transformer.registerClassRedirect(
            "net/minecraft/util/registry/Registry",
            "net/minecraft/core/Registry");

        transformer.registerClassRedirect(
            "net/minecraft/util/registry/RegistryKey",
            "net/minecraft/resources/ResourceKey");

        // ResourceLocation constructor -> static factory redirects.
        //   new ResourceLocation(namespace, path) -> ResourceLocation.fromNamespaceAndPath(namespace, path)
        //   new ResourceLocation(location)        -> ResourceLocation.parse(location)
        //
        // Gated to hosts >= 1.20.5, where the (String) / (String,String) constructors were removed for
        // the static factories. On 1.20.1-1.20.4 those ctors STILL exist, so a pure class-move leaves a
        // valid `new ResourceLocation(ns, path)`; firing this there corrupted the mod (#135 Wyrms of
        // Nyrus, #136, #121). And it must use registerConstructorRedirect, not a plain method redirect:
        // a ctor->factory conversion has to delete the NEW/DUP and emit INVOKESTATIC (which only
        // CtorRedirectPrePass does). A plain method redirect only swapped the Methodref, leaving the
        // uninitialized NEW on the stack + an INVOKESPECIAL against the factory -> "Type uninitialized 0
        // ... is not assignable" VerifyError. Both the pre-1.13 (util/) and relocated (resources/) source
        // names are registered so the prepass matches whichever a mod's bytecode uses.
        if (com.retromod.core.RetromodVersion.mcVersionExceeds(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.20.4")) {
            final String RL = "net/minecraft/resources/ResourceLocation";
            final String FROM_NS = "fromNamespaceAndPath";
            final String FROM_NS_DESC = "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;";
            final String PARSE_DESC = "(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;";
            for (String src : new String[]{"net/minecraft/util/ResourceLocation", RL}) {
                transformer.registerConstructorRedirect(
                    src, "(Ljava/lang/String;Ljava/lang/String;)V", RL, FROM_NS, FROM_NS_DESC);
                transformer.registerConstructorRedirect(
                    src, "(Ljava/lang/String;)V", RL, "parse", PARSE_DESC);
            }
        }
    }
}
