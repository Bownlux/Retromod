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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Fabric networking v0 stand-ins warn when a mod registers a channel, not on every launch. */
class FabricNetworkingV0BridgeTest {

    private static final String GENERATED = "com/retromod/generated/legacynetwork/";
    private static final String IDENTIFIER = "net/minecraft/class_2960";
    private static final List<String> OTHER_MINECRAFT_CLASSES = List.of(
            "net/minecraft/class_2540", "net/minecraft/class_2596",
            "net/minecraft/class_1657", "net/minecraft/class_3222");

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
        String err = StandInTestLoader.captureErr(() -> new FabricNetworkingV0Bridge().registerRedirects(transformer));

        assertFalse(err.contains("WARN"), "a launch with no v0 mod must not warn about v0 networking: " + err);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ServerSidePacketRegistry", "ClientSidePacketRegistry"})
    @DisplayName("The first registered channel is named once, and every register call still completes")
    void firstRegisteredChannelWarnsOnce(String registry) throws Exception {
        new FabricNetworkingV0Bridge().registerRedirects(transformer);
        StandInTestLoader loader = standIns(transformer.getSyntheticClasses());
        Class<?> registryType = loader.load(GENERATED + registry);
        Object instance = registryType.getField("INSTANCE").get(null);
        Method register = registryType.getMethod("register", loader.load(IDENTIFIER),
                loader.load(GENERATED + "PacketConsumer"));
        Object channel = loader.load(IDENTIFIER).getConstructor().newInstance();
        Object[] futures = new Object[2];

        String err = StandInTestLoader.captureErr(() -> {
            futures[0] = register.invoke(instance, channel, null);
            futures[1] = register.invoke(instance, channel, null);
        });

        // The test logger puts its thread, level, and logger name before each message.
        List<String> lines = err.lines().filter(line -> line.contains("[Retromod]")).toList();
        assertEquals(1, lines.size(), "a mod that registers channels gets one warning: " + err);
        assertTrue(lines.get(0).contains("WARN") && lines.get(0).contains("examplemod:sync"),
                "the warning names the first channel: " + lines.get(0));
        assertTrue(((CompletableFuture<?>) futures[0]).isDone() && ((CompletableFuture<?>) futures[1]).isDone(),
                "register still returns a completed future each time");
    }

    private static StandInTestLoader standIns(Map<String, byte[]> synthetics) {
        StandInTestLoader loader = new StandInTestLoader();
        synthetics.forEach((name, bytes) -> {
            if (name.startsWith(GENERATED)) loader.add(name, bytes);
        });
        loader.addMinecraftClass(IDENTIFIER, "examplemod:sync");
        OTHER_MINECRAFT_CLASSES.forEach(name -> loader.addMinecraftClass(name, name));
        return loader;
    }
}
