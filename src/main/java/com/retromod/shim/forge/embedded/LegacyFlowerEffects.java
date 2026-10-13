/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Holds a Forge flower's stew effect between the steps of its rebuilt {@code super(...)} call.
 * See {@link com.retromod.shim.forge.LegacyFlowerBlockBridge}.
 *
 * <p>Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class LegacyFlowerEffects {
    private LegacyFlowerEffects() {}

    private static final ThreadLocal<Object[]> PENDING = new ThreadLocal<>();

    /** Takes the old arguments, keeps the effect and its seconds, and hands back the properties. */
    public static Object stash(Object effectSupplier, int seconds, Object properties) {
        PENDING.set(new Object[] {holder(effectSupplier), (float) seconds});
        return properties;
    }

    /** The stashed effect as the {@code Holder<MobEffect>} the constructor takes now. */
    public static Object holder() {
        Object[] pending = PENDING.get();
        return pending == null ? null : pending[0];
    }

    public static float seconds() {
        Object[] pending = PENDING.get();
        PENDING.remove();
        return pending == null ? 0f : (Float) pending[1];
    }

    private static Object holder(Object effectSupplier) {
        try {
            // A cast, not a reflective call: the mod's lambda class is not accessible from here.
            Object effect = ((java.util.function.Supplier<?>) effectSupplier).get();
            ClassLoader loader = LegacyFlowerEffects.class.getClassLoader();
            Class<?> holderType = Class.forName("net.minecraft.core.Holder", false, loader);
            if (holderType.isInstance(effect)) return effect;
            Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
                    .getField("MOB_EFFECT").get(null);
            for (java.lang.reflect.Method method : registry.getClass().getMethods()) {
                if (method.getName().equals("wrapAsHolder") && method.getParameterCount() == 1) {
                    return method.invoke(registry, effect);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException("Retromod could not read the flower's stew effect", e);
        }
        throw new IllegalStateException("Retromod found no way to wrap the flower's stew effect");
    }
}
