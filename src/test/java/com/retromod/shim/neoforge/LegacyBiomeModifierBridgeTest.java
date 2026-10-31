/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #377: a 1.20.1 or 1.21.1 biome modifier implements the three-argument modify that NeoForge 26.3 replaced. */
class LegacyBiomeModifierBridgeTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An old modifier implements the generated interface, whose new modify calls the old one")
    void oldModifierGetsTheNewMethod() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyBiomeModifierBridge.registerReplacement(transformer);

        ClassNode modifier = new ClassNode();
        new ClassReader(transformer.transformClass(oldModifier(), "mod/AddDragons.class")).accept(modifier, 0);
        assertEquals(List.of(LegacyBiomeModifierBridge.LEGACY), modifier.interfaces);

        byte[] generated = transformer.getSyntheticClasses().get(LegacyBiomeModifierBridge.LEGACY);
        new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        ClassNode legacy = new ClassNode();
        new ClassReader(generated).accept(legacy, 0);
        MethodNode bridge = legacy.methods.stream()
                .filter(m -> m.desc.equals(LegacyBiomeModifierBridge.NEW_MODIFY)).findFirst().orElseThrow();
        assertEquals(0, bridge.access & ACC_ABSTRACT, "the new modify is a default method");
        assertEquals(List.of(LegacyBiomeModifierBridge.MODIFIER), legacy.interfaces);
    }

    /** {@code class AddDragons implements BiomeModifier { void modify(Holder, Phase, Builder) {} }} */
    private static byte[] oldModifier() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/AddDragons", null, "java/lang/Object",
                new String[]{LegacyBiomeModifierBridge.MODIFIER});
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "modify", LegacyBiomeModifierBridge.OLD_MODIFY, null, null);
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
