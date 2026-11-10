/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forge 1.20.2 reused its networking class names, so only older mods may leave them, on every translation path. */
class ForgeNetworkLimitPathTest {

    private static final String NET = "net/minecraftforge/network/";
    private static final String DISTRIBUTOR = NET + "PacketDistributor";
    private static final String TARGET = DISTRIBUTOR + "$PacketTarget";
    private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";

    @TempDir
    Path tempDir;

    private String savedTarget;

    @BeforeEach
    void saveTarget() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(false);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} on Forge {1}")
    @CsvSource({"runtime, 26.2", "runtime, 26.3", "transform, 26.2", "transform, 26.3", "batch, 26.3"})
    @DisplayName("A Forge 1.21.1 mod keeps Forge's PacketDistributor after its translation")
    void newerModKeepsHostNetworking(String path, String host) throws Exception {
        Path input = writeMod("netmod-1.21.1.jar", "[1.21.1,)", "[52,)", newApiSender(), blockCheck("is"));

        Path output = translate(path, host, input);

        List<String> sender = references(output, "mod/Sender");
        assertTrue(sender.contains(NET + "SimpleChannel.send(Ljava/lang/Object;L" + TARGET + ";)V")
                        && sender.contains(DISTRIBUTOR + ".PLAYER"),
                "a 1.20.2 and newer mod must keep Forge's own networking classes: " + sender);
        assertTrue(sender.stream().noneMatch(r -> r.contains("NetworkShim")),
                "the 1.20.1 networking shim must not reach a newer mod: " + sender);
        // The block check pulls in a stand-in, so the embed did rewrite this jar's classes.
        assertTrue(references(output, "mod/Blocks").stream().anyMatch(r -> r.startsWith(SyntheticEmbedder.PREFIX)),
                "the fixture must exercise the stand-in embed");
    }

    @ParameterizedTest(name = "{0} on Forge {1}")
    @CsvSource({"runtime, 26.3", "transform, 26.3", "batch, 26.3"})
    @DisplayName("A Forge 1.20.1 mod still sends through the networking shim after its translation")
    void olderModTakesTheShim(String path, String host) throws Exception {
        Path input = writeMod("netmod-1.20.1.jar", "[1.20.1,1.21)", "[47,)", oldApiSender(),
                blockCheck("m_60713_"));

        Path output = translate(path, host, input);

        List<String> sender = references(output, "mod/Sender");
        assertTrue(sender.stream().noneMatch(r -> r.startsWith(DISTRIBUTOR + ".") || r.startsWith(TARGET)),
                "the 1.20.1 PacketDistributor no longer exists on newer hosts: " + sender);
        assertTrue(sender.stream().anyMatch(r -> r.contains("NetworkShim")), sender.toString());
    }

    /** Translates {@code input} for a Forge {@code host} through one path and returns the output jar. */
    private Path translate(String path, String host, Path input) throws Exception {
        McReflect.setForceNeoForge(false);
        ModVersionDetector.setDefaultPreferredLoader("forge");
        Path out = Files.createDirectories(tempDir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> {
                registerForgeHost(host);
                yield new ForgeModTransformer(host).transformMod(input, out);
            }
            case "transform" -> {
                CliHarness.transform(input, out.resolve("out.jar"), host);
                yield out.resolve("out.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                yield out.resolve(input.getFileName());
            }
        };
        assertNotNull(output, path + " must produce a jar");
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what RetromodForge registers on a Forge {@code host} that runs Mojang names. */
    private static void registerForgeHost(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType())
                    && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
    }

    private Path writeMod(String name, String minecraftRange, String forgeRange, byte[] sender,
            byte[] blocks) throws Exception {
        Path input = Files.createDirectories(tempDir.resolve("in")).resolve(name);
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"" + forgeRange + "\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"netmod\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.netmod]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"" + forgeRange + "\"\n"
                + "[[dependencies.netmod]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"" + minecraftRange + "\"\n";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("mod/Sender.class"));
            out.write(sender);
            out.closeEntry();
            out.putNextEntry(new JarEntry("mod/Blocks.class"));
            out.write(blocks);
            out.closeEntry();
        }
        return input;
    }

    /** The 1.20.2 API, as Explorer's Compass sends: {@code channel.send(msg, PacketDistributor.PLAYER.with(player))}. */
    private static byte[] newApiSender() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Sender", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "send",
                "(L" + NET + "SimpleChannel;Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETSTATIC, DISTRIBUTOR, "PLAYER", "L" + DISTRIBUTOR + ";");
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DISTRIBUTOR, "with", "(Ljava/lang/Object;)L" + TARGET + ";", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NET + "SimpleChannel", "send",
                "(Ljava/lang/Object;L" + TARGET + ";)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The 1.20.1 API: {@code simple.SimpleChannel.send(PacketDistributor.SERVER.noArg(), msg)}. */
    private static byte[] oldApiSender() {
        String oldChannel = NET + "simple/SimpleChannel";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Sender", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "send",
                "(L" + oldChannel + ";Ljava/lang/Object;)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETSTATIC, DISTRIBUTOR, "SERVER", "L" + DISTRIBUTOR + ";");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DISTRIBUTOR, "noArg", "()L" + TARGET + ";", false);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, oldChannel, "send", "(L" + TARGET + ";Ljava/lang/Object;)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code state.is(block)} by {@code name}, which 26.1 removed, so the class pass calls a stand-in. */
    private static byte[] blockCheck(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Blocks", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isBlock",
                "(L" + BLOCK_STATE + ";L" + BLOCK + ";)Z", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BLOCK_STATE, name, "(L" + BLOCK + ";)Z", false);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Every field and method reference in one class of {@code jar}, as owner.name(desc) or owner.name. */
    private static List<String> references(Path jar, String className) throws Exception {
        ClassNode node = new ClassNode();
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(className + ".class");
            assertNotNull(entry, className + " is missing from " + jar.getFileName());
            new ClassReader(file.getInputStream(entry).readAllBytes()).accept(node, 0);
        }
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                else if (insn instanceof FieldInsnNode field) refs.add(field.owner + "." + field.name);
            }
        }
        return refs;
    }
}
