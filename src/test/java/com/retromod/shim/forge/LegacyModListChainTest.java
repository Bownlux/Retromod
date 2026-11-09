/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Iceberg, Xaero's Minimap and others built for 1.21.1 ask ModList.get() and LoadingModList.get(), which Forge 26.2 removed. */
class LegacyModListChainTest {

    private static final String MOD_LIST = LegacyModListBridge.MOD_LIST;
    private static final String LOADING_MOD_LIST = LegacyModListBridge.LOADING_MOD_LIST;
    private static final String CALLER = "mod/PlatformHelper";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "a {0} mod on Forge {1}")
    @CsvSource({"1.20.1, 26.2", "1.21.1, 26.2", "1.21.1, 26.3"})
    @DisplayName("a mod's chain reaches the mod list bridge whatever version it starts from")
    void everyChainTakesTheBridge(String built, String host) throws Exception {
        McReflect.setForceForge(true);
        ModVersionDetector.setDefaultPreferredLoader("forge");
        Path input = Files.createDirectories(dir.resolve("in")).resolve("iceberg.jar");
        Files.write(input, LoaderApiFixtures.jar(Map.of("META-INF/mods.toml", modsToml(built), CALLER + ".class", caller())));
        Path output = dir.resolve("out.jar");

        CliHarness.transform(input, output, host);

        List<String> calls = calls(output);
        String where = "a " + built + " mod on " + host + ": " + calls;
        assertTrue(calls.stream().anyMatch(c -> c.endsWith("LegacyModList.get()L" + MOD_LIST + ";")), where);
        assertTrue(calls.stream().anyMatch(c -> c.endsWith("LegacyModList.isLoaded(L" + MOD_LIST + ";Ljava/lang/String;)Z")), where);
        assertTrue(calls.stream().anyMatch(c -> c.endsWith("LegacyLoadingModList.get()L" + LOADING_MOD_LIST + ";")), where);
        assertTrue(calls.stream().anyMatch(c -> c.endsWith("LegacyLoadingModList.getModFileById(L" + LOADING_MOD_LIST
                + ";Ljava/lang/String;)Lnet/minecraftforge/fml/loading/moddiscovery/ModFileInfo;")), where);
        assertTrue(calls.stream().noneMatch(c -> c.startsWith(MOD_LIST + ".") || c.startsWith(LOADING_MOD_LIST + ".")),
                "no call is left on the host classes in their old instance form: " + where);
    }

    /** {@code ModList.get().isLoaded("jei")} and {@code LoadingModList.get().getModFileById("jei")}, as Iceberg's platform helper asks. */
    private static byte[] caller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "check", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MOD_LIST, "get", "()L" + MOD_LIST + ";", false);
        mv.visitLdcInsn("jei");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOD_LIST, "isLoaded", "(Ljava/lang/String;)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LOADING_MOD_LIST, "get", "()L" + LOADING_MOD_LIST + ";", false);
        mv.visitLdcInsn("jei");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LOADING_MOD_LIST, "getModFileById",
                "(Ljava/lang/String;)Lnet/minecraftforge/fml/loading/moddiscovery/ModFileInfo;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Each call in the caller, as {@code owner-suffix.name(desc)} with the embedded package stripped. */
    private static List<String> calls(Path jarPath) throws Exception {
        List<String> calls = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(jar.getInputStream(jar.getJarEntry(CALLER + ".class")).readAllBytes()).accept(node, 0);
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }

    private static byte[] modsToml(String built) {
        String loader = built.equals("1.20.1") ? "47" : "52";
        String next = built.equals("1.20.1") ? "1.20.2" : "1.21.2";
        return ("modLoader=\"javafml\"\nloaderVersion=\"[" + loader + ",)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"iceberg\"\n"
                + "version=\"1.0.0\"\n[[dependencies.iceberg]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[" + built + "," + next + ")\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }
}
