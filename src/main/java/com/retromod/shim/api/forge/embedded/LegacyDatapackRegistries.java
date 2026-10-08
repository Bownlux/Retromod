/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.forge.embedded;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands in for a Forge registry that 1.21 turned into a datapack registry, such as enchantments
 * and painting variants.
 *
 * <p>There is no registry object to hand a mod any more, only a key. The stand-in answers
 * {@code key()}, so {@code DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, modid)} builds
 * a register for that key. Datapack registries never fire the registration event, so the mod's Java
 * entries stay unbound, and every other registry call fails with a message that says why. A mod
 * whose enchantments extend {@code Enchantment} still fails when the first one loads, because 1.21
 * made that class a final record. Reflective because Retromod is not compiled against Minecraft.
 */
public final class LegacyDatapackRegistries {

    private static final Map<String, Object> STAND_INS = new ConcurrentHashMap<>();

    private LegacyDatapackRegistries() {}

    public static Object enchantments() {
        return standIn("ENCHANTMENT");
    }

    public static Object paintingVariants() {
        return standIn("PAINTING_VARIANT");
    }

    static Object standIn(String keyField) {
        return STAND_INS.computeIfAbsent(keyField, LegacyDatapackRegistries::create);
    }

    private static Object create(String keyField) {
        ClassLoader loader = LegacyDatapackRegistries.class.getClassLoader();
        try {
            Class<?> registry = Class.forName("net.minecraft.core.Registry", false, loader);
            Object key = Class.forName("net.minecraft.core.registries.Registries", false, loader)
                    .getField(keyField).get(null);
            return Proxy.newProxyInstance(registry.getClassLoader(), new Class<?>[] {registry},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "key" -> key;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "Retromod stand-in for the " + keyField + " datapack registry";
                        default -> throw new UnsupportedOperationException(keyField.toLowerCase()
                                + " entries come from datapacks since Minecraft 1.21, so a mod built for"
                                + " 1.20.1 cannot register or look them up from code (" + method.getName() + ")");
                    });
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build a stand-in for the " + keyField + " registry", e);
        }
    }
}
