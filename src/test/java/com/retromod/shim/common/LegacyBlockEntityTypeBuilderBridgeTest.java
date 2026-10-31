/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

import static org.junit.jupiter.api.Assertions.*;

/** #366: ProjectE builds its block entity types with {@code BlockEntityType.Builder}, which 26.1 removed. */
class LegacyBlockEntityTypeBuilderBridgeTest {

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On 26.x the builder becomes the generated one, which verifies")
    void builderIsReplacedOn26() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyBlockEntityTypeBuilderBridge.register(transformer);

        assertEquals(LegacyBlockEntityTypeBuilderBridge.BUILDER,
                transformer.getClassRedirects().get(LegacyBlockEntityTypeBuilderBridge.OLD_BUILDER));
        new ClassReader(LegacyBlockEntityTypeBuilderBridge.generate()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    @Test
    @DisplayName("A host that still has the builder is left alone")
    void olderHostsKeepTheBuilder() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyBlockEntityTypeBuilderBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(LegacyBlockEntityTypeBuilderBridge.OLD_BUILDER));
    }
}
