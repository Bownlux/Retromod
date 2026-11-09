/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A mod's frame join of two types from a library it bundles keeps their shared type (Create with Registrate). */
class BundledJarFrameMergeTest {

    private static final String HOLDER = "com/example/mod/Holder";
    /** Two library types that share a parent, like Registrate's BlockEntry and ItemEntry. */
    private static final String BLOCK_ENTRY = "com/example/lib/BlockEntry";
    private static final String ITEM_ENTRY = "com/example/lib/ItemEntry";
    private static final String PROVIDER_ENTRY = "com/example/lib/ProviderEntry";
    /** The name the mod was built against, which the transform rewrites so frames are rebuilt. */
    private static final String OLD_NAME = "net/minecraft/class_437";
    private static final String ABSENT = "net/minecraft/client/gui/screens/Screen";
    private String savedVersion;

    @AfterEach
    void restore() {
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
    }

    private static byte[] classWithSuper(String name, String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Picks one of two library entries and returns it as the renamed Minecraft type. */
    private static byte[] branchingHolder() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOLDER, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "pick", "(Z)L" + OLD_NAME + ";", null, null);
        m.visitCode();
        Label other = new Label();
        Label done = new Label();
        m.visitVarInsn(Opcodes.ILOAD, 0);
        m.visitJumpInsn(Opcodes.IFEQ, other);
        m.visitTypeInsn(Opcodes.NEW, BLOCK_ENTRY);
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK_ENTRY, "<init>", "()V", false);
        m.visitJumpInsn(Opcodes.GOTO, done);
        m.visitLabel(other);
        m.visitTypeInsn(Opcodes.NEW, ITEM_ENTRY);
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, ITEM_ENTRY, "<init>", "()V", false);
        m.visitLabel(done);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] libraryJar() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : Map.of(
                    BLOCK_ENTRY, classWithSuper(BLOCK_ENTRY, PROVIDER_ENTRY),
                    ITEM_ENTRY, classWithSuper(ITEM_ENTRY, PROVIDER_ENTRY),
                    PROVIDER_ENTRY, classWithSuper(PROVIDER_ENTRY, OLD_NAME)).entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void write(Path dir, String name, byte[] bytes) throws Exception {
        Path out = dir.resolve(name);
        Files.createDirectories(out.getParent());
        Files.write(out, bytes);
    }

    private static List<String> stackTypes(byte[] classBytes, String methodName) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.EXPAND_FRAMES);
        List<String> types = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (!methodName.equals(m.name)) continue;
            for (var insn : m.instructions.toArray()) {
                if (insn instanceof FrameNode f && f.stack != null) {
                    for (Object o : f.stack) if (o instanceof String s) types.add(s);
                }
            }
        }
        return types;
    }

    @Test
    @DisplayName("The NeoForge and Forge pass joins two bundled-library types at their shared parent")
    void forgePassReadsJarInJarLibrary(@TempDir Path dir) throws Exception {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        write(dir, HOLDER + ".class", branchingHolder());
        write(dir, "META-INF/jarjar/library.jar", libraryJar());

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD_NAME, ABSENT);

        new ForgeModTransformer("26.3").transformClasses(dir);

        List<String> after = stackTypes(Files.readAllBytes(dir.resolve(HOLDER + ".class")), "pick");
        assertTrue(after.contains(PROVIDER_ENTRY),
                "the join of two bundled-library types should be their shared parent, which the "
                        + "bundled jar declares, not Object: " + after);
    }

    @Test
    @DisplayName("A jar file's bundled classes reach the hierarchy but not the jar's own class lookup")
    void jarFileBundledClassesServeOnlyTheHierarchy(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("mod.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/jars/library.jar"));
            zip.write(libraryJar());
            zip.closeEntry();
        }
        Function<String, byte[]> own = name -> null;
        BundledJarClasses.Bundled bundled;
        try (JarFile file = new JarFile(jar.toFile())) {
            bundled = BundledJarClasses.fromJar(file);
        }
        Function<String, byte[]> attached = BundledJarClasses.attach(own, bundled);

        assertNull(attached.apply(BLOCK_ENTRY), "the jar's own lookup must not claim a bundled class");
        // Lookups of host classes, which most walks make, never need the bundled jars.
        assertNull(BundledJarClasses.withBundled(attached).apply("net/minecraft/world/level/block/Block"));
        assertFalse(bundled.isRead(), "the bundled jars should be read only when a library class is looked up");
        assertNotNull(BundledJarClasses.withBundled(attached).apply(BLOCK_ENTRY),
                "the hierarchy should read a class from a jar under META-INF/jars/");
        assertTrue(bundled.isRead());
        assertEquals(PROVIDER_ENTRY, RetromodTransformer.resolveCommonSuperClass(BLOCK_ENTRY, ITEM_ENTRY,
                BundledJarClasses.withBundled(attached), name -> null));
    }
}
