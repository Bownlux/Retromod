/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bridges that read NeoForge registered nothing offline, so a CLI translation for 26.3 left Carry On's
 * {@code AttachmentType.Builder.serialize(Codec)} and Citadel's three-argument {@code BiomeModifier.modify}
 * unbridged. With no loader to read, the target version now answers.
 */
class OfflineHostVersionTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The attachment Codec bridge registers for NeoForge targets from 1.21.6")
    void attachmentCodecs() {
        assertRegistersFrom("1.21.6", "1.21.5", LegacyAttachmentCodecBridge::register, LegacyAttachmentCodecBridge.CALLS);
    }

    @Test
    @DisplayName("The biome modifier bridge registers for NeoForge targets from 26.3")
    void biomeModifiers() {
        assertRegistersFrom("26.3", "26.2", LegacyBiomeModifierBridge::register, LegacyBiomeModifierBridge.LEGACY);
    }

    @Test
    @DisplayName("The crafting remainder bridge registers for NeoForge targets from 26.1")
    void craftingRemainders() {
        assertRegistersFrom("26.1", "1.21.11", LegacyCraftingRemainderBridge::register, LegacyCraftingRemainderBridge.CALLS);
    }

    @Test
    @DisplayName("The old handler capabilities come back for NeoForge targets from 1.21.9")
    void handlerCapabilities() {
        assertRegistersFrom("1.21.9", "1.21.8", LegacyHandlerCapabilityBridge::register, LegacyHandlerCapabilityBridge.ITEM_CAPS);
    }

    /** Registered for NeoForge at {@code from}, not at {@code before}, and never for another loader. */
    private void assertRegistersFrom(String from, String before, Consumer<RetromodTransformer> register, String synthetic) {
        McReflect.setForceNeoForge(true);
        assertEquals(true, registers(from, register, synthetic), "NeoForge " + from);
        assertEquals(false, registers(before, register, synthetic), "NeoForge " + before);
        McReflect.setForceNeoForge(false);
        assertEquals(false, registers(from, register, synthetic), "another loader at " + from);
    }

    private boolean registers(String target, Consumer<RetromodTransformer> register, String synthetic) {
        RetromodVersion.TARGET_MC_VERSION = target;
        transformer.clearRedirectsForTesting();
        register.accept(transformer);
        return transformer.getSyntheticClasses().containsKey(synthetic);
    }
}
