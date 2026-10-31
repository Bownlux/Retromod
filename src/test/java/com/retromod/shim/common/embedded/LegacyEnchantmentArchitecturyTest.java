/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fossils and Archaeology 9.3.4.0 (Forge 1.20.1) registers its enchantments through Architectury's
 * DeferredRegister, whose register() stopped NeoForge 1.21.1 with "Registry minecraft:enchantment
 * does not exist", because 1.21 made enchantments data.
 */
class LegacyEnchantmentArchitecturyTest {

    /** An old enchantment as the generated class keeps it: the id field registration fills in. */
    public static final class OldEnchantment {
        public String id;
    }

    @BeforeEach
    void reset() {
        DeferredRegister.REGISTERED.clear();
    }

    @Test
    @DisplayName("An enchantment entry is recorded and answered by a stand-in, and register() no longer fails")
    void enchantmentRegisterIsRecorded() {
        DeferredRegister<Object> enchantments = DeferredRegister.create("fossil", "minecraft:enchantment");
        OldEnchantment archeology = new OldEnchantment();

        RegistrySupplier<?> entry = (RegistrySupplier<?>) LegacyEnchantmentSupport.architecturyRegister(
                enchantments, "archeology", () -> archeology);
        assertDoesNotThrow(() -> LegacyEnchantmentSupport.architecturyRegisterAll(enchantments));

        assertSame(archeology, entry.get());
        assertTrue(entry.isPresent());
        assertSame(archeology, entry.getOrNull(), "a default method builds on the stand-in's answers");
        assertEquals("fossil:archeology", entry.getId());
        assertEquals("fossil:archeology", LegacyEnchantmentSupport.idOf(archeology), "recorded for the pack");
        List<Object> heard = new ArrayList<>();
        entry.listen(heard::add);
        assertEquals(List.of(archeology), heard);
    }

    @Test
    @DisplayName("Every other register reaches Architectury unchanged")
    void otherRegistersAreUntouched() {
        DeferredRegister<Object> items = DeferredRegister.create("fossil", "minecraft:item");

        Object entry = LegacyEnchantmentSupport.architecturyRegister(items, "amber", Object::new);
        LegacyEnchantmentSupport.architecturyRegisterAll(items);

        assertFalse(java.lang.reflect.Proxy.isProxyClass(entry.getClass()), "Architectury's own entry is returned");
        assertEquals(List.of("fossil:amber"), DeferredRegister.REGISTERED);
    }
}
