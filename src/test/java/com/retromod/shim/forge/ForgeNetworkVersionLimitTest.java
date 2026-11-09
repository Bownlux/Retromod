/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.SyntheticLibraries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 1.20.2 reused the old networking class names, so only older mods may take the 1.20.1 networking shim. */
class ForgeNetworkVersionLimitTest {

    private static final String NET = "net/minecraftforge/network/";
    private static final String DISTRIBUTOR = NET + "PacketDistributor";
    private static final String TARGET = DISTRIBUTOR + "$PacketTarget";
    private static final String POINT = DISTRIBUTOR + "$TargetPoint";
    private static final String DIRECTION = NET + "NetworkDirection";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";

    @AfterEach
    void reset() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A Forge 1.21.1 mod keeps the host's PacketDistributor, TargetPoint and NetworkDirection")
    void newerModKeepsHostNetworking() {
        RetromodTransformer transformer = bridged("1.21.1");
        List<String> refs = references(transformer.transformClass(newApiSender(), "mod/Sender"));

        assertTrue(refs.stream().noneMatch(r -> r.contains("retromod")),
                "a 1.20.2 and newer mod must not be pointed at Retromod's 1.20.1 networking shim: " + refs);
        assertTrue(refs.contains(NET + "SimpleChannel.send(Ljava/lang/Object;L" + TARGET + ";)V"), refs.toString());
        assertTrue(refs.contains(POINT + ".<init>(DDDD" + KEY + ")V"), refs.toString());
        assertTrue(refs.contains(DIRECTION + ".PLAY_TO_SERVER"), refs.toString());
    }

