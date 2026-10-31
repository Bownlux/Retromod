/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Builds 1.21.1's tag and block-list tool rules as the block holder sets that 1.21.2 rules take. */
public final class LegacyToolRules {

    private static final String RULE = "net.minecraft.world.item.component.Tool$Rule";

    private LegacyToolRules() {}

    /** {@code Rule.minesAndDrops(blocks, speed)} for a tag or a list of blocks. */
    public static Object minesAndDrops(Object blocks, float speed) {
        return rule("minesAndDrops", blocks, speed);
    }

    /** {@code Rule.deniesDrops(blocks)}. */
    public static Object deniesDrops(Object blocks) {
        return rule("deniesDrops", blocks, null);
    }

    /** {@code Rule.overrideSpeed(blocks, speed)}. */
    public static Object overrideSpeed(Object blocks, float speed) {
        return rule("overrideSpeed", blocks, speed);
    }

    /** {@code new Tool(rules, speed, damagePerBlock)}: 1.21.5 added whether it breaks blocks in creative, which it did. */
    public static Object tool(Object rules, float speed, int damagePerBlock) {
        try {
            return Class.forName("net.minecraft.world.item.component.Tool", true, loader())
                    .getConstructor(List.class, float.class, int.class, boolean.class)
                    .newInstance(rules, speed, damagePerBlock, true);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not build an old tool component", failure);
        }
    }

    private static Object rule(String factory, Object blocks, Float speed) {
        try {
            Class<?> holderSet = Class.forName("net.minecraft.core.HolderSet", true, loader());
            Object set = holderSet(blocks, holderSet);
            Class<?> rule = Class.forName(RULE, true, loader());
            if (speed == null) return rule.getMethod(factory, holderSet).invoke(null, set);
            return rule.getMethod(factory, holderSet, float.class).invoke(null, set, speed);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not build an old tool rule", failure);
        }
    }

    /** A tag through the registration lookup, which names tags before they load; a list as direct holders. */
    private static Object holderSet(Object blocks, Class<?> holderSet) throws ReflectiveOperationException {
        ClassLoader loader = loader();
        Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader);
        Object registry = builtIn.getField("BLOCK").get(null);
        Class<?> registryClass = Class.forName("net.minecraft.core.Registry", true, loader);
        if (blocks instanceof List<?> list) {
            Method wrap = registryClass.getMethod("wrapAsHolder", Object.class);
            List<Object> holders = new ArrayList<>();
            for (Object block : list) holders.add(wrap.invoke(registry, block));
            return holderSet.getMethod("direct", List.class).invoke(null, holders);
        }
        Object lookup = builtIn.getMethod("acquireBootstrapRegistrationLookup", registryClass).invoke(null, registry);
        return Class.forName("net.minecraft.core.HolderGetter", true, loader)
                .getMethod("getOrThrow", Class.forName("net.minecraft.tags.TagKey", true, loader)).invoke(lookup, blocks);
    }

    private static ClassLoader loader() {
        return LegacyToolRules.class.getClassLoader();
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(cause);
    }
}
