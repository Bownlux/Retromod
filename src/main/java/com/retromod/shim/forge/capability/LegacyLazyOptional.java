/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Stand-in for Forge's {@code LazyOptional}, embedded into a migrated mod under Forge's own class
 * name by {@link LegacyCapabilitySynthetics}. Forge's {@code NonNull*} functional interfaces are
 * redirected to their {@code java.util.function} twins, so every signature here uses those.
 *
 * <p>The value is resolved once and cached, like Forge's, because providers commonly hand out a
 * supplier that allocates the capability instance.
 */
public final class LegacyLazyOptional<T> {

    private static final LegacyLazyOptional<Void> EMPTY = new LegacyLazyOptional<>(null);

    private final Supplier<? extends T> supplier;
    private final Object lock = new Object();
    private volatile boolean resolved;
    private T value;
    private boolean valid = true;
    private final List<Consumer<LegacyLazyOptional<T>>> listeners = new ArrayList<>();

    private LegacyLazyOptional(Supplier<? extends T> supplier) {
        this.supplier = supplier;
    }

    public static <T> LegacyLazyOptional<T> of(Supplier<T> supplier) {
        return supplier == null ? empty() : new LegacyLazyOptional<>(supplier);
    }

    @SuppressWarnings("unchecked")
    public static <T> LegacyLazyOptional<T> empty() {
        return (LegacyLazyOptional<T>) EMPTY;
    }

    @SuppressWarnings("unchecked")
    public <X> LegacyLazyOptional<X> cast() {
        return (LegacyLazyOptional<X>) this;
    }

    private T getValue() {
        if (!valid || supplier == null) return null;
        if (!resolved) {
            synchronized (lock) {
                if (!resolved) {
                    value = supplier.get();
                    resolved = true;
                }
            }
        }
        return value;
    }

    public boolean isPresent() {
        return supplier != null && valid;
    }

    public void ifPresent(Consumer<? super T> consumer) {
        T current = getValue();
        if (current != null) consumer.accept(current);
    }

    public <U> Optional<U> map(Function<? super T, ? extends U> mapper) {
        T current = getValue();
        return current == null ? Optional.empty() : Optional.ofNullable(mapper.apply(current));
    }

    public <U> LegacyLazyOptional<U> lazyMap(Function<? super T, ? extends U> mapper) {
        if (!isPresent()) return empty();
        LegacyLazyOptional<U> mapped = new LegacyLazyOptional<>(() -> mapper.apply(getValue()));
        addListener(ignored -> mapped.invalidate());
        return mapped;
    }

    public Optional<T> filter(Predicate<? super T> predicate) {
        T current = getValue();
        return current != null && predicate.test(current) ? Optional.of(current) : Optional.empty();
    }

    public Optional<T> resolve() {
        return Optional.ofNullable(getValue());
    }

    public T orElse(T other) {
        T current = getValue();
        return current != null ? current : other;
    }

    public T orElseGet(Supplier<? extends T> other) {
        T current = getValue();
        return current != null ? current : other.get();
    }

    public T orElseThrow() {
        T current = getValue();
        if (current == null) throw new NoSuchElementException("No value present");
        return current;
    }

    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        T current = getValue();
        if (current == null) throw exceptionSupplier.get();
        return current;
    }

    public void addListener(Consumer<LegacyLazyOptional<T>> listener) {
        if (isPresent()) {
            synchronized (lock) {
                listeners.add(listener);
            }
        } else {
            listener.accept(this);
        }
    }

    public void removeListener(Consumer<LegacyLazyOptional<T>> listener) {
        synchronized (lock) {
            listeners.remove(listener);
        }
    }

    public void invalidate() {
        if (this == EMPTY || !valid) return;
        List<Consumer<LegacyLazyOptional<T>>> toNotify;
        synchronized (lock) {
            valid = false;
            toNotify = new ArrayList<>(listeners);
            listeners.clear();
        }
        for (Consumer<LegacyLazyOptional<T>> listener : toNotify) listener.accept(this);
    }
}
