/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.core.component;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Proxy;

/** Stands in for the {@code HIDE_ADDITIONAL_TOOLTIP} component, which 1.21.5 folded into the tooltip display component. */
public final class LegacyHiddenTooltip {

    // A type no registry holds, so reading it from an item finds nothing and nothing stores it.
    private static final DataComponentType<?> TYPE = (DataComponentType<?>) Proxy.newProxyInstance(
            DataComponentType.class.getClassLoader(), new Class<?>[] {DataComponentType.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "toString": return "retromod:hide_additional_tooltip";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                }
            });

    private LegacyHiddenTooltip() {}

    /** What an old read of {@code DataComponents.HIDE_ADDITIONAL_TOOLTIP} now gets. */
    public static DataComponentType<?> type() {
        return TYPE;
    }

    /** {@code builder.set(type, value)}; the old flag is dropped, since the host could neither save nor send it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static DataComponentMap.Builder set(DataComponentMap.Builder builder, DataComponentType type, Object value) {
        return type == TYPE ? builder : builder.set(type, value);
    }

    /** {@code properties.component(type, value)}, so an item's default components never hold the stand-in. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Item.Properties component(Item.Properties properties, DataComponentType type, Object value) {
        return type == TYPE ? properties : properties.component(type, value);
    }

    /** {@code builder.set(type, value)} on a component patch, dropping the stand-in the same way. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static DataComponentPatch.Builder set(DataComponentPatch.Builder builder, DataComponentType type, Object value) {
        return type == TYPE ? builder : builder.set(type, value);
    }

    /** {@code stack.set(type, value)}, dropping the old flag the same way and answering that nothing was there. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object set(ItemStack stack, DataComponentType type, Object value) {
        return type == TYPE ? null : stack.set(type, value);
    }
}
