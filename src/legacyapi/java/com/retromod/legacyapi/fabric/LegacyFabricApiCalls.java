/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric;

import net.fabricmc.fabric.api.biome.v1.BiomeModificationContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluid;

/** Fabric API 1.20.1 and 1.21.1 calls that 26.x Fabric API reshaped, answered through the members it kept. */
public final class LegacyFabricApiCalls {

    private LegacyFabricApiCalls() {}

    /** {@code FluidVariant.of(Fluid, CompoundTag)}: the tag becomes the variant's custom data. */
    public static FluidVariant fluidOf(Fluid fluid, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return FluidVariant.of(fluid);
        return FluidVariant.of(fluid, DataComponentPatch.builder().set(DataComponents.CUSTOM_DATA, CustomData.of(tag)).build());
    }

    /** {@code FluidVariant.getNbt()}: the variant's custom data as a tag, or null when it has none. */
    public static CompoundTag fluidNbt(FluidVariant variant) {
        CustomData data = variant.getComponents().get(DataComponents.CUSTOM_DATA);
        return data == null || data.isEmpty() ? null : data.copyTag();
    }

    /** {@code FluidVariant.hasNbt()}. */
    public static boolean fluidHasNbt(FluidVariant variant) {
        return fluidNbt(variant) != null;
    }

    /** {@code addCarver(GenerationStep.Carving, ResourceKey)}: 1.21.2 keeps one carver list, so the step is dropped. */
    public static void addCarver(BiomeModificationContext.GenerationSettingsContext context, Object step, ResourceKey<?> carver) {
        context.addCarver(carver);
    }

    /** {@code removeCarver(GenerationStep.Carving, ResourceKey)}, from the one carver list. */
    public static boolean removeCarver(BiomeModificationContext.GenerationSettingsContext context, Object step,
            ResourceKey<?> carver) {
        return context.removeCarver(carver);
    }
}
