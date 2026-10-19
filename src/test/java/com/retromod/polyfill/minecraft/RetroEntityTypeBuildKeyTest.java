/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.RegistryIdBridgeSynthetic;
import com.retromod.testsupport.fakemc.FakeMinecraftHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.21.2 builds an entity type's translation key and loot table from the key given to
 * {@code EntityType.Builder.build}. Cake Cow (Forge 1.20.1) on NeoForge 26.3 built with the bare
 * name {@code "cake_cow"}, which the bridge parsed as {@code minecraft:cake_cow}: its summon
 * message read {@code entity.minecraft.cake_cow} and it dropped nothing.
 *
 * <p>Each case runs the bridge as it is embedded into a mod, relocated beside a deferred register
 * bridge, against stand-ins for the Minecraft classes it reads by name.
 */
class RetroEntityTypeBuildKeyTest {

    private static final String BASE = SyntheticEmbedder.embeddedBase("entity-key-test");
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";

    @Test
    @DisplayName("inside a deferred register's entity supplier, a bare name builds under the registered id")
    void usesTheEntityRegistrationKey() throws Exception {
        FakeMinecraftHost host = new FakeMinecraftHost(IDENTIFIER, BASE, Map.of());
        host.setRegistration(host.key(host.registry("ENTITY_TYPE"), "cake_cow:cake_cow"));

        assertEquals("ResourceKey[minecraft:entity_type / cake_cow:cake_cow]", build(host, "cake_cow"));
    }

    @Test
    @DisplayName("a registration for another registry, such as a spawn egg's, is not the entity's id")
    void ignoresAnotherRegistrysKey() throws Exception {
        FakeMinecraftHost host = new FakeMinecraftHost(IDENTIFIER, BASE, Map.of());
        host.setRegistration(host.key(host.registry("ITEM"), "cake_cow:cake_cow_spawn_egg"));

        assertEquals("ResourceKey[minecraft:entity_type / cake_cow:cake_cow]",
                build(host, "cake_cow", null, "cake_cow"));
    }

    @Test
    @DisplayName("an id a static field stamped for a block or item is not the entity's id")
    void ignoresAStampedBlockOrItemId() throws Exception {
        FakeMinecraftHost host = new FakeMinecraftHost(IDENTIFIER, BASE, Map.of());
        host.setRegistration(host.id("cake_cow:cake_block"));

        assertEquals("ResourceKey[minecraft:entity_type / cake_cow:cake_cow]",
                build(host, "cake_cow", null, "cake_cow"));
    }

    @Test
    @DisplayName("outside a registration: the registered id, then a namespaced name, then the mod's namespace")
    void namesTheKeyFromTheCallSite() throws Exception {
        FakeMinecraftHost host = new FakeMinecraftHost(IDENTIFIER, BASE, Map.of());

        assertEquals("ResourceKey[minecraft:entity_type / longwings:moth]",
                build(host, "old_moth_name", host.id("longwings:moth"), "longwings"));
        assertEquals("ResourceKey[minecraft:entity_type / longwings:moth]",
                build(host, "old_moth_name", "longwings:moth", "longwings"));
        assertEquals("ResourceKey[minecraft:entity_type / longwings:butterfly]",
                build(host, "longwings:butterfly", null, "othermod"));
        assertEquals("ResourceKey[minecraft:entity_type / cake_cow:cake_cow]",
                build(host, "cake_cow", null, "cake_cow"));
        assertEquals("ResourceKey[minecraft:entity_type / minecraft:pig]",
                build(host, "minecraft:pig", null, "cake_cow"), "a mod that wrote minecraft keeps it");
    }

    @Test
    @DisplayName("a host before 1.21.11 names the id class ResourceLocation")
    void readsResourceLocationHosts() throws Exception {
        FakeMinecraftHost host = new FakeMinecraftHost("net/minecraft/resources/ResourceLocation", BASE, Map.of());

        assertEquals("ResourceKey[minecraft:entity_type / cake_cow:cake_cow]",
                build(host, "cake_cow", null, "cake_cow"));
    }

    @Test
    @DisplayName("the deferred register bridges are looked up under the names they are generated with")
    void registrationBridgeNamesMatchTheGenerators() {
        assertEquals(RegistryIdBridgeSynthetic.INTERNAL.replace('/', '.'),
                RetroEntityTypeBuild.NEOFORGE_REGISTRATION_BRIDGE);
        assertEquals(RegistryIdBridgeSynthetic.FORGE_INTERNAL.replace('/', '.'),
                RetroEntityTypeBuild.FORGE_REGISTRATION_BRIDGE);
    }

    /** The call the redirect makes when the transformer found nothing at the call site. */
    private static String build(FakeMinecraftHost host, String name) throws Exception {
        return String.valueOf(host.bridge().getMethod("buildOfficial", Object.class, String.class)
                .invoke(null, host.builder(), name));
    }

    private static String build(FakeMinecraftHost host, String name, Object registeredId, String namespace)
            throws Exception {
        return String.valueOf(host.bridge().getMethod("buildOfficial", Object.class, String.class,
                Object.class, String.class).invoke(null, host.builder(), name, registeredId, namespace));
    }
}
