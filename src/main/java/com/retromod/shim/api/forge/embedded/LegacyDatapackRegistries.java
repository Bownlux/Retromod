/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.forge.embedded;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands in for a Forge registry that became a datapack registry: biomes in 1.19.3, and
 * enchantments and painting variants in 1.21.
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
        return standIn("ENCHANTMENT", "1.21");
    }

    public static Object paintingVariants() {
        return standIn("PAINTING_VARIANT", "1.21");
    }

    public static Object biomes() {
        return standIn("BIOME", "1.19.3");
    }

    static Object standIn(String keyField, String dataSince) {
        return STAND_INS.computeIfAbsent(keyField, key -> create(key, dataSince));
    }

    private static Object create(String keyField, String dataSince) {
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
                                + " entries come from datapacks since Minecraft " + dataSince + ", so a mod"
                                + " built before that cannot register or look them up from code ("
                                + method.getName() + ")");
                    });
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build a stand-in for the " + keyField + " registry", e);
        }
    }
}
