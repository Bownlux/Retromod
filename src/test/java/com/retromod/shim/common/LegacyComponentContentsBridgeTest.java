/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** owo-lib's own text content builds a {@code ComponentContents.Type}, which hosts from 1.21.11 replaced with {@code codec()}. */
class LegacyComponentContentsBridgeTest {

    private static final String MOD = "mod/InsertingTextContent";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the stand-in is registered only where the host answers codec() and has no old type")
    void followsTheHost() {
        assertTrue(LegacyComponentContentsBridge.hostUsesCodec(name -> name.equals(LegacyComponentContentsBridge.CONTENTS)
                ? contents(true) : null));
        assertFalse(LegacyComponentContentsBridge.hostUsesCodec(name -> name.equals(LegacyComponentContentsBridge.CONTENTS)
                ? contents(false) : null), "a host that still has the old type keeps it");
        assertFalse(LegacyComponentContentsBridge.hostUsesCodec(name -> null), "an unreadable host gets nothing");
    }

    @Test
    @DisplayName("a mod's content class gains codec(), read from its old type(), and the old type becomes the stand-in")
    void contentClassGainsCodec() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyComponentContentsBridge.register(transformer, name -> name.equals(LegacyComponentContentsBridge.CONTENTS)
                ? contents(true) : null);

        byte[] out = LegacyComponentContentsBridge.apply(transformer.transformClass(oldContent(), MOD + ".class"));
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        MethodNode codec = node.methods.stream().filter(m -> m.name.equals("codec")).findFirst().orElseThrow();
        assertEquals(LegacyComponentContentsBridge.CODEC_DESC, codec.desc);
        assertEquals(List.of(MOD + ".type", LegacyComponentContentsBridge.STAND_IN + ".codec"), calls(codec));
        assertTrue(new String(out, java.nio.charset.StandardCharsets.ISO_8859_1).contains(LegacyComponentContentsBridge.STAND_IN));
        assertSame(out, LegacyComponentContentsBridge.apply(out), "a class that already has codec() is left alone");
    }

    @Test
    @DisplayName("the stand-in is a valid class")
    void standInVerifies() {
        assertDoesNotThrow(() -> new ClassReader(LegacyComponentContentsBridge.generate())
                .accept(new CheckClassAdapter(new ClassWriter(0), true), 0));
    }

    private static ClassNode contents(boolean codec) {
        ClassNode node = new ClassNode();
        node.name = LegacyComponentContentsBridge.CONTENTS;
        String name = codec ? "codec" : "type";
        String desc = codec ? LegacyComponentContentsBridge.CODEC_DESC : "()L" + LegacyComponentContentsBridge.OLD_TYPE + ";";
        node.methods.add(new MethodNode(ACC_PUBLIC | ACC_ABSTRACT, name, desc, null, null));
        return node;
    }

    /** {@code record InsertingTextContent(int index) implements ComponentContents { Type<?> type() { return TYPE; } }}, simplified. */
    private static byte[] oldContent() {
        String type = "L" + LegacyComponentContentsBridge.OLD_TYPE + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, MOD, null, "java/lang/Object", new String[] {LegacyComponentContentsBridge.CONTENTS});
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "TYPE", type, null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "type", "()" + type, null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, MOD, "TYPE", type);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        return calls;
    }
}
