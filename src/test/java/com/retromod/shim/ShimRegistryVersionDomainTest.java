/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.core.AuxiliaryVersionShim;
import com.retromod.core.VersionShim;
import com.retromod.shim.api.common.BotaniaApiShim;
import com.retromod.shim.api.fabric.RenderingBackendShim;
import com.retromod.shim.api.forge.ForgeCapabilitiesShim;
import com.retromod.shim.api.forge.JeiApiShim;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShimRegistryVersionDomainTest {

    @Test
    void libraryVersionsAreNotComparedWithMinecraftVersions() {
        ShimRegistry registry = new ShimRegistry();
        JeiApiShim jei = new JeiApiShim();
        ForgeCapabilitiesShim capabilities = new ForgeCapabilitiesShim();
        registry.register(jei);
        registry.register(capabilities);

        List<VersionShim> on1201 = registry.findApiShimsForLoader("forge", "1.20.1");
        assertTrue(on1201.contains(jei));
        assertFalse(on1201.contains(capabilities));

        List<VersionShim> on12111 = registry.findApiShimsForLoader("neoforge", "1.21.11");
        assertTrue(on12111.contains(jei));
        assertTrue(on12111.contains(capabilities));
    }

    @Test
    void minecraftVersionedApiRepairsStayOutsideTheGraph() {
        ShimRegistry registry = new ShimRegistry();
        BotaniaApiShim botania = new BotaniaApiShim();
        registry.register(botania);

        assertTrue(registry.findShimChain("fabric", "1.18", "1.21").isEmpty());
        assertFalse(registry.findApiShimsForLoader("fabric", "1.20.1").contains(botania));
        assertTrue(registry.findApiShimsForLoader("fabric", "1.21").contains(botania));
    }

    @Test
    void unknownSourceSelectionUsesTheSameVersionDomainRules() {
        ShimRegistry registry = new ShimRegistry();
        JeiApiShim jei = new JeiApiShim();
        ForgeCapabilitiesShim capabilities = new ForgeCapabilitiesShim();
        registry.register(jei);
        registry.register(capabilities);

        List<VersionShim> selected = registry.findShimsForUnknownSource("forge", "1.20.1");
        assertTrue(selected.contains(jei));
        assertFalse(selected.contains(capabilities));
    }

    @Test
    void quiltUsesFabricVersionAndApiProviders() {
        ShimRegistry registry = new ShimRegistry();
        TestFabricShim versionShim = new TestFabricShim();
        RenderingBackendShim apiShim = new RenderingBackendShim();
        registry.register(versionShim);
        registry.register(apiShim);

        assertTrue(registry.findShimChain("quilt", "1.20", "1.21")
                .contains(versionShim));
        assertTrue(registry.findApiShimsForLoader("quilt", "26.2")
                .contains(apiShim));
    }

    @Test
    void everyBundledApiProviderDeclaresTheAuxiliaryContract() throws Exception {
        String descriptor = "META-INF/services/com.retromod.core.VersionShim";
        ClassLoader loader = getClass().getClassLoader();
        try (var input = loader.getResourceAsStream(descriptor)) {
            assertTrue(input != null, "the VersionShim service descriptor must be present");
            try (var reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                for (String line : reader.lines().toList()) {
                    String provider = line.strip();
                    if (provider.isEmpty() || provider.startsWith("#")
                            || !provider.startsWith("com.retromod.shim.api.")) {
                        continue;
                    }
                    Object instance = Class.forName(provider, true, loader)
                            .getDeclaredConstructor().newInstance();
                    assertInstanceOf(AuxiliaryVersionShim.class, instance, provider);
                }
            }
        }
        assertInstanceOf(AuxiliaryVersionShim.class, new RenderingBackendShim());
    }

    private static final class TestFabricShim implements VersionShim {
        @Override public String getShimName() { return "Fabric-family fixture"; }
        @Override public String getSourceVersion() { return "1.20"; }
        @Override public String getTargetVersion() { return "1.21"; }
        @Override public String getModLoaderType() { return "fabric"; }
        @Override public void registerRedirects(com.retromod.core.RetromodTransformer transformer) {}
    }
}
