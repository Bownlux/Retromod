/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/** Keeps a data attachment that serializes with a {@code Codec} working on NeoForge versions that take a {@code MapCodec}. */
public final class LegacyAttachmentCodecBridge {

    static final String CALLS = "com/retromod/generated/LegacyAttachmentCodecs";
    static final String BUILDER = "net/neoforged/neoforge/attachment/AttachmentType$Builder";
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String MAP_CODEC_CODEC = MAP_CODEC + "$MapCodecCodec";
    private static final String PREDICATE = "Ljava/util/function/Predicate;";

    private LegacyAttachmentCodecBridge() {}

    /** Registers on a host whose attachment builder has no {@code serialize(Codec)}. */
    public static void register(RetromodTransformer t) {
        ClassNode builder = ClassResourceInspector.read(BUILDER);
        if (builder == null || builder.methods.stream().anyMatch(m -> m.name.equals("serialize")
                && m.desc.equals("(L" + CODEC + ";)L" + BUILDER + ";"))) {
            return;
        }
        t.registerSyntheticClass(CALLS, generate());
        String self = "L" + BUILDER + ";";
        t.registerMethodRedirect(BUILDER, "serialize", "(L" + CODEC + ";)" + self, CALLS, "serialize",
                "(" + self + "L" + CODEC + ";)" + self, true);
        t.registerMethodRedirect(BUILDER, "serialize", "(L" + CODEC + ";" + PREDICATE + ")" + self, CALLS, "serialize",
                "(" + self + "L" + CODEC + ";" + PREDICATE + ")" + self, true);
    }

    static byte[] generate() {
        String self = "L" + BUILDER + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        for (boolean filtered : new boolean[]{false, true}) {
            String extra = filtered ? PREDICATE : "";
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "serialize", "(" + self + "L" + CODEC + ";" + extra + ")" + self, null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, CALLS, "mapCodec", "(L" + CODEC + ";)L" + MAP_CODEC + ";", false);
            if (filtered) mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "serialize", "(L" + MAP_CODEC + ";" + extra + ")" + self, false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        // A map codec wrapped as a codec is unwrapped; anything else is stored under a "value" field.
        MethodVisitor map = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "mapCodec", "(L" + CODEC + ";)L" + MAP_CODEC + ";", null, null);
        map.visitCode();
        Label plain = new Label();
        map.visitVarInsn(ALOAD, 0);
        map.visitTypeInsn(INSTANCEOF, MAP_CODEC_CODEC);
        map.visitJumpInsn(IFEQ, plain);
        map.visitVarInsn(ALOAD, 0);
        map.visitTypeInsn(CHECKCAST, MAP_CODEC_CODEC);
        map.visitMethodInsn(INVOKEVIRTUAL, MAP_CODEC_CODEC, "codec", "()L" + MAP_CODEC + ";", false);
        map.visitInsn(ARETURN);
        map.visitLabel(plain);
        map.visitFrame(F_SAME, 0, null, 0, null);
        map.visitVarInsn(ALOAD, 0);
        map.visitLdcInsn("value");
        map.visitMethodInsn(INVOKEINTERFACE, CODEC, "fieldOf", "(Ljava/lang/String;)L" + MAP_CODEC + ";", true);
        map.visitInsn(ARETURN);
        map.visitMaxs(0, 0);
        map.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
