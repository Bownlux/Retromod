/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinCommandParserBridgeTest {

    private static final String TARGET =
            "net/minecraft/client/multiplayer/ClientPacketListener";
    private static final String PROVIDER =
            "net/minecraft/client/multiplayer/ClientSuggestionProvider";
    private static final String DISPATCHER = "com/mojang/brigadier/CommandDispatcher";
    private static final String PARSE_RESULTS = "com/mojang/brigadier/ParseResults";
    private static final String OLD_DESC =
            "(Ljava/lang/String;)L" + PARSE_RESULTS + ";";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    @TempDir
    Path tempDir;

    @Test
    void removedParseInvokerBecomesSyntheticDefaultBridge() throws IOException {
        FuzzyMethodResolver host = hostIndex(false, true);
        ClassNode mixin = read(accessorMixin());

        assertTrue(MixinCommandParserBridge.apply(mixin, host));

        MethodNode method = mixin.methods.get(0);
        assertEquals(0, method.access & Opcodes.ACC_ABSTRACT);
        assertEquals(Opcodes.ACC_SYNTHETIC, method.access & Opcodes.ACC_SYNTHETIC,
                "synthetic keeps Mixin's accessor-interface classification");
        assertFalse(hasAnnotation(method, INVOKER));
        assertEquals(OLD_DESC, method.desc, "existing No Chat Reports callers stay unchanged");
        assertTrue(calls(method, TARGET, "getCommands", "()L" + DISPATCHER + ";"));
        assertTrue(calls(method, TARGET, "getSuggestionsProvider", "()L" + PROVIDER + ";"));
        assertTrue(calls(method, DISPATCHER, "parse",
                "(Ljava/lang/String;Ljava/lang/Object;)L" + PARSE_RESULTS + ";"));
        assertEquals(Opcodes.ARETURN, method.instructions.getLast().getOpcode());
    }

    @Test
    void existingOldHostMethodKeepsInvokerUntouched() throws IOException {
        FuzzyMethodResolver host = hostIndex(true, true);
        ClassNode mixin = read(accessorMixin());

        assertFalse(MixinCommandParserBridge.apply(mixin, host));
        MethodNode method = mixin.methods.get(0);
        assertEquals(Opcodes.ACC_ABSTRACT, method.access & Opcodes.ACC_ABSTRACT);
        assertTrue(hasAnnotation(method, INVOKER));
    }

    @Test
    void missingCurrentGetterKeepsInvokerUntouched() throws IOException {
        FuzzyMethodResolver host = hostIndex(false, false);
        ClassNode mixin = read(accessorMixin());

        assertFalse(MixinCommandParserBridge.apply(mixin, host));
        assertTrue(hasAnnotation(mixin.methods.get(0), INVOKER));
    }

    private FuzzyMethodResolver hostIndex(boolean includeOldParse, boolean includeSuggestions)
            throws IOException {
        Path jar = tempDir.resolve("host-" + includeOldParse + "-" + includeSuggestions + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            addClass(out, TARGET, packetListener(includeOldParse, includeSuggestions));
            addClass(out, PROVIDER, emptyClass(PROVIDER));
        }
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(jar);
        return resolver;
    }

    private static byte[] packetListener(boolean includeOldParse, boolean includeSuggestions) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                TARGET, null, "java/lang/Object", null);
        abstractMethod(writer, "getCommands", "()L" + DISPATCHER + ";");
        if (includeSuggestions) {
            abstractMethod(writer, "getSuggestionsProvider", "()L" + PROVIDER + ";");
        }
        if (includeOldParse) abstractMethod(writer, "parseCommand", OLD_DESC);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void abstractMethod(ClassWriter writer, String name, String descriptor) {
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                name, descriptor, null, null).visitEnd();
    }

    private static byte[] accessorMixin() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "test/AccessorClientPacketListener", null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType(TARGET));
        values.visitEnd();
        mixin.visitEnd();
        MethodVisitor invoker = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "invokeParseCommand", OLD_DESC, null, null);
        AnnotationVisitor annotation = invoker.visitAnnotation(INVOKER, false);
        annotation.visit("value", "parseCommand");
        annotation.visitEnd();
        invoker.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addClass(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name + ".class"));
        out.write(bytes);
        out.closeEntry();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean calls(MethodNode method, String owner, String name, String descriptor) {
        return java.util.Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .anyMatch(call -> owner.equals(call.owner) && name.equals(call.name)
                        && descriptor.equals(call.desc));
    }

    private static boolean hasAnnotation(MethodNode method, String descriptor) {
        return List.of(
                method.visibleAnnotations != null
                        ? method.visibleAnnotations : List.<AnnotationNode>of(),
                method.invisibleAnnotations != null
                        ? method.invisibleAnnotations : List.<AnnotationNode>of())
                .stream().flatMap(List::stream)
                .anyMatch(annotation -> descriptor.equals(annotation.desc));
    }
}
