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
    @DisplayName("build() calls the Set constructor that Forge, NeoForge and vanilla 26.x all keep public")
    void buildUsesTheSetConstructor() {
        java.util.List<String> constructors = new java.util.ArrayList<>();
        new ClassReader(LegacyBlockEntityTypeBuilderBridge.generate()).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String member, String descriptor, boolean itf) {
                        if (owner.equals("net/minecraft/world/level/block/entity/BlockEntityType") && member.equals("<init>")) {
                            constructors.add(descriptor);
                        }
                    }
                };
            }
        }, 0);
        // Forge 26.2 has no Block... constructor; only NeoForge adds one.
        assertEquals(java.util.List.of(LegacyBlockEntityTypeBuilderBridge.TYPE_CONSTRUCTOR), constructors);
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
