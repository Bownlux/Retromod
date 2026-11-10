/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.fluids;

import com.retromod.legacyapi.minecraft.core.LegacyRegistryContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/** Forge's FluidStack members that NeoForge's FluidStack lacks, answered through the members it has. */
public final class LegacyForgeFluidStacks {

    private static final String FLUID_STACK = "net.neoforged.neoforge.fluids.FluidStack";
    private static final String FLUID_NAME = "FluidName";
    private static final String AMOUNT = "Amount";
    private static final String EMPTY_FLUID = "minecraft:empty";
    // Forge's Tag key held free-form fluid NBT, so the components get a key that old data never used.
    private static final String COMPONENTS = "RetromodFluidComponents";
    // Tanks compare stacks every tick, so each host member is looked up once.
    private static volatile Members members;

    private LegacyForgeFluidStacks() {}

    /** {@code stack.isFluidEqual(other)}: the same fluid with the same data. */
    public static boolean isFluidEqual(Object stack, Object other) {
        return (boolean) invoke(members().sameFluidSameComponents, null, stack, other);
    }

    /** {@code stack.containsFluid(other)}: the same fluid and data, and at least as much. */
    public static boolean containsFluid(Object stack, Object other) {
        return isFluidEqual(stack, other) && amount(stack) >= amount(other);
    }

    /** {@code stack.isFluidStackIdentical(other)}: the same fluid, data and amount. */
    public static boolean isFluidStackIdentical(Object stack, Object other) {
        return (boolean) invoke(members().matches, null, stack, other);
    }

    /** {@code stack.getDisplayName()}, which NeoForge calls the hover name. */
    public static Object getDisplayName(Object stack) {
        return invoke(members().hoverName, stack);
    }

    /** {@code stack.getRawFluid()}: the stack's fluid. NeoForge keeps no public raw fluid, so an empty stack answers empty. */
    public static Object getRawFluid(Object stack) {
        return invoke(members().fluid, stack);
    }

    /** {@code stack.writeToNBT(tag)}: Forge's {@code FluidName} and {@code Amount} keys, plus the fluid's components under a key of Retromod's own. */
    public static CompoundTag writeToNBT(Object stack, CompoundTag tag) {
        Members host = members();
        // Mods write into their block entity's own tag, so the codec's id and components keys must never reach it.
        if ((boolean) invoke(host.isEmpty, stack)) {
            tag.putString(FLUID_NAME, EMPTY_FLUID);
            tag.putInt(AMOUNT, 0);
            tag.put(COMPONENTS, new CompoundTag());
            return tag;
        }
        Object ops = LegacyRegistryContext.provider("FluidStack.writeToNBT").createSerializationContext(NbtOps.INSTANCE);
        Object result = invoke(host.encode, host.codec, stack, ops, new CompoundTag());
        Optional<?> written = (Optional<?>) invoke(host.result, result);
        if (written.isEmpty() || !(written.get() instanceof CompoundTag saved) || saved.get("id") == null || saved.get("amount") == null) {
            throw new IllegalStateException("Retromod could not save the fluid stack " + stack + ": " + result);
        }
        tag.put(FLUID_NAME, saved.get("id"));
        tag.put(AMOUNT, saved.get("amount"));
        // An empty compound replaces the components of a fluid this tag held before.
        Tag components = saved.get("components");
        tag.put(COMPONENTS, components != null ? components : new CompoundTag());
        return tag;
    }

    /** {@code FluidStack.loadFluidStackFromNBT(tag)}: empty unless the tag holds Forge's {@code FluidName}, as in Forge. */
    public static Object loadFluidStackFromNBT(CompoundTag tag) {
        Members host = members();
        Tag name = tag.get(FLUID_NAME);
        // The name prints quoted, and Forge saved an empty tank as the empty fluid, which the codec does not read.
        if (name == null || name.toString().equals("\"" + EMPTY_FLUID + "\"")) return host.empty;
        CompoundTag current = new CompoundTag();
        current.put("id", name);
        Tag amount = tag.get(AMOUNT);
        if (amount != null) current.put("amount", amount);
        // A 1.20.1 world's fluid NBT under Forge's Tag key is not carried over.
        Tag components = tag.get(COMPONENTS);
        if (components instanceof CompoundTag patch && !patch.isEmpty()) {
            CompoundTag withComponents = current.copy();
            withComponents.put("components", patch);
            Object read = parse(host, withComponents);
            if (read != null) return read;
            // A component this host no longer knows still keeps the fluid and its amount.
        }
        Object read = parse(host, current);
        return read != null ? read : host.empty;
    }

    private static Object parse(Members host, CompoundTag fields) {
        Object ops = LegacyRegistryContext.provider("FluidStack.loadFluidStackFromNBT").createSerializationContext(NbtOps.INSTANCE);
        Optional<?> read = (Optional<?>) invoke(host.result, invoke(host.parse, host.optionalCodec, ops, fields));
        return read.isPresent() ? read.get() : null;
    }

    private static int amount(Object stack) {
        return (int) invoke(members().amount, stack);
    }

    private static Members members() {
        Members found = members;
        if (found == null) {
            found = new Members();
            members = found;
        }
        return found;
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod could not call " + method + " on a fluid stack", e);
        }
    }

    /** The host's FluidStack members and codecs, read once. */
    private static final class Members {
        final Method sameFluidSameComponents;
        final Method matches;
        final Method hoverName;
        final Method fluid;
        final Method isEmpty;
        final Method amount;
        final Object codec;
        final Object optionalCodec;
        final Object empty;
        final Method encode;
        final Method parse;
        final Method result;

        Members() {
            try {
                ClassLoader loader = LegacyForgeFluidStacks.class.getClassLoader();
                Class<?> stack = Class.forName(FLUID_STACK, true, loader);
                sameFluidSameComponents = stack.getMethod("isSameFluidSameComponents", stack, stack);
                matches = stack.getMethod("matches", stack, stack);
                hoverName = stack.getMethod("getHoverName");
                fluid = stack.getMethod("getFluid");
                isEmpty = stack.getMethod("isEmpty");
                amount = stack.getMethod("getAmount");
                codec = stack.getField("CODEC").get(null);
                optionalCodec = stack.getField("OPTIONAL_CODEC").get(null);
                empty = stack.getField("EMPTY").get(null);
                // Through DataFixerUpper's public interfaces: the codecs' own classes are not public.
                Class<?> ops = Class.forName("com.mojang.serialization.DynamicOps", true, loader);
                encode = Class.forName("com.mojang.serialization.Encoder", true, loader).getMethod("encode", Object.class, ops, Object.class);
                parse = Class.forName("com.mojang.serialization.Decoder", true, loader).getMethod("parse", ops, Object.class);
                result = Class.forName("com.mojang.serialization.DataResult", true, loader).getMethod("result");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Retromod could not find NeoForge's FluidStack members", e);
            }
        }
    }
}
