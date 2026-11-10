/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.HostLibraryNames;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/** Rebuilds {@code ResourceLocation.Serializer}, the Gson adapter 26.1 removed, as a class each mod carries. */
public final class LegacyIdentifierSerializer {

    public static final String INTERNAL = "com/retromod/generated/LegacyIdentifierSerializer";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    // The game's own Gson names, assembled at runtime, because the release jar relocates Gson and
    // the game's Gson refuses an adapter built on Retromod's copy.
    private static final String GSON = HostLibraryNames.GSON_PACKAGE + "/";
    private static final String ELEMENT = GSON + "JsonElement";

    private LegacyIdentifierSerializer() {}

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(INTERNAL, generate());
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, INTERNAL, null, "java/lang/Object",
                new String[]{GSON + "JsonDeserializer", GSON + "JsonSerializer"});

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // deserialize: a string becomes Identifier.parse(string), as the old serializer read it.
        MethodVisitor read = cw.visitMethod(ACC_PUBLIC, "deserialize",
                "(L" + ELEMENT + ";Ljava/lang/reflect/Type;L" + GSON + "JsonDeserializationContext;)Ljava/lang/Object;",
                null, null);
        read.visitCode();
        Label notText = new Label();
        read.visitVarInsn(ALOAD, 1);
        read.visitMethodInsn(INVOKEVIRTUAL, ELEMENT, "isJsonPrimitive", "()Z", false);
        read.visitJumpInsn(IFEQ, notText);
        read.visitVarInsn(ALOAD, 1);
        read.visitMethodInsn(INVOKEVIRTUAL, ELEMENT, "getAsString", "()Ljava/lang/String;", false);
        read.visitMethodInsn(INVOKESTATIC, IDENTIFIER, "parse", "(Ljava/lang/String;)L" + IDENTIFIER + ";", false);
        read.visitInsn(ARETURN);
        read.visitLabel(notText);
        read.visitFrame(F_SAME, 0, null, 0, null);
        read.visitTypeInsn(NEW, GSON + "JsonParseException");
        read.visitInsn(DUP);
        read.visitLdcInsn("An Identifier must be a string such as \"minecraft:stone\", not ");
        read.visitVarInsn(ALOAD, 1);
        read.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        read.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        read.visitMethodInsn(INVOKESPECIAL, GSON + "JsonParseException", "<init>", "(Ljava/lang/String;)V", false);
        read.visitInsn(ATHROW);
        read.visitMaxs(0, 0);
        read.visitEnd();

        // serialize: the identifier's "namespace:path" text.
        MethodVisitor write = cw.visitMethod(ACC_PUBLIC, "serialize",
                "(Ljava/lang/Object;Ljava/lang/reflect/Type;L" + GSON + "JsonSerializationContext;)L" + ELEMENT + ";",
                null, null);
        write.visitCode();
        write.visitTypeInsn(NEW, GSON + "JsonPrimitive");
        write.visitInsn(DUP);
        write.visitVarInsn(ALOAD, 1);
        write.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        write.visitMethodInsn(INVOKESPECIAL, GSON + "JsonPrimitive", "<init>", "(Ljava/lang/String;)V", false);
        write.visitInsn(ARETURN);
        write.visitMaxs(0, 0);
        write.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
