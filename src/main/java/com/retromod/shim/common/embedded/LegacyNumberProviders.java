/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

/**
 * The old loot number factories, and the consumers that took the old {@code NumberProvider}, on a
 * 26.3 host. 26.3 split the providers into an int family and a float family, and its consumers take
 * a {@code Holder} of one of them, so each consumer here converts the value to the family its new
 * signature names.
 *
 * <p>Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class LegacyNumberProviders {

    private static final String NUMBER = "net.minecraft.world.level.storage.loot.providers.number.";
    private static final String INTS = NUMBER + "ints.";
    private static final String FLOATS = NUMBER + "floats.";
    private static final String HOLDER = "net.minecraft.core.Holder";

    private LegacyNumberProviders() {}

    /** The old {@code ConstantValue.exactly(float)} and {@code new ConstantValue(float)}. */
    public static LegacyNumberProvider exactly(float value) {
        return LegacyNumberValue.constant(value);
    }

    /** The old {@code UniformGenerator.between(float, float)}. */
    public static LegacyNumberProvider between(float min, float max) {
        return LegacyNumberValue.uniform(LegacyNumberValue.constant(min), LegacyNumberValue.constant(max));
    }

    /** The old {@code new UniformGenerator(NumberProvider, NumberProvider)}. */
    public static LegacyNumberProvider uniform(Object min, Object max) {
        return LegacyNumberValue.uniform(min, max);
    }

    /** The old {@code BinomialDistributionGenerator.binomial(int, float)}, now an int provider. */
    public static Object binomial(int trials, float probability) {
        return construct(INTS + "BinomialDistributionGenerator", new String[] {HOLDER, HOLDER},
                holder(construct(INTS + "ConstantValue", new Class<?>[] {int.class}, trials)),
                holder(construct(FLOATS + "ConstantValue", new Class<?>[] {float.class}, probability)));
    }

    /** {@code new TradeSet(trades, amount, allowDuplicates, randomSequence)} with an int amount. */
    public static Object tradeSet(Object trades, Object amount, boolean allowDuplicates,
            Optional<?> randomSequence) {
        try {
            Class<?> tradeSet = type("net.minecraft.world.item.trading.TradeSet");
            Constructor<?> constructor = tradeSet.getConstructor(
                    type("net.minecraft.core.HolderSet"), type(HOLDER), boolean.class, Optional.class);
            return constructor.newInstance(trades, holder(toIntProvider(amount)), allowDuplicates, randomSequence);
        } catch (ReflectiveOperationException e) {
            throw failure("build a villager trade set", e);
        }
    }

    /** {@code LootPool.Builder.setRolls}, whose rolls are an int provider on 26.3. */
    public static Object setRolls(Object builder, Object rolls) {
        return callBuilder(builder, "setRolls", holder(toIntProvider(rolls)));
    }

    /** {@code LootPool.Builder.setBonusRolls}, whose bonus rolls are a float provider on 26.3. */
    public static Object setBonusRolls(Object builder, Object bonusRolls) {
        return callBuilder(builder, "setBonusRolls", holder(toFloatProvider(bonusRolls)));
    }

    /** {@code SetItemCountFunction.setCount(count)}, whose count is an int provider on 26.3. */
    public static Object setCount(Object count) {
        return invokeStatic("net.minecraft.world.level.storage.loot.functions.SetItemCountFunction",
                "setCount", new Class<?>[] {type(HOLDER)}, holder(toIntProvider(count)));
    }

    /** {@code SetItemCountFunction.setCount(count, add)}. */
    public static Object setCount(Object count, boolean add) {
        return invokeStatic("net.minecraft.world.level.storage.loot.functions.SetItemCountFunction",
                "setCount", new Class<?>[] {type(HOLDER), boolean.class}, holder(toIntProvider(count)), add);
    }

    /**
     * Turns a loot number from either era into a 26.3 int provider. An old constant rounds the way
     * the old {@code NumberProvider.getInt} did, with {@code Math.round}.
     */
    public static Object toIntProvider(Object provider) {
        if (provider == null) throw new NullPointerException("loot number provider");
        if (provider instanceof LegacyNumberValue legacy) {
            if (legacy.isConstant()) {
                return construct(INTS + "ConstantValue", new Class<?>[] {int.class}, Math.round(legacy.constant()));
            }
            return construct(INTS + "UniformGenerator", new String[] {HOLDER, HOLDER},
                    holder(toIntProvider(legacy.min())), holder(toIntProvider(legacy.max())));
        }
        if (type(INTS + "ContextIntProvider").isInstance(provider)) return provider;
        if (type(FLOATS + "ContextFloatProvider").isInstance(provider)) {
            return construct(INTS + "FromFloat", new String[] {HOLDER}, holder(provider));
        }
        throw unsupported(provider);
    }

    /** Turns a loot number from either era into a 26.3 float provider. */
    public static Object toFloatProvider(Object provider) {
        if (provider == null) throw new NullPointerException("loot number provider");
        if (provider instanceof LegacyNumberValue legacy) {
            if (legacy.isConstant()) {
                return construct(FLOATS + "ConstantValue", new Class<?>[] {float.class}, legacy.constant());
            }
            return construct(FLOATS + "UniformGenerator", new String[] {HOLDER, HOLDER},
                    holder(toFloatProvider(legacy.min())), holder(toFloatProvider(legacy.max())));
        }
        if (type(FLOATS + "ContextFloatProvider").isInstance(provider)) return provider;
        if (type(INTS + "ContextIntProvider").isInstance(provider)) {
            return construct(FLOATS + "FromInt", new String[] {HOLDER}, holder(provider));
        }
        throw unsupported(provider);
    }

    private static Object holder(Object value) {
        return invokeStatic(HOLDER, "direct", new Class<?>[] {Object.class}, value);
    }

    private static Object callBuilder(Object builder, String name, Object holder) {
        try {
            return type("net.minecraft.world.level.storage.loot.LootPool$Builder")
                    .getMethod(name, type(HOLDER)).invoke(builder, holder);
        } catch (InvocationTargetException e) {
            throw rethrow(e);
        } catch (ReflectiveOperationException e) {
            throw failure("call LootPool.Builder." + name, e);
        }
    }

    private static Object invokeStatic(String owner, String name, Class<?>[] parameters, Object... args) {
        try {
            return type(owner).getMethod(name, parameters).invoke(null, args);
        } catch (InvocationTargetException e) {
            throw rethrow(e);
        } catch (ReflectiveOperationException e) {
            throw failure("call " + owner + "." + name, e);
        }
    }

    private static Object construct(String owner, String[] parameterTypes, Object... args) {
        Class<?>[] parameters = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameters.length; i++) parameters[i] = type(parameterTypes[i]);
        return construct(owner, parameters, args);
    }

    private static Object construct(String owner, Class<?>[] parameters, Object... args) {
        try {
            return type(owner).getConstructor(parameters).newInstance(args);
        } catch (InvocationTargetException e) {
            throw rethrow(e);
        } catch (ReflectiveOperationException e) {
            throw failure("build " + owner, e);
        }
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name, false, LegacyNumberProviders.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw failure("find " + name, e);
        }
    }

    private static RuntimeException rethrow(InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(cause);
    }

    private static IllegalStateException failure(String action, Exception cause) {
        return new IllegalStateException("Retromod could not " + action
                + " for a mod's old loot number provider on this Minecraft version", cause);
    }

    private static IllegalArgumentException unsupported(Object provider) {
        return new IllegalArgumentException("Retromod cannot convert the loot number provider "
                + provider.getClass().getName() + " to the int and float providers 26.3 uses. A mod that"
                + " implements the old NumberProvider itself needs a version built for 26.3.");
    }
}
