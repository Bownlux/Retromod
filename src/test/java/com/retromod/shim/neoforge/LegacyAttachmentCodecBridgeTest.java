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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #366: ProjectE's data attachments serialize with a {@code Codec}, which NeoForge 21.6 and newer take as a {@code MapCodec}. */
class LegacyAttachmentCodecBridgeTest {

    private static final String BUILDER = LegacyAttachmentCodecBridge.BUILDER;
    private static final String SELF = "L" + BUILDER + ";";
    private static final String CODEC = "Lcom/mojang/serialization/Codec;";
    private static final String MAP_CODEC = "Lcom/mojang/serialization/MapCodec;";
    private static final String PREDICATE = "Ljava/util/function/Predicate;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The generated serialize adapters verify")
    void generatedCallsVerify() {
        new ClassReader(LegacyAttachmentCodecBridge.generate()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    @Test
    @DisplayName("On a MapCodec-only host the 1.21.6 shim sends both Codec serialize calls to the adapter")
    void mapCodecHostBridgesThroughThe1216Shim() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        List<String> calls = callsAfterRegistering(transformer, builder(MAP_CODEC));

        String adapter = LegacyAttachmentCodecBridge.CALLS + ".serialize";
        assertEquals(List.of(
                "static " + adapter + "(" + SELF + CODEC + ")" + SELF,
                "static " + adapter + "(" + SELF + CODEC + PREDICATE + ")" + SELF), calls,
                "NeoForge 21.6 replaced serialize(Codec) with serialize(MapCodec)");
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyAttachmentCodecBridge.CALLS));
    }

    @Test
    @DisplayName("A host that still has serialize(Codec), NeoForge 21.2 to 21.5, is left alone")
    void codecHostIsLeftAlone() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        List<String> calls = callsAfterRegistering(transformer, builder(CODEC));

        assertEquals(List.of(
                "virtual " + BUILDER + ".serialize(" + CODEC + ")" + SELF,
                "virtual " + BUILDER + ".serialize(" + CODEC + PREDICATE + ")" + SELF), calls);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyAttachmentCodecBridge.CALLS));
    }

    /** Registers the 1.21.6 shim against a host that serves only the given builder, then transforms a mod. */
    private static List<String> callsAfterRegistering(RetromodTransformer transformer, byte[] hostBuilder) {
        transformer.clearRedirectsForTesting();
        ClassLoader host = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return name.equals(BUILDER + ".class") ? new ByteArrayInputStream(hostBuilder) : null;
            }
        };
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(host);
        try {
            new NeoForge_1_21_5_to_1_21_6().registerRedirects(transformer);
        } finally {
            thread.setContextClassLoader(previous);
        }
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(attachments(), "mod/Attachments.class")).accept(node, 0);
        List<String> calls = new ArrayList<>();
        node.methods.forEach(method -> method.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) {
                String kind = call.getOpcode() == INVOKESTATIC ? "static " : "virtual ";
                calls.add(kind + call.owner + "." + call.name + call.desc);
            }
        }));
        return calls;
    }

    /** A host AttachmentType.Builder whose serialize overloads take the given codec type. */
    private static byte[] builder(String codecType) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, BUILDER, null, "java/lang/Object", null);
        for (String extra : new String[]{"", PREDICATE}) {
            cw.visitMethod(ACC_PUBLIC, "serialize", "(" + codecType + extra + ")" + SELF, null, null).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code builder.serialize(codec)} and {@code builder.serialize(codec, filter)}, as a 1.21.1 mod writes them. */
    private static byte[] attachments() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/Attachments", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "build",
                "(" + SELF + CODEC + PREDICATE + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "serialize", "(" + CODEC + ")" + SELF, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "serialize", "(" + CODEC + PREDICATE + ")" + SELF, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
