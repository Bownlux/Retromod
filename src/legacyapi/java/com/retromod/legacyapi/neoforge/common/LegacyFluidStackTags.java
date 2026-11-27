/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.common;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/** Forge 1.20.1's fluid stack tag, which NeoForge's FluidStack dropped for data components in 1.20.5, kept in the custom data component. */
public final class LegacyFluidStackTags {

    // The custom data's own tag, which CustomData keeps private; reading it lets writes reach the stack.
    private static volatile Field heldTag;

    private LegacyFluidStackTags() {}

    /**
     * {@code getTag()}: the stack's tag, or null. A write to it reaches the stack, because the stack stores a fresh copy
     * each time and hands out that copy: copies of a stack share their custom data, so writing in place would change them all.
     */
    public static CompoundTag getTag(FluidStack stack) {
        CustomData data = stack.isEmpty() ? null : stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : store(stack, data.copyTag());
    }

    /** {@code getOrCreateTag()}: the stack's tag, made empty when it has none; an empty stack's tag is never kept. */
    public static CompoundTag getOrCreateTag(FluidStack stack) {
        CompoundTag tag = getTag(stack);
        if (tag != null) return tag;
        return stack.isEmpty() ? new CompoundTag() : store(stack, new CompoundTag());
    }

    public static boolean hasTag(FluidStack stack) {
        return !stack.isEmpty() && stack.get(DataComponents.CUSTOM_DATA) != null;
    }

    /** {@code new FluidStack(fluid, amount, tag)}: the tag becomes the stack's custom data. */
    public static FluidStack create(Fluid fluid, int amount, CompoundTag tag) {
        FluidStack stack = new FluidStack(fluid, amount);
        if (tag != null) setTag(stack, tag.copy());
        return stack;
    }

    /** {@code new FluidStack(stack, amount)}: the same fluid and data with another amount. */
    public static FluidStack copyWithAmount(FluidStack stack, int amount) {
        return stack.copyWithAmount(amount);
    }

    /** {@code getTranslationKey()}: the fluid's description id. */
    public static String getTranslationKey(FluidStack stack) {
        return stack.getDescriptionId();
    }

    /** {@code setTag(tag)}: null removes the tag, as it did. */
    public static void setTag(FluidStack stack, CompoundTag tag) {
        if (stack.isEmpty()) return;
        if (tag == null) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            store(stack, tag);
        }
    }

    /** {@code getChildTag(key)}: the compound under {@code key}, or null. */
    public static CompoundTag getChildTag(FluidStack stack, String key) {
        CompoundTag tag = getTag(stack);
        return tag == null ? null : tag.getCompound(key).orElse(null);
    }

    /** {@code getOrCreateChildTag(key)}: the compound under {@code key}, added when missing. */
    public static CompoundTag getOrCreateChildTag(FluidStack stack, String key) {
        CompoundTag tag = getOrCreateTag(stack);
        CompoundTag child = tag.getCompound(key).orElse(null);
        if (child == null) {
            child = new CompoundTag();
            tag.put(key, child);
        }
        return child;
    }

    /** {@code removeChildTag(key)}: the stack keeps no empty tag behind, as before. */
    public static void removeChildTag(FluidStack stack, String key) {
        CompoundTag tag = getTag(stack);
        if (tag == null) return;
        tag.remove(key);
        if (tag.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
    }

    // Stores the tag as the stack's custom data and returns the tag the component now holds.
    private static CompoundTag store(FluidStack stack, CompoundTag tag) {
        CustomData data = CustomData.of(tag);
        stack.set(DataComponents.CUSTOM_DATA, data);
        CompoundTag held = held(data);
        if (held != null) return held;
        LegacyLog.warnOnce("fluid stack tag " + LegacyFluidStackTags.class.getName(), "Retromod could not reach the tag"
                + " inside a fluid stack's custom data, so changes old mods make to a fluid stack's tag are not kept.");
        return tag;
    }

    private static CompoundTag held(CustomData data) {
        try {
            Field field = heldTag;
            if (field == null) {
                for (Field candidate : CustomData.class.getDeclaredFields()) {
                    if (!Modifier.isStatic(candidate.getModifiers()) && candidate.getType() == CompoundTag.class) field = candidate;
                }
                if (field == null) return null;
                field.setAccessible(true);
                heldTag = field;
            }
            return (CompoundTag) field.get(data);
        } catch (ReflectiveOperationException | RuntimeException closed) {
            return null;
        }
    }
}
