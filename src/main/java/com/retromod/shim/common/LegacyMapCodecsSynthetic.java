/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** One map codec per old codec, shared by a mod's rebuilt generator base and its registration, so both name the same object. */
public final class LegacyMapCodecsSynthetic {

    public static final String INTERNAL = "com/retromod/generated/LegacyMapCodecs";
    static final String OF_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";
    static final String REMEMBER_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String MAP_CODEC_CODEC = "com/mojang/serialization/MapCodec$MapCodecCodec";
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String MAP = "java/util/Map";

    private LegacyMapCodecsSynthetic() {}

    /** Registers the class once per transformer. */
    public static void register(RetromodTransformer t) {
        if (!t.getSyntheticClasses().containsKey(INTERNAL)) t.registerSyntheticClass(INTERNAL, generate());
    }

    /** The shared map codecs: {@code of(codec)}, which the registry finds by identity, and {@code remember} and {@code remembered}. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, INTERNAL, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "MADE", "L" + MAP + ";", null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/util/IdentityHashMap");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, INTERNAL, "MADE", "L" + MAP + ";");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        // remember(codec, value): what a register event stored for the codec.
        MethodVisitor remember = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED, "remember",
                REMEMBER_DESC, null, null);
        remember.visitCode();
        remember.visitFieldInsn(Opcodes.GETSTATIC, INTERNAL, "MADE", "L" + MAP + ";");
        remember.visitVarInsn(Opcodes.ALOAD, 0);
        remember.visitVarInsn(Opcodes.ALOAD, 1);
        remember.visitMethodInsn(Opcodes.INVOKEINTERFACE, MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        remember.visitInsn(Opcodes.POP);
        remember.visitInsn(Opcodes.RETURN);
        remember.visitMaxs(0, 0);
        remember.visitEnd();

        // remembered(codec): that value, or null.
        MethodVisitor remembered = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED, "remembered",
                OF_DESC, null, null);
        remembered.visitCode();
        remembered.visitFieldInsn(Opcodes.GETSTATIC, INTERNAL, "MADE", "L" + MAP + ";");
        remembered.visitVarInsn(Opcodes.ALOAD, 0);
        remembered.visitMethodInsn(Opcodes.INVOKEINTERFACE, MAP, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        remembered.visitInsn(Opcodes.ARETURN);
        remembered.visitMaxs(0, 0);
        remembered.visitEnd();

        MethodVisitor of = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED, "of", OF_DESC, null, null);
        of.visitCode();
        // A register event stored its own value for this codec; the generator must answer with that same object.
        Label notRemembered = new Label();
        of.visitFieldInsn(Opcodes.GETSTATIC, INTERNAL, "MADE", "L" + MAP + ";");
        of.visitVarInsn(Opcodes.ALOAD, 0);
        of.visitMethodInsn(Opcodes.INVOKEINTERFACE, MAP, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        of.visitInsn(Opcodes.DUP);
        of.visitJumpInsn(Opcodes.IFNULL, notRemembered);
        of.visitInsn(Opcodes.ARETURN);
        of.visitLabel(notRemembered);
        of.visitInsn(Opcodes.POP);
        Label notMap = new Label();
        of.visitVarInsn(Opcodes.ALOAD, 0);
        of.visitTypeInsn(Opcodes.INSTANCEOF, MAP_CODEC);
        of.visitJumpInsn(Opcodes.IFEQ, notMap);
        of.visitVarInsn(Opcodes.ALOAD, 0);
        of.visitInsn(Opcodes.ARETURN);
        of.visitLabel(notMap);
        Label notRecord = new Label();
        of.visitVarInsn(Opcodes.ALOAD, 0);
        of.visitTypeInsn(Opcodes.INSTANCEOF, MAP_CODEC_CODEC);
        of.visitJumpInsn(Opcodes.IFEQ, notRecord);
        of.visitVarInsn(Opcodes.ALOAD, 0);
        of.visitTypeInsn(Opcodes.CHECKCAST, MAP_CODEC_CODEC);
        of.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MAP_CODEC_CODEC, "codec", "()L" + MAP_CODEC + ";", false);
        of.visitInsn(Opcodes.ARETURN);
        of.visitLabel(notRecord);
        // Any other codec is used as a map codec, made once so every caller gets the same object.
        of.visitVarInsn(Opcodes.ALOAD, 0);
        of.visitTypeInsn(Opcodes.CHECKCAST, CODEC);
        of.visitMethodInsn(Opcodes.INVOKESTATIC, MAP_CODEC, "assumeMapUnsafe", "(L" + CODEC + ";)L" + MAP_CODEC + ";", false);
        of.visitVarInsn(Opcodes.ASTORE, 1);
        of.visitFieldInsn(Opcodes.GETSTATIC, INTERNAL, "MADE", "L" + MAP + ";");
        of.visitVarInsn(Opcodes.ALOAD, 0);
        of.visitVarInsn(Opcodes.ALOAD, 1);
        of.visitMethodInsn(Opcodes.INVOKEINTERFACE, MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        of.visitInsn(Opcodes.POP);
        of.visitVarInsn(Opcodes.ALOAD, 1);
        of.visitInsn(Opcodes.ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
