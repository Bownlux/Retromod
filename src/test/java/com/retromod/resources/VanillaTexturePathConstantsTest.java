/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class VanillaTexturePathConstantsTest {

    private static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";

    /** A class whose static method runs {@code body} and returns. */
    private static byte[] classWith(Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "oldmod/client/SkyRenderer", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "textures", "()V", null, null);
        method.visitCode();
        body.accept(method);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code new ResourceLocation(namespace, path)}, the shape every Forge-era mod used. */
    private static Consumer<MethodVisitor> newResourceLocation(String namespace, String path) {
        return method -> {
            method.visitTypeInsn(Opcodes.NEW, RESOURCE_LOCATION);
            method.visitInsn(Opcodes.DUP);
            method.visitLdcInsn(namespace);
            method.visitLdcInsn(path);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, RESOURCE_LOCATION, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            method.visitInsn(Opcodes.POP);
        };
    }

    /** {@code owner.factory(id)} for a one-argument static factory. */
    private static Consumer<MethodVisitor> factory(String owner, String name, String id) {
        return method -> {
            method.visitLdcInsn(id);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, "(Ljava/lang/String;)L" + owner + ";", false);
            method.visitInsn(Opcodes.POP);
        };
    }

    private static List<String> strings(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<String> strings = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String text) strings.add(text);
            }
        }
        return strings;
    }

    @ParameterizedTest(name = "bat texture on {0}: {1}")
    @CsvSource({"1.21.11, textures/entity/bat.png", "26.1, textures/entity/bat/bat.png",
            "26.3, textures/entity/bat/bat.png"})
    @DisplayName("A vanilla entity texture built with the minecraft namespace follows the 26.1 move")
    void entityTextureMovedIn261(String host, String expected) {
        byte[] original = classWith(newResourceLocation("minecraft", "textures/entity/bat.png"));

        assertEquals(List.of("minecraft", expected),
                strings(VanillaTexturePathConstants.apply(original, host)));
    }

    @ParameterizedTest(name = "sun on {0}: {1}")
    @CsvSource({"1.21.10, textures/environment/sun.png", "1.21.11, textures/environment/celestial/sun.png",
            "26.3, textures/environment/celestial/sun.png"})
    @DisplayName("A bare path read in the default namespace follows the 1.21.11 sun move")
    void defaultNamespaceFactory(String host, String expected) {
        byte[] original = classWith(factory(IDENTIFIER, "withDefaultNamespace", "textures/environment/sun.png"));

        assertEquals(List.of(expected), strings(VanillaTexturePathConstants.apply(original, host)));
    }

    @Test
    @DisplayName("A full id follows every move up to the host, and intermediary-named ids are recognised")
    void chainedMovesAndIntermediaryNames() {
        byte[] parsed = classWith(factory(IDENTIFIER, "parse", "minecraft:textures/entity/chicken.png"));
        assertEquals(List.of("minecraft:textures/entity/chicken/chicken_temperate.png"),
                strings(VanillaTexturePathConstants.apply(parsed, "26.3")),
                "1.21.5 moved it into chicken/, 26.1 renamed it again");

        byte[] intermediary = classWith(factory("net/minecraft/class_2960", "method_60656",
                "textures/environment/sun.png"));
        assertEquals(List.of("textures/environment/celestial/sun.png"),
                strings(VanillaTexturePathConstants.apply(intermediary, "1.21.11")));
    }

    @Test
    @DisplayName("Mod textures, split sheets, and strings that build no id are left alone")
    void leavesOtherStringsAlone() {
        byte[] modNamespace = classWith(newResourceLocation("oldmod", "textures/entity/bat.png"));
        assertArrayEquals(modNamespace, VanillaTexturePathConstants.apply(modNamespace, "26.3"));

        byte[] splitSheet = classWith(factory(RESOURCE_LOCATION, "parse", "textures/gui/icons.png"));
        assertArrayEquals(splitSheet, VanillaTexturePathConstants.apply(splitSheet, "26.3"),
                "1.20.2 cut icons.png into sprites; no single path replaces it");

        byte[] printed = classWith(method -> {
            method.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            method.visitLdcInsn("textures/entity/bat.png");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                    "(Ljava/lang/String;)V", false);
        });
        assertArrayEquals(printed, VanillaTexturePathConstants.apply(printed, "26.3"));
    }

    @ParameterizedTest(name = "sign sheet on {0}: {1}")
    @CsvSource({"1.21.1, textures/entity/signs/oak.png", "26.1, textures/entity/signs/oak.png",
            "26.2, textures/entity/sign.png", "26.3, textures/entity/sign.png"})
    @DisplayName("A sign renderer's sheet follows the 1.14 rename, but not the 26.2 block sprite drawn in another layout")
    void signSheetStopsAtTheRedraw(String host, String expected) {
        byte[] original = classWith(method -> {
            method.visitTypeInsn(Opcodes.NEW, RESOURCE_LOCATION);
            method.visitInsn(Opcodes.DUP);
            method.visitLdcInsn("textures/entity/sign.png");
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, RESOURCE_LOCATION, "<init>", "(Ljava/lang/String;)V", false);
            method.visitInsn(Opcodes.POP);
        });

        assertEquals(List.of(expected), strings(VanillaTexturePathConstants.apply(original, host)));
    }

    @Test
    @DisplayName("Every path rewritten on a host is gone there, and its destination is not moved again")
    void movesEndWhereTheHostKeepsTheFile() {
        var moves = VanillaTexturePathConstants.movesOnHost(PackFormat.resourceTarget("26.3"));
        assertEquals("entity/signs/oak.png", VanillaTexturePathConstants.movesOnHost(
                PackFormat.resourceTarget("26.1")).get("entity/sign.png"));
        for (String redrawn : List.of("entity/sign.png", "entity/signs/oak.png", "entity/signs/hanging/oak.png")) {
            assertNull(moves.get(redrawn), redrawn + " became a 32x32 block sprite in 26.2, not the 64x32 sheet");
        }
        assertEquals("block/purpur_pillar_side.png", moves.get("block/purpur_pillar.png"),
                "a byte-identical move in the same release still applies");
        for (var move : moves.entrySet()) {
            assertFalse(moves.containsKey(move.getValue()), move.getKey() + " ends on a path that moves again");
        }
    }
}
