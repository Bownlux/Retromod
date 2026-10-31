/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package dev.architectury.registry.registries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Test stand-in for Architectury's DeferredRegister: entries are recorded lazily, and register()
 * fails for a registry with no registrar, as the real one does for minecraft:enchantment on 1.21.
 */
public class DeferredRegister<T> {
    public static final List<String> REGISTERED = new ArrayList<>();

    private final String modId;
    private final String registry;
    private final List<Entry<T>> entries = new ArrayList<>();

    private DeferredRegister(String modId, String registry) {
        this.modId = modId;
        this.registry = registry;
    }

    public static <T> DeferredRegister<T> create(String modId, String registry) {
        return new DeferredRegister<>(modId, registry);
    }

    public <R extends T> RegistrySupplier<R> register(String name, Supplier<? extends R> supplier) {
        @SuppressWarnings("unchecked")
        Entry<R> entry = new Entry<>(modId + ":" + name, (Supplier<R>) supplier);
        entries.add((Entry<T>) (Entry<?>) entry);
        return entry;
    }

    public void register() {
        if (registry.equals("minecraft:enchantment")) {
            throw new IllegalArgumentException("Registry ResourceKey[minecraft:root / minecraft:enchantment] does not exist!");
        }
        for (Entry<T> entry : entries) REGISTERED.add(entry.getId());
    }

    // Package-private, like Architectury's own entry class.
    class Entry<R> implements RegistrySupplier<R> {
        private final String id;
        private final Supplier<R> supplier;

        Entry(String id, Supplier<R> supplier) {
            this.id = id;
            this.supplier = supplier;
        }

        @Override public String getRegistryId() { return registry; }
        @Override public String getId() { return id; }
        @Override public boolean isPresent() { return REGISTERED.contains(id); }
        @Override public R get() { return supplier.get(); }
        @Override public Object getRegistrar() { throw new IllegalArgumentException("no registrar for " + registry); }
    }
}
