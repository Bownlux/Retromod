/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Fabric renderer stand-in warns when a mod asks for the renderer, not on every launch. */
class FabricRendererMaterialBridgeTest {

    private static final String GENERATED = "com/retromod/generated/legacyrenderer/";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Registering the bridge logs no warning, because every Fabric launch registers it")
    void registrationIsQuiet() throws Exception {
        String err = StandInTestLoader.captureErr(() -> new FabricRendererMaterialBridge().registerRedirects(transformer));

        assertFalse(err.contains("WARN"), "a launch with no renderer API mod must not warn about it: " + err);
    }

    @Test
    @DisplayName("Asking for the renderer warns once, and the stand-in still hands out materials")
    void getRendererWarnsOnce() throws Exception {
        new FabricRendererMaterialBridge().registerRedirects(transformer);
        StandInTestLoader loader = standIns(transformer.getSyntheticClasses());
        Class<?> access = loader.load(GENERATED + "RendererAccess");
        Object instance = access.getField("INSTANCE").get(null);
        Object[] renderers = new Object[2];

        String err = StandInTestLoader.captureErr(() -> {
            renderers[0] = access.getMethod("getRenderer").invoke(instance);
            renderers[1] = access.getMethod("getRenderer").invoke(instance);
        });

        // The test logger puts its thread, level, and logger name before each message.
        List<String> lines = err.lines().filter(line -> line.contains("[Retromod]")).toList();
        assertEquals(1, lines.size(), "a mod that asks for the renderer gets one warning: " + err);
        assertTrue(lines.get(0).contains("WARN") && lines.get(0).contains("RendererAccess"),
                "the warning says which API the mod used: " + lines.get(0));
        assertSame(renderers[0], renderers[1], "getRenderer keeps returning the same stand-in");
        Object finder = loader.load(GENERATED + "Renderer").getMethod("materialFinder").invoke(renderers[0]);
        Object material = loader.load(GENERATED + "MaterialFinder").getMethod("find").invoke(finder);
        assertNotNull(material, "the stand-in renderer still hands out a material");
    }

    private static StandInTestLoader standIns(Map<String, byte[]> synthetics) {
        StandInTestLoader loader = new StandInTestLoader();
        synthetics.forEach((name, bytes) -> {
            if (name.startsWith(GENERATED)) loader.add(name, bytes);
        });
        loader.addMinecraftClass("net/minecraft/class_2960", "examplemod:material");
        return loader;
    }
}
