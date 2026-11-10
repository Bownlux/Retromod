/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sophisticated Backpacks picks between two of Sophisticated Core's result kinds. Every path that
 * translates an addon after its library must frame that join as the library's base type, or the
 * method fails verification while the addon's items register.
 */
class LibraryFrameJoinPathParityTest {

    private static final String RESULT = "corelib/Result";
    private static final String SUCCESS = "corelib/Success";
    private static final String FAILURE = "corelib/Failure";
    private static final String PICKER = "addon/Picker";
    private static final String HOST = "26.2";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void reset() {
        McReflect.setForceNeoForge(false);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
        transformer.clearLinkedLibraries();
    }

    @Test
    @DisplayName("Forge 26.2: the runtime, the CLI batch and batch --aot join the library's types at their base")
    void forgePathsAgree(@TempDir Path dir) throws Exception {
        McReflect.setForceForge(true);
        Path in = inputs(dir, true);

        Map<String, String> joins = new LinkedHashMap<>();
        joins.put("Forge runtime", join(runtime(in, dir, true)));
        joins.put("CLI batch", join(batch(in, dir, "batch", "--target-loader", "forge")));
        joins.put("CLI batch --aot", join(batch(in, dir, "aot", "--aot", "--target-loader", "forge")));
        joins.forEach((path, join) -> assertEquals(RESULT, join, path));
    }

    @Test
    @DisplayName("Fabric 26.2: the runtime, the CLI batch and batch --aot join the library's types at their base")
    void fabricPathsAgree(@TempDir Path dir) throws Exception {
        McReflect.setForceForge(false);
        Path in = inputs(dir, false);

        Map<String, String> joins = new LinkedHashMap<>();
        joins.put("Fabric runtime", join(runtime(in, dir, false)));
        joins.put("CLI batch", join(batch(in, dir, "batch")));
        joins.put("CLI batch --aot", join(batch(in, dir, "aot", "--aot")));
        joins.forEach((path, join) -> assertEquals(RESULT, join, path));
    }

    /** The game's path: the library first, then the addon with the translated library as a library. */
    private Path runtime(Path in, Path dir, boolean forge) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = HOST;
        transformer.clearRedirectsForTesting();
        Set<String> types = forge ? Set.of("forge", "common") : Set.of("fabric", "common");
        if (!forge) IntermediaryToMojangMapper.applyTo(transformer);
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, HOST)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        Path out = Files.createDirectories(dir.resolve("runtime"));
        SyntheticLibraries libraries = SyntheticLibraries.create();
        Path library = forge
                ? new ForgeModTransformer(HOST).transformMod(in.resolve("corelib.jar"), out, SyntheticLibraries.NONE)
                : new FabricModTransformer(HOST).transformMod(in.resolve("corelib.jar"), out, SyntheticLibraries.NONE);
        libraries.add(library);
        return forge
                ? new ForgeModTransformer(HOST).transformMod(in.resolve("addon.jar"), out, libraries)
                : new FabricModTransformer(HOST).transformMod(in.resolve("addon.jar"), out, libraries);
    }

    private static Path batch(Path in, Path dir, String name, String... flags) throws Exception {
        Path out = Files.createDirectories(dir.resolve(name));
        CliHarness.batch(in, out, HOST, flags);
        try (var jars = Files.list(out)) {
            return jars.filter(p -> p.getFileName().toString().startsWith("addon")).findFirst()
                    .orElseThrow(() -> new AssertionError("the " + name + " batch wrote no addon jar"));
        }
    }

    /** The type every stack map frame inside {@code pick} holds on its stack, or what it found instead. */
    private static String join(Path jar) throws Exception {
        assertNotNull(jar, "the path wrote a jar");
        try (JarFile file = new JarFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(file.getInputStream(file.getJarEntry(PICKER + ".class")).readAllBytes())
                    .accept(node, ClassReader.EXPAND_FRAMES);
            List<Object> stacks = new ArrayList<>();
            for (MethodNode method : node.methods) {
                if (!method.name.equals("pick")) continue;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof FrameNode frame && frame.stack != null) stacks.addAll(frame.stack);
                }
            }
            return stacks.size() == 1 ? String.valueOf(stacks.get(0)) : stacks.toString();
        }
    }

    /** A library with a base and two kinds, and an addon that picks between them. */
    private static Path inputs(Path dir, boolean forge) throws Exception {
        Path in = Files.createDirectories(dir.resolve("in"));
        write(in.resolve("corelib.jar"), metadata(forge, "corelib", null),
                Map.of(RESULT, type(RESULT, "java/lang/Object"), SUCCESS, type(SUCCESS, RESULT),
                        FAILURE, type(FAILURE, RESULT)));
        write(in.resolve("addon.jar"), metadata(forge, "addon", "corelib"), Map.of(PICKER, picker(forge)));
        return in;
    }

    private static Map.Entry<String, byte[]> metadata(boolean forge, String id, String dependency) {
        if (forge) {
            return Map.entry("META-INF/mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                    + "[[mods]]\nmodId=\"" + id + "\"\nversion=\"1.0\"\n[[dependencies." + id + "]]\nmodId=\"minecraft\"\n"
                    + "mandatory=true\nversionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return Map.entry("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"1.20.1\"" + (dependency == null ? "" : ",\"" + dependency + "\":\"*\"")
                + "}}").getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path jar, Map.Entry<String, byte[]> metadata, Map<String, byte[]> classes) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(metadata.getKey()));
            out.write(metadata.getValue());
            out.closeEntry();
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static byte[] type(String name, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code static Result pick(boolean b) { Result r = b ? new Success() : new Failure(); Mth.color(0, 0, 0); return r; }}.
     * The Minecraft call makes every path rewrite the class, so its frames are computed again.
     */
    private static byte[] picker(boolean forge) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String first, String second) {
                return RESULT;
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, PICKER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(Z)L" + RESULT + ";", null, null);
        mv.visitCode();
        Label other = new Label();
        Label joined = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFEQ, other);
        mv.visitTypeInsn(Opcodes.NEW, SUCCESS);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SUCCESS, "<init>", "()V", false);
        mv.visitJumpInsn(Opcodes.GOTO, joined);
        mv.visitLabel(other);
        mv.visitTypeInsn(Opcodes.NEW, FAILURE);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, FAILURE, "<init>", "()V", false);
        mv.visitLabel(joined);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, forge ? "net/minecraft/util/Mth" : "net/minecraft/class_3532",
                forge ? "color" : "method_15353", "(FFF)I", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
