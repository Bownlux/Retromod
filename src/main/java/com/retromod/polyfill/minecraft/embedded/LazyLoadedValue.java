/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * Polyfill for net.minecraft.util.LazyLoadedValue, removed in MC 26.1.
 * This is a simple lazy wrapper: takes a Supplier in its constructor and
 * caches the result on the first get() call. Thread-safe via double-checked
 * locking.
 *
 * Mods referencing net/minecraft/util/LazyLoadedValue will be redirected
 * to this class via a class redirect registered in MinecraftVanillaPolyfill.
 */
package com.retromod.polyfill.minecraft.embedded;

import java.util.function.Supplier;

/**
 * Drop-in replacement for the removed {@code net.minecraft.util.LazyLoadedValue}.
 *
 * <p>Mojang replaced {@code LazyLoadedValue<T>} with a plain {@code java.util.function.Supplier<T>}
 * (e.g. {@code InputConstants.Key.displayName} is now {@code Supplier<Component>}), so Retromod
 * redirects the removed TYPE to {@code Supplier} and rewrites {@code new LazyLoadedValue(supplier)}
 * to {@link #of(Supplier)}. This class implements {@code Supplier} so a memoizing instance is
 * assignable wherever the vanilla field/param is now a {@code Supplier} (crucially, it lets a mixin
 * {@code @Accessor} bind to the now-{@code Supplier} field). The lazy/thread-safe memoization the
 * old class guaranteed is preserved by this wrapper.
 *
 * @param <T> the type of the lazily computed value
 */
public class LazyLoadedValue<T> implements Supplier<T> {

    private final Supplier<T> supplier;
    private volatile T value;
    private volatile boolean computed;

    public LazyLoadedValue(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    /**
     * Factory the {@code new LazyLoadedValue(supplier)} constructor redirects to. Returns the
     * memoizing wrapper typed as {@code Supplier}, matching the redirected type.
     */
    public static <T> Supplier<T> of(Supplier<T> supplier) {
        return new LazyLoadedValue<>(supplier);
    }

    @Override
    public T get() {
        if (!computed) {
            synchronized (this) {
                if (!computed) {
                    value = supplier.get();
                    computed = true;
                }
            }
        }
        return value;
    }
}
