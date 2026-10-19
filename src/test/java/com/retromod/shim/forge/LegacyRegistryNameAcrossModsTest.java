/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.shim.forge.embedded.LegacyForgeEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.InputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Each 1.12 mod carries its own copy of the lifecycle bridge, and a copy keeps the registry names
 * set through it. Rockhounding Rocks' blocks extend Rockhounding Core's base block, whose
 * constructor calls {@code setRegistryName}, so the name lands in Core's copy while Rocks' copy
 * registers the block. Rocks' copy has to find it there, or every block is skipped as unnamed.
 */
class LegacyRegistryNameAcrossModsTest {

    private static final String EVENTS = "com/retromod/shim/forge/embedded/LegacyForgeEvents";

    @Test
    @DisplayName("a block named by its core mod's base class keeps that name when its addon registers it")
    void addonReadsNameRecordedByItsCore(@TempDir Path dir) throws Exception {
        String coreEvents = "com/retromod/embedded/core_lib/" + EVENTS;
        String addonEvents = "com/retromod/embedded/rocks_addon/" + EVENTS;
        Path core = jar(dir.resolve("core.jar"), Map.of(
                coreEvents, relocatedEvents(coreEvents),
                "core/BaseBlock", baseBlock(coreEvents)));
        Path addon = jar(dir.resolve("rocks.jar"), Map.of(
                addonEvents, relocatedEvents(addonEvents),
                "addon/Rock", rock(),
                "addon/Probe", probe(addonEvents)));

        ModuleLayer boot = ModuleLayer.boot();
        Configuration configuration = boot.configuration().resolve(
                ModuleFinder.of(core, addon), ModuleFinder.of(), Set.of("core", "rocks"));
        ModuleLayer layer = boot.defineModulesWithOneLoader(configuration, getClass().getClassLoader());
        ClassLoader loader = layer.findLoader("rocks");

        Object block = loader.loadClass("addon.Rock").getConstructor().newInstance();
        Object name = loader.loadClass("addon.Probe").getMethod("name", Object.class).invoke(null, block);

        assertEquals("core:base", name);
    }

    /** {@code public class BaseBlock { public BaseBlock() { LegacyForgeEvents.setRegistryName(this, "core", "base"); } }} */
    private static byte[] baseBlock(String events) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "core/BaseBlock", null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitLdcInsn("core");
        init.visitLdcInsn("base");
        init.visitMethodInsn(INVOKESTATIC, events, "setRegistryName",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
        init.visitInsn(POP);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code public class Rock extends BaseBlock {}} */
    private static byte[] rock() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "addon/Rock", null, "core/BaseBlock", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "core/BaseBlock", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code public class Probe { public static Object name(Object e) { return LegacyForgeEvents.getRegistryName(e); } }} */
    private static byte[] probe(String events) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "addon/Probe", null, "java/lang/Object", null);
        MethodVisitor name = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "name",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        name.visitCode();
        name.visitVarInsn(ALOAD, 0);
        name.visitMethodInsn(INVOKESTATIC, events, "getRegistryName",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        name.visitInsn(ARETURN);
        name.visitMaxs(0, 0);
        name.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The bridge class as the embedder relocates it into one mod. */
    private static byte[] relocatedEvents(String relocated) throws Exception {
        byte[] original;
        try (InputStream in = LegacyForgeEvents.class.getResourceAsStream("LegacyForgeEvents.class")) {
            original = in.readAllBytes();
        }
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(original).accept(new ClassRemapper(writer,
                new SimpleRemapper(Map.of(EVENTS, relocated))), 0);
        return writer.toByteArray();
    }

    private static Path jar(Path path, Map<String, byte[]> classes) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return path;
    }
}
