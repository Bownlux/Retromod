/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** Old reads of BlockStateProvider.CODEC take DIRECT_CODEC on 26.3, and only in mods known to be older. */
class LegacyStateProviderCodecAdapterTest {

    private static final String PROVIDER = LegacyStateProviderCodecAdapter.PROVIDER;

    @Test
    @DisplayName("A mod built before 26.3 reads DIRECT_CODEC where it read CODEC")
    void oldModReadsTheDirectCodec() {
        assertEquals("DIRECT_CODEC", codecRead(LegacyStateProviderCodecAdapter.apply(readsCodec(), "1.21.1")));
    }

    @Test
    @DisplayName("Moonlight's Recipe.CODEC read takes DIRECT_CODEC, since 26.3's CODEC decodes a recipe holder")
    void oldRecipeReadTakesTheDirectCodec() {
        byte[] reader = readsCodec(LegacyStateProviderCodecAdapter.RECIPE);
        assertEquals("DIRECT_CODEC", codecRead(LegacyStateProviderCodecAdapter.apply(reader, "1.21.1"),
                LegacyStateProviderCodecAdapter.RECIPE));
        assertSame(reader, LegacyStateProviderCodecAdapter.apply(reader, "26.3"));
    }

    @Test
    @DisplayName("A mod built for 26.3, or of unknown version, keeps CODEC")
    void newerOrUnknownModsKeepTheCodec() {
        byte[] reader = readsCodec();
        assertSame(reader, LegacyStateProviderCodecAdapter.apply(reader, "26.3"));
        assertSame(reader, LegacyStateProviderCodecAdapter.apply(reader, null), "a mod of unknown version may be written for 26.3");
    }

    /** A class whose one method reads {@code BlockStateProvider.CODEC}. */
    private static byte[] readsCodec() {
        return readsCodec(PROVIDER);
    }

    /** A class whose one method reads {@code <owner>.CODEC}. */
    private static byte[] readsCodec(String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/Features", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "codec", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "CODEC", "Lcom/mojang/serialization/Codec;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String codecRead(byte[] bytes) {
        return codecRead(bytes, PROVIDER);
    }

    private static String codecRead(byte[] bytes, String owner) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof FieldInsnNode field && field.owner.equals(owner)) return field.name;
        }
        return null;
    }
}
