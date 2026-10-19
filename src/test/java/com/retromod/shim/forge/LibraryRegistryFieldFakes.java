/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Stand-ins for the NeoForge 1.21 registry types and for a library's registry class, renamed into
 * the real packages by {@link LibraryRegistryFieldsTest} and loaded on their own.
 */
final class LibraryRegistryFieldFakes {

    private LibraryRegistryFieldFakes() {}

    /** Becomes {@code net.minecraft.resources.ResourceKey}. */
    public static final class Key {
        public final String id;

        public Key(String id) {
            this.id = id;
        }
    }

    /** Becomes {@code net.minecraft.core.Holder}. */
    public interface Holder {
        Optional<Key> unwrapKey();
    }

    /** Becomes {@code net.neoforged.neoforge.registries.DeferredHolder}. */
    public static class DeferredHolder implements Holder, Supplier<Object> {
        public final Key key;

        public DeferredHolder(Key key) {
            this.key = key;
        }

        public static DeferredHolder create(Key key) {
            return new DeferredHolder(key);
        }

        @Override
        public Optional<Key> unwrapKey() {
            return Optional.of(key);
        }

        @Override
        public Object get() {
            return key.id;
        }
    }

    /** A holder a library made without a deferred register, like {@code Holder.Reference}. */
    public static final class ReferenceHolder implements Holder {
        private final Key key;

        public ReferenceHolder(Key key) {
            this.key = key;
        }

        @Override
        public Optional<Key> unwrapKey() {
            return Optional.of(key);
        }
    }

    /** A library's registry class as its NeoForge port declares it, field by field. */
    public static final class Entries {
        public static final Supplier<Object> KNIFE = DeferredHolder.create(new Key("farmersdelight:flint_knife"));
        public static final Holder NOURISHMENT = DeferredHolder.create(new Key("farmersdelight:nourishment"));
        public static final DeferredHolder TOMATO_CROP = DeferredHolder.create(new Key("farmersdelight:tomatoes"));
        public static final Holder COMFORT = new ReferenceHolder(new Key("farmersdelight:comfort"));
        public static final Supplier<Object> PLAIN = () -> "not a registry entry";
        public final Holder perInstance = COMFORT;
    }
}