    @ParameterizedTest(name = "forge {0}")
    @ValueSource(strings = {"[47,)", "[48,)"})
    @DisplayName("A mod that declares minecraft [1.20,) is judged by its Forge floor: 48 keeps the host's networking class")
    void buildVersionDecidesTheClassLimit(String forgeRange, @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        RetromodTransformer transformer = bridged("[1.20,)");
        Path jar = dir.resolve("sender.jar");
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"sender\"\n"
                + "[[dependencies.sender]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"" + forgeRange + "\"\n"
                + "[[dependencies.sender]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.20,)\"\n";
        Files.write(jar, jarOfEntries(Map.of("META-INF/mods.toml", toml.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        transformer.setModSourceJar(jar);

        List<String> refs = references(transformer.transformClass(newApiSender(), "mod/Sender"));

        if (forgeRange.equals("[48,)")) {
            assertTrue(refs.contains(DIRECTION + ".PLAY_TO_SERVER"), "a Forge 48 build is 1.20.2 and keeps the host class: " + refs);
        } else {
            assertTrue(refs.stream().noneMatch(r -> r.startsWith(DIRECTION + ".")),
                    "a Forge 47 build is 1.20.1 and takes the shim's NetworkDirection: " + refs);
        }
    }

    private static byte[] jarOfEntries(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("A Forge 1.20.1 mod still sends through the shim")
    void olderModTakesTheShim() {
        RetromodTransformer transformer = bridged("1.20.1");
        List<String> refs = references(transformer.transformClass(oldApiSender(), "mod/OldSender"));

        assertTrue(refs.stream().noneMatch(r -> r.startsWith(DISTRIBUTOR + ".") || r.startsWith(TARGET)),
                "the 1.20.1 PacketDistributor no longer exists on newer hosts: " + refs);
        assertTrue(refs.stream().anyMatch(r -> r.contains("NetworkShim$SimpleChannelWrapper.send")), refs.toString());
    }

    @Test
    @DisplayName("A mod whose version is unknown keeps the shim, as it keeps every shim for its host")
    void unknownVersionKeepsTheShim() {
        RetromodTransformer transformer = bridged(null);
        List<String> refs = references(transformer.transformClass(oldApiSender(), "mod/OldSender"));
        assertTrue(refs.stream().anyMatch(r -> r.contains("NetworkShim")), refs.toString());
    }

    @Test
    @DisplayName("A Forge 1.21.1 mod's Class.forName of PacketDistributor keeps the host class")
    void reflectionKeepsHostClassForNewerMod() {
        RetromodTransformer transformer = bridged("1.21.1");
        assertEquals(List.of("net.minecraftforge.network.PacketDistributor"),
                stringConstants(transformer.transformClass(reflectiveLookup(), "mod/Lookup")));
    }

    @Test
    @DisplayName("A Forge 1.20.1 mod's Class.forName of PacketDistributor still names the shim")
    void reflectionTakesShimForOlderMod() {
        RetromodTransformer transformer = bridged("1.20.1");
        assertEquals(List.of("com.retromod.shim.forge.embedded.NetworkShim$PacketDistributor"),
                stringConstants(transformer.transformClass(reflectiveLookup(), "mod/Lookup")));
    }

    /** {@code Class.forName("net.minecraftforge.network.PacketDistributor")}. */
    private static byte[] reflectiveLookup() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Lookup", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "find", "()Ljava/lang/Class;", null,
                new String[]{"java/lang/ClassNotFoundException"});
        m.visitCode();
        m.visitLdcInsn("net.minecraftforge.network.PacketDistributor");
        m.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> stringConstants(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> strings = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) strings.add(s);
            }
        }
        return strings;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"directory", "jar", "nested"})
    @DisplayName("Embedding stand-ins into a Forge 1.21.1 mod keeps the host's networking classes")
    void embedKeepsHostNetworkingForNewerMod(String path) throws Exception {
        RetromodTransformer transformer = bridged("1.21.1");
        byte[] sender = transformer.transformClass(newApiSender(), "mod/Sender");

        List<String> refs = references(embedBesideHelper(transformer, "mod/Sender", sender, path));

        assertTrue(refs.stream().noneMatch(r -> r.contains("NetworkShim")),
                "the embed must not put back the redirect the class pass left out: " + refs);
        assertTrue(refs.contains(NET + "SimpleChannel.send(Ljava/lang/Object;L" + TARGET + ";)V"), refs.toString());
        assertTrue(refs.contains(POINT + ".<init>(DDDD" + KEY + ")V"), refs.toString());
        assertTrue(refs.contains(DIRECTION + ".PLAY_TO_SERVER"), refs.toString());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"directory", "jar", "nested"})
    @DisplayName("Embedding stand-ins into a Forge 1.20.1 mod keeps it on the networking shim")
    void embedKeepsShimForOlderMod(String path) throws Exception {
        RetromodTransformer transformer = bridged("1.20.1");
        byte[] sender = transformer.transformClass(oldApiSender(), "mod/OldSender");

        List<String> refs = references(embedBesideHelper(transformer, "mod/OldSender", sender, path));

        assertTrue(refs.stream().noneMatch(r -> r.startsWith(DISTRIBUTOR + ".") || r.startsWith(TARGET)),
                "the 1.20.1 PacketDistributor no longer exists on newer hosts: " + refs);
        assertTrue(refs.stream().anyMatch(r -> r.contains("NetworkShim$SimpleChannelWrapper.send")), refs.toString());
    }

    private static final String HELPER = "com/retromod/generated/TestNetworkHelper";

    /**
     * Runs one embed path over a jar holding {@code classBytes} and a class that calls a registered
     * stand-in, which is what makes the embed rewrite the jar's classes, and returns the class after it.
     */
    private static byte[] embedBesideHelper(RetromodTransformer transformer, String className,
            byte[] classBytes, String path) throws Exception {
        transformer.registerSyntheticClass(HELPER, emptyClass(HELPER));
        Map<String, byte[]> classes = Map.of(className, classBytes, "mod/UsesHelper", helperCaller());
        Path dir = Files.createTempDirectory("retromod-net-embed");
        switch (path) {
            case "directory" -> {
                for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                    Path file = dir.resolve(entry.getKey() + ".class");
                    Files.createDirectories(file.getParent());
                    Files.write(file, entry.getValue());
                }
                SyntheticEmbedder.embed(dir, "netmod", transformer);
                return Files.readAllBytes(dir.resolve(className + ".class"));
            }
            case "jar" -> {
                Path jar = dir.resolve("netmod.jar");
                Files.write(jar, jarOf(classes));
                SyntheticEmbedder.embedIntoJarOrDiscard(jar, "netmod", transformer, SyntheticLibraries.NONE);
                return entryOf(Files.readAllBytes(jar), className + ".class");
            }
            default -> {
                SyntheticEmbedder.ByteEmbeddingResult result =
                        SyntheticEmbedder.embedIntoJarBytes(jarOf(classes), "netmod", transformer);
                assertTrue(result.succeeded());
                return entryOf(result.jarBytes(), className + ".class");
            }
        }
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "touch", "()V", null, null);
        m.visitCode();
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] helperCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/UsesHelper", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        m.visitCode();
        m.visitMethodInsn(INVOKESTATIC, HELPER, "touch", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] jarOf(Map<String, byte[]> classes) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] entryOf(byte[] jarBytes, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (entry.getName().equals(name)) return in.readAllBytes();
            }
        }
        throw new AssertionError(name + " is missing from the embedded jar");
    }

    private static RetromodTransformer bridged(String modVersion) {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Forge_1_20_to_NeoForge_1_21.registerNetworkBridge(transformer);
        transformer.setModSourceVersion(modVersion);
        return transformer;
    }

    /** The 1.20.2 API: {@code channel.send(msg, PacketDistributor.SERVER.noArg())}, a target point, and a direction. */
    private static byte[] newApiSender() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Sender", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "send",
                "(L" + NET + "SimpleChannel;Ljava/lang/Object;" + KEY + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitFieldInsn(GETSTATIC, DISTRIBUTOR, "SERVER", "L" + DISTRIBUTOR + ";");
        m.visitMethodInsn(INVOKEVIRTUAL, DISTRIBUTOR, "noArg", "()L" + TARGET + ";", false);
        m.visitMethodInsn(INVOKEVIRTUAL, NET + "SimpleChannel", "send", "(Ljava/lang/Object;L" + TARGET + ";)V", false);
        m.visitTypeInsn(NEW, POINT);
        m.visitInsn(DUP);
        m.visitInsn(DCONST_0);
        m.visitInsn(DCONST_0);
        m.visitInsn(DCONST_0);
        m.visitInsn(DCONST_1);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKESPECIAL, POINT, "<init>", "(DDDD" + KEY + ")V", false);
        m.visitInsn(POP);
        m.visitFieldInsn(GETSTATIC, DIRECTION, "PLAY_TO_SERVER", "L" + DIRECTION + ";");
        m.visitInsn(POP);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The 1.20.1 API: {@code simple.SimpleChannel.send(PacketDistributor.SERVER.noArg(), msg)}. */
    private static byte[] oldApiSender() {
        String oldChannel = NET + "simple/SimpleChannel";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/OldSender", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "send",
                "(L" + oldChannel + ";Ljava/lang/Object;)V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETSTATIC, DISTRIBUTOR, "SERVER", "L" + DISTRIBUTOR + ";");
        m.visitMethodInsn(INVOKEVIRTUAL, DISTRIBUTOR, "noArg", "()L" + TARGET + ";", false);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, oldChannel, "send", "(L" + TARGET + ";Ljava/lang/Object;)V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Every field, method and type reference in the class, as owner.name(desc), owner.name, or a type. */
    private static List<String> references(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                else if (insn instanceof FieldInsnNode field) refs.add(field.owner + "." + field.name);
                else if (insn instanceof TypeInsnNode type) refs.add(type.desc);
            }
        }
        return refs;
    }
}
