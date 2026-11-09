/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Balm's creative tab supplier registers the tab it returns, and the deferred register then adds
 * it again. Forge 26.2 refused the second add with a duplicate key, so the supplier's own call must
 * hand the tab back when it is the entry being built.
 */
class LateVanillaRegistrationTest {

    /** A registry key: the registry's key and the entry's id, compared by value. */
    public static final class ResourceKey {
        final Object registry;
        final Object id;

        private ResourceKey(Object registry, Object id) {
            this.registry = registry;
            this.id = id;
        }

        public static ResourceKey create(Object registry, Object id) {
            return new ResourceKey(registry, id);
        }

        @Override public boolean equals(Object other) {
            return other instanceof ResourceKey key && key.registry.equals(registry) && key.id.equals(id);
        }

        @Override public int hashCode() {
            return Objects.hash(registry, id);
        }
    }

    public static final class FakeRegistry {
        public String key() {
            return "minecraft:creative_mode_tab";
        }
    }

    @Test
    @DisplayName("the supplier's registration of the entry it builds is handed back")
    void theEntryBeingBuiltIsRecognized() {
        ResourceKey building = ResourceKey.create("minecraft:creative_mode_tab", "waystones:waystones");

        assertTrue(LateVanillaRegistration.buildsEntry(building, new FakeRegistry(), "waystones:waystones"));
        assertTrue(LateVanillaRegistration.buildsEntry(building, new FakeRegistry(), building),
                "an id given as the key itself compares directly");
    }

    @Test
    @DisplayName("another entry, or no supplier running, registers as before")
    void otherRegistrationsGoThrough() {
        ResourceKey building = ResourceKey.create("minecraft:creative_mode_tab", "waystones:waystones");

        assertFalse(LateVanillaRegistration.buildsEntry(building, new FakeRegistry(), "waystones:other"));
        assertFalse(LateVanillaRegistration.buildsEntry(null, new FakeRegistry(), "waystones:waystones"));
    }
}
