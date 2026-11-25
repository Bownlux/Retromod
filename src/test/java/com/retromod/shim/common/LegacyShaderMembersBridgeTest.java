/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Old shader program and uniform calls link to inert stand-ins on the program class each 26.x host has. */
class LegacyShaderMembersBridgeTest {

    private static final String UNIFORM = LegacyShaderMembersBridge.UNIFORM;
    private static final String SHADER = LegacyShaderMembersBridge.SHADER_INSTANCE;
    private static final Path LIBRARIES = Path.of(System.getProperty("user.home", ""),
            "Library/Application Support/PrismLauncher/libraries");
    private String savedVersion;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        McReflect.setForceForge(false);
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
    }

    @Test
    @DisplayName("offline for NeoForge 26.3 an old mod's shader code reaches the stand-ins and the renderpearl program")
    void offline263() throws Exception {
        ClassNode out = transform("26.3", name -> null, forgeShaderCode(), LegacyShaderMembersBridge.PEARL_PROGRAM);
        List<String> refs = refs(out);
        assertTrue(refs.contains(LegacyShaderMembersBridge.PROGRAMS + ".create(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)L"
                + LegacyShaderMembersBridge.PEARL_PROGRAM + ";"), refs.toString());
        assertTrue(refs.contains(UNIFORM + ".inert(Ljava/lang/Object;Ljava/lang/String;)L" + UNIFORM + ";"), refs.toString());
        assertTrue(refs.contains(UNIFORM + ".set(F)V"), refs.toString());
        assertTrue(refs.contains(UNIFORM + ".inertField(Ljava/lang/Object;)L" + UNIFORM + ";"), refs.toString());
        assertTrue(refs.contains(UNIFORM + ".programId(Ljava/lang/Object;)I"), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains(".clear()V")), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.startsWith(SHADER) || r.startsWith("com/mojang/blaze3d/shaders/")), refs.toString());
        for (MethodNode method : out.methods) new Analyzer<>(new BasicVerifier()).analyze(out.name, method);
    }

    @Test
    @DisplayName("offline for 26.3 a Fabric mod's uniform on the host interface reaches the static stand-ins")
    void offlineFabric263() throws Exception {
        ClassNode out = transform("26.3", name -> null, fabricShaderCode("com/mojang/renderpearl/backend/opengl/"), null);
        List<String> refs = refs(out);
        assertTrue(refs.contains(UNIFORM + ".noField(Ljava/lang/Object;)Ljava/lang/Object;"), refs.toString());
        assertTrue(refs.contains(UNIFORM + ".set(Ljava/lang/Object;F)V"), refs.toString());
        assertTrue(refs.contains(UNIFORM + ".upload(Ljava/lang/Object;)V"), refs.toString());
        for (MethodNode method : out.methods) new Analyzer<>(new BasicVerifier()).analyze(out.name, method);
    }

    @Test
    @DisplayName("for a Forge 26.2 target, whose chain never renamed ShaderInstance, the old class becomes a stand-in that builds itself")
    void offlineForge262() throws Exception {
        McReflect.setForceForge(true);
        ClassNode out = transform("26.2", name -> null, forgeShaderCode(), null);
        List<String> refs = refs(out);
        String legacy = LegacyShaderMembersBridge.LEGACY_SHADER;
        assertTrue(refs.contains(legacy + ".<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Ljava/lang/String;"
                + "Lcom/mojang/blaze3d/vertex/VertexFormat;)V"), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.startsWith(LegacyShaderMembersBridge.PROGRAMS)), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains("setupShaderLights")), refs.toString());
        assertTrue(refs.contains(UNIFORM + ".inert(Ljava/lang/Object;Ljava/lang/String;)L" + UNIFORM + ";"), refs.toString());
        for (MethodNode method : out.methods) new Analyzer<>(new BasicVerifier()).analyze(out.name, method);
    }

    @ParameterizedTest(name = "{1}")
    @DisplayName("real hosts: the old shader class becomes the host's program after a loader chain, else the stand-in")
    @CsvSource({
            "26.1.2, com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar, com/mojang/blaze3d/opengl/GlProgram",
            "26.2, com/mojang/minecraft/26.2/minecraft-26.2-client.jar, com/mojang/blaze3d/opengl/GlProgram",
            "26.3, com/mojang/minecraft/26.3/minecraft-26.3-client.jar, com/mojang/renderpearl/backend/opengl/GlProgram",
            "26.3, net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar, com/mojang/renderpearl/backend/opengl/GlProgram",
            "26.2, net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar, com/mojang/blaze3d/opengl/GlProgram"})
    void realHosts(String version, String jar, String program) throws Exception {
        Path host = LIBRARIES.resolve(jar);
        assumeTrue(Files.isRegularFile(host), "needs " + host);
        ClassNode out;
        try (ZipFile zip = new ZipFile(host.toFile())) {
            boolean forge = jar.contains("forge-");
            McReflect.setForceForge(forge);
            out = transform(version, name -> read(zip, name), forgeShaderCode(), forge ? null : program);
        }
        List<String> refs = refs(out);
        String built = jar.contains("forge-") ? LegacyShaderMembersBridge.LEGACY_SHADER + ".<init>("
                : LegacyShaderMembersBridge.PROGRAMS + ".create(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)L" + program + ";";
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(built)), refs.toString());
        assertTrue(refs.contains(UNIFORM + ".inert(Ljava/lang/Object;Ljava/lang/String;)L" + UNIFORM + ";"), refs.toString());
        // The stand-in keeps the old uniform fields itself; a host program answers them through the helper.
        String field = jar.contains("forge-") ? LegacyShaderMembersBridge.LEGACY_SHADER + ".MODEL_VIEW_MATRIX:L" + UNIFORM + ";"
                : UNIFORM + ".inertField(Ljava/lang/Object;)L" + UNIFORM + ";";
        assertTrue(refs.contains(field), refs.toString());
    }

    /** {@code chainTarget} stands in for a loader chain that already renamed ShaderInstance, as NeoForge's does; null for none. */
    private ClassNode transform(String version, Function<String, ClassNode> host, byte[] code, String chainTarget) {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = version;
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        if (chainTarget != null) t.registerClassRedirect(SHADER, chainTarget);
        LegacyShaderMembersBridge.register(t, host);
        ClassNode node = new ClassNode();
        new ClassReader(t.transformClass(code, "test/shader/Old")).accept(node, 0);
        return node;
    }

    /** A 1.20.1 Forge mod's custom shader: built, a uniform set, a shared uniform read, cleared and its id read. */
    private static byte[] forgeShaderCode() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/shader/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, SHADER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitLdcInsn("name");
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SHADER, "<init>", "(Lnet/minecraft/server/packs/resources/ResourceProvider;"
                + "Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormat;)V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("Strength");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SHADER, "safeGetUniform", "(Ljava/lang/String;)L"
                + LegacyShaderMembersBridge.OLD_ABSTRACT_UNIFORM + ";", false);
        mv.visitInsn(Opcodes.FCONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyShaderMembersBridge.OLD_ABSTRACT_UNIFORM, "set", "(F)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, SHADER, "MODEL_VIEW_MATRIX", "L" + LegacyShaderMembersBridge.OLD_UNIFORM + ";");
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SHADER, "clear", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SHADER, "getId", "()I", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/mojang/blaze3d/systems/RenderSystem", "setupShaderLights", "(L" + SHADER + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A Fabric mod after the intermediary remap: its old uniform type is the host's uniform interface. */
    private static byte[] fabricShaderCode(String pkg) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/shader/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(L" + pkg + "GlProgram;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, pkg + "GlProgram", "COLOR_MODULATOR", "L" + pkg + "Uniform;");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.FCONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, pkg + "Uniform", "set", "(F)V", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, pkg + "Uniform", "upload", "()V", true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(ZipFile zip, String name) {
        try {
            ZipEntry entry = zip.getEntry(name + ".class");
            if (entry == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            return node;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static List<String> refs(ClassNode node) {
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) refs.add(field.owner + "." + field.name + ":" + field.desc);
                if (insn instanceof TypeInsnNode type) refs.add(type.desc);
            }
        }
        return refs;
    }
}
