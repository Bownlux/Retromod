/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** An SRG id an old Forge mod passes as text for reflection reaches the Mojang name a Mojang-named host has. */
class SrgReflectionNameTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("SRG field and method ids in strings take their Mojang names; other text is left alone")
    void srgTextIsRenamed() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        transformer.registerSrgNameMappings(Map.of("m_7023_", "travel"), Map.of("f_19853_", "level"));

        List<String> texts = texts(transformer.transformClass(reflector(), "mod/Reflector"));

        assertTrue(texts.contains("level"), texts.toString());
        assertTrue(texts.contains("travel"), texts.toString());
        assertTrue(texts.contains("f_99999_"), "an id the table lacks is kept: " + texts);
        assertTrue(texts.contains("hello_world_"), "ordinary text is kept: " + texts);
    }

    @Test
    @DisplayName("a host that does not run Mojang member names, such as Fabric before 26.1, keeps the text the mod wrote")
    void olderHostKeepsTheText() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        transformer.clearRedirectsForTesting();
        transformer.registerSrgNameMappings(Map.of("m_7023_", "travel"), Map.of("f_19853_", "level"));

        List<String> texts = texts(transformer.transformClass(reflector(), "mod/Reflector"));

        assertTrue(texts.contains("f_19853_") && texts.contains("m_7023_"), texts.toString());
    }

    private static byte[] reflector() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Reflector", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "names", "()V", null, null);
        mv.visitCode();
        for (String text : new String[]{"f_19853_", "m_7023_", "f_99999_", "hello_world_"}) {
            mv.visitLdcInsn(text);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> texts(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> texts = new ArrayList<>();
        node.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) texts.add(text);
        }));
        return texts;
    }
}
