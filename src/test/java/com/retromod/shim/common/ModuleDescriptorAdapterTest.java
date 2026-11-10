/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertSame;

/** A jar's module-info.class has no superclass, which per-class adapters must pass through unchanged. */
class ModuleDescriptorAdapterTest {

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
    }

    @Test
    @DisplayName("The button and renderer adapters leave a module descriptor alone on a 26.x host")
    void moduleDescriptorPassesThrough() {
        // JEI, spark and Kiwi ship one, and the button adapter's null lookup failed their whole jar.
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        byte[] descriptor = moduleInfo();
        assertSame(descriptor, LegacyAbstractButtonBridge.apply(descriptor));
        assertSame(descriptor, LegacyLivingRendererAdapter.apply(descriptor));
    }

    /** A minimal module-info.class, as javac writes for a mod that declares a module. */
    private static byte[] moduleInfo() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor module = cw.visitModule("examplemod", 0, null);
        module.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
        module.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
