/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_10_to_1_21_11;
import com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2;
import com.retromod.shim.neoforge.NeoForge_1_21_10_to_1_21_11;
import com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Registry.getOptional exists on every host, so an old mod's call must reach it unchanged. */
class RegistryGetOptionalKeptTest {

    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String LOCATION = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String IDENTIFIER = "Lnet/minecraft/resources/Identifier;";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
    }

    @Test
    @DisplayName("On a 1.21.4 host a 1.21.1 mod's getOptional(ResourceLocation) stays as written")
    void keptOnAnObfuscatedHost(@TempDir Path directory) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        RetromodTransformer transformer = indexed(directory, new NeoForge_1_21_1_to_1_21_2());

        assertEquals(List.of(REGISTRY + ".getOptional(" + LOCATION + ")Ljava/util/Optional;"),
                registryCalls(transformer, LOCATION));
    }

    @Test
    @DisplayName("On NeoForge 26.3 both spellings of the call end as getOptional(Identifier)")
    void neoForgeKeepsGetOptional(@TempDir Path directory) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertKeptOn26(indexed(directory, new NeoForge_1_21_1_to_1_21_2(), new NeoForge_1_21_10_to_1_21_11()));
    }

    @Test
    @DisplayName("On Fabric 26.3 both spellings of the call end as getOptional(Identifier)")
    void fabricKeepsGetOptional(@TempDir Path directory) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertKeptOn26(indexed(directory, new Fabric_1_21_1_to_1_21_2(), new Fabric_1_21_10_to_1_21_11()));
    }

    private static void assertKeptOn26(RetromodTransformer transformer) {
        String kept = REGISTRY + ".getOptional(" + IDENTIFIER + ")Ljava/util/Optional;";
        // The old redirect sent these to getOptionalValue, which no host's Registry has.
        assertEquals(List.of(kept), registryCalls(transformer, LOCATION));
        assertEquals(List.of(kept), registryCalls(transformer, IDENTIFIER));
        assertTrue(transformer.getMethodRedirects().keySet().stream()
                        .noneMatch(k -> k.owner().equals(REGISTRY) && k.name().equals("getOptional")),
                "no redirect may be keyed on Registry.getOptional");
        assertEquals(0, transformer.getClassesHittingIterationCap(),
                "the old redirect and the host index used to rename the call back and forth");
    }

    /** A fresh transformer with these shims, indexed against a host whose Registry has only getOptional. */
    private static RetromodTransformer indexed(Path directory, VersionShim... shims) throws Exception {
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        transformer.initFuzzyResolver(hostJar(directory));
        for (VersionShim shim : shims) shim.registerRedirects(transformer);
        return transformer;
    }

    private static Path hostJar(Path directory) throws Exception {
        ClassWriter registry = new ClassWriter(0);
        registry.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                REGISTRY, null, "java/lang/Object", null);
        for (String arg : new String[]{IDENTIFIER, KEY}) {
            registry.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getOptional",
                    "(" + arg + ")Ljava/util/Optional;", null, null).visitEnd();
        }
        registry.visitEnd();
        Path jar = directory.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(REGISTRY + ".class"));
            out.write(registry.toByteArray());
            out.closeEntry();
        }
        return jar;
    }

    /** The Registry calls left after transforming a class that calls getOptional with {@code argument}. */
    private static List<String> registryCalls(RetromodTransformer transformer, String argument) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Lookup", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "find",
                "(L" + REGISTRY + ";" + argument + ")Ljava/util/Optional;", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, REGISTRY, "getOptional",
                "(" + argument + ")Ljava/util/Optional;", true);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "mod/Lookup")).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(REGISTRY)) {
                    calls.add(call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }
}
