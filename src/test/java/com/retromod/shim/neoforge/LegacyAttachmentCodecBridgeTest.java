/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

/** #366: ProjectE's data attachments serialize with a {@code Codec}, which NeoForge 26.x takes as a {@code MapCodec}. */
class LegacyAttachmentCodecBridgeTest {

    @Test
    @DisplayName("The generated serialize adapters verify")
    void generatedCallsVerify() {
        new ClassReader(LegacyAttachmentCodecBridge.generate()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }
}
