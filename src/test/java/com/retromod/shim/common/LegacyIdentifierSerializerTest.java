/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The rebuilt ResourceLocation.Serializer is a Gson adapter the game's own Gson accepts. */
class LegacyIdentifierSerializerTest {

    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";

    @Test
    @DisplayName("It implements the game's Gson interfaces and verifies")
    void implementsTheGameGson() {
        byte[] bytes = LegacyIdentifierSerializer.generate();
        new ClassReader(bytes).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        assertEquals(List.of("com/google/gson/JsonDeserializer", "com/google/gson/JsonSerializer"), node.interfaces,
                "Jade crashed in its static initializer when the adapter implemented Retromod's relocated Gson");
    }

    @Test
    @DisplayName("Gson takes it as an adapter and reads and writes \"namespace:path\"")
    void gsonReadsAndWritesIdentifiers() throws Exception {
        Loader loader = new Loader();
        Class<?> identifier = loader.define(IDENTIFIER, identifierStandIn());
        Class<?> serializer = loader.define(LegacyIdentifierSerializer.INTERNAL, LegacyIdentifierSerializer.generate());

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(identifier, serializer.getDeclaredConstructor().newInstance())
                .create();

        Object stone = gson.fromJson("\"minecraft:stone\"", identifier);
        assertEquals("minecraft:stone", stone.toString());
        assertEquals("\"minecraft:stone\"", gson.toJson(stone, identifier));
        assertThrows(JsonParseException.class, () -> gson.fromJson("{\"id\":1}", identifier));
    }

    /** The host's Identifier, reduced to parse(String) and toString(). */
    private static byte[] identifierStandIn() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, IDENTIFIER, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "text", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "(Ljava/lang/String;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, IDENTIFIER, "text", "Ljava/lang/String;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor parse = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "parse",
                "(Ljava/lang/String;)L" + IDENTIFIER + ";", null, null);
        parse.visitCode();
        parse.visitTypeInsn(NEW, IDENTIFIER);
        parse.visitInsn(DUP);
        parse.visitVarInsn(ALOAD, 0);
        parse.visitMethodInsn(INVOKESPECIAL, IDENTIFIER, "<init>", "(Ljava/lang/String;)V", false);
        parse.visitInsn(ARETURN);
        parse.visitMaxs(0, 0);
        parse.visitEnd();
        MethodVisitor text = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        text.visitCode();
        text.visitVarInsn(ALOAD, 0);
        text.visitFieldInsn(GETFIELD, IDENTIFIER, "text", "Ljava/lang/String;");
        text.visitInsn(ARETURN);
        text.visitMaxs(0, 0);
        text.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Defines the stand-in and the adapter next to the test's own Gson. */
    private static final class Loader extends ClassLoader {
        Loader() {
            super(LegacyIdentifierSerializerTest.class.getClassLoader());
        }

        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
}
