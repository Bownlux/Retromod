/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Synthetic archive whose handler can only be retyped by joining its class and refmap. */
public final class RefmapRepairFixture {

    public static final String TARGET = "net/minecraft/test/RefmapRepairTarget";
    public static final String MIXIN = "test/refmap/RefmapLinkedMixin";
    public static final String SOURCE_SELECTOR = "legacySourceName(Ljava/lang/String;)V";
    public static final String OLD_TARGET_SELECTOR =
            "L" + TARGET + ";currentTarget(Ljava/lang/String;)V";
    public static final String NEW_TARGET_SELECTOR =
            "L" + TARGET + ";currentTarget(Ljava/lang/String;I)V";
    public static final String CALLBACK_INFO =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    public static final String OLD_HANDLER = "(Ljava/lang/String;" + CALLBACK_INFO + ")V";
    public static final String NEW_HANDLER = "(Ljava/lang/String;I" + CALLBACK_INFO + ")V";
    public static final String REFMAP = "fixture-refmap.json";

    private RefmapRepairFixture() {}

    public static Path writeTargetJar(Path path) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry(TARGET + ".class"));
            output.write(targetClass());
            output.closeEntry();
        }
        return path;
    }

    public static byte[] archive(boolean includeRefmap) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        // Class first is deliberate. A single streaming pass cannot repair it from a later refmap.
        entries.put(MIXIN + ".class", mixinClass());
        if (includeRefmap) {
            entries.put(REFMAP, refmapJson().getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    public static Path writeFabricMod(Path path, String modId, boolean includeRefmap)
            throws IOException {
        byte[] archive = archive(includeRefmap);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive));
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write(("{\"schemaVersion\":1,\"id\":\"" + modId
                    + "\",\"version\":\"1.0.0\",\"depends\":{\"minecraft\":\"1.20.1\"}}")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new JarEntry(entry.getName()));
                output.write(input.readAllBytes());
                output.closeEntry();
            }
        }
        return path;
    }

    public static byte[] entry(byte[] jarBytes, String name) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (name.equals(entry.getName())) return input.readAllBytes();
            }
        }
        throw new IOException("missing archive entry: " + name);
    }

    public static String handlerDescriptor(byte[] jarBytes) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(entry(jarBytes, MIXIN + ".class")).accept(node, 0);
        for (MethodNode method : node.methods) {
            if ("handler".equals(method.name)) return method.desc;
        }
        throw new IOException("missing handler in " + MIXIN);
    }

    public static String refmap(byte[] jarBytes) throws IOException {
        return new String(entry(jarBytes, REFMAP), StandardCharsets.UTF_8);
    }

    private static String refmapJson() {
        return "{\"mappings\":{\"" + MIXIN.replace('/', '.') + "\":{\""
                + SOURCE_SELECTOR + "\":\"" + OLD_TARGET_SELECTOR + "\"}}}";
    }

    private static byte[] targetClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                TARGET, null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "currentTarget", "(Ljava/lang/String;I)V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] mixinClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                MIXIN, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(TARGET));
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor handler = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "handler", OLD_HANDLER, null, null);
        AnnotationVisitor inject = handler.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, SOURCE_SELECTOR);
        methods.visitEnd();
        AnnotationVisitor at = inject.visitAnnotation(
                "at", "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "HEAD");
        at.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitVarInsn(Opcodes.ALOAD, 2);
        handler.visitInsn(Opcodes.POP);
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
