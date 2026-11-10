/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.common.LegacyReloadListenerBridge.Names;
import com.retromod.shim.common.LegacyReloadListenerBridge.Shape;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path gives an old reload listener, its lambda and its calls the host's reload, in the mod and its nested library. */
class ReloadListenerPathParityTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} mod, {1} {2} on {3}")
    @CsvSource({"forge, forge, runtime, 26.2", "forge, forge, transform, 26.3", "forge, forge, aot, 26.2",
            "forge, forge, batch, 1.21.5", "forge, neoforge, runtime, 26.3", "forge, neoforge, transform, 26.3",
            "neoforge, neoforge, runtime, 26.3", "neoforge, neoforge, transform, 1.21.5",
            "fabric, fabric, runtime, 26.3", "fabric, fabric, transform, 26.3", "fabric, fabric, aot, 26.3",
            "fabric, fabric, runtime, 1.21.5", "fabric, fabric, transform, 1.21.11"})
    @DisplayName("each path adds the host's reload, builds the lambda as a host listener, and moves the old call")
    void everyPathBridgesTheOldListener(String source, String hostLoader, String path, String host) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("models.jar");
        Files.write(input, modJar(source));

        Path output = translate(hostLoader, path, host, input);

        // Fabric hosts before 26.1 run intermediary names; everything else runs Mojang's.
        boolean intermediary = source.equals("fabric") && RetromodVersion.compareMcVersions(host, "26.1") < 0;
        Names names = intermediary ? Names.INTERMEDIARY : Names.MOJANG;
        Shape shape = RetromodVersion.compareMcVersions(host, "1.21.9") < 0 ? Shape.BARRIER : Shape.STATE;
        try (JarFile jar = new JarFile(output.toFile())) {
            assertBridged(jar, "mod", names, shape, source + " " + path + " on " + host);
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(nestedPath(source))).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                assertBridged(lib, "lib", names, shape, "nested library, " + source + " " + path + " on " + host);
            }
        }
    }

    private static void assertBridged(JarFile jar, String pkg, Names names, Shape shape, String where) throws Exception {
        ClassNode listener = read(jar, pkg + "/Listener");
        assertNotNull(method(listener, names.reload(), names.desc(shape)), where + ": the class has the host's reload");
        assertNotNull(method(listener, names.reload(), names.desc(Shape.PROFILED)), where + ": and keeps its own");

        // The lambda is built as the embedded old-shape interface and handed back as the host's.
        MethodNode register = method(read(jar, pkg + "/Registrar"), "register", "()L" + names.listener() + ";");
        InvokeDynamicInsnNode lambda = only(register, InvokeDynamicInsnNode.class);
        String built = Type.getReturnType(lambda.desc).getInternalName();
        assertTrue(built.startsWith(SyntheticEmbedder.PREFIX) && built.endsWith("/LegacyReloadListener"),
                where + ": " + built);
        assertNotNull(jar.getJarEntry(built + ".class"), where + ": the interface is embedded");
        assertTrue(only(register, MethodInsnNode.class).owner.endsWith("/LegacyReloadListeners"), where);

        // A call on the host interface reaches the host's reload.
        MethodInsnNode call = only(method(read(jar, pkg + "/Forwarder"), "forward", null), MethodInsnNode.class);
        if (shape == Shape.STATE) {
            assertTrue(call.owner.startsWith(SyntheticEmbedder.PREFIX) && call.owner.endsWith("/LegacyReloadListeners"),
                    where + ": " + call.owner);
            assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(), where);
        } else {
            assertEquals(names.listener() + "." + names.reload() + names.desc(Shape.BARRIER),
                    call.owner + "." + call.name + call.desc, where);
        }
        for (String name : List.of("Listener", "Registrar", "Forwarder")) {
            ClassNode node = read(jar, pkg + "/" + name);
            for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private Path translate(String hostLoader, String path, String host, Path input) throws Exception {
        McReflect.setForceForge(hostLoader.equals("forge"));
        McReflect.setForceNeoForge(hostLoader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(hostLoader.equals("fabric") ? null : hostLoader);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = out.resolve("models.jar");
        switch (path) {
            case "runtime" -> output = runtime(hostLoader, host, input, out);
            case "transform" -> CliHarness.transform(input, output, host);
            case "aot" -> CliHarness.aot(input, output, host);
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    List<Path> all = jars.filter(jar -> jar.toString().endsWith(".jar")).toList();
                    assertEquals(1, all.size(), "the batch wrote one jar: " + all);
                    output = all.get(0);
                }
            }
        }
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what the loader's entry point registers on {@code host}, then runs its mod transformer. */
    private Path runtime(String hostLoader, String host, Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        if (hostLoader.equals("fabric")) {
            RetromodPreLaunch.registerShimsForTransform(host);
            return new FabricModTransformer(host).transformMod(input, out);
        }
        Set<String> types = hostLoader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        return new ForgeModTransformer(host).transformMod(input, out);
    }

    // ---- The mod: a 1.20.1 Forge, 1.21.1 NeoForge or 1.21.1 Fabric jar with a nested library ----

    private static byte[] modJar(String source) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put(metadataPath(source), metadata(source, "modellib", false));
        lib.putAll(fixtureClasses("lib", source));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(source), metadata(source, "models", true));
        mod.putAll(fixtureClasses("mod", source));
        mod.put(nestedPath(source), LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    /** A listener class, a registrar that builds a method reference, and a forwarder, in the names {@code source} shipped. */
    private static Map<String, byte[]> fixtureClasses(String pkg, String source) {
        Names names = source.equals("fabric") ? Names.INTERMEDIARY : Names.MOJANG;
        // Forge 1.20.1 names members by SRG id; the classes already have Mojang's names.
        String reload = source.equals("forge") ? "m_5540_" : names.reload();
        String old = names.desc(Shape.PROFILED);
        Map<String, byte[]> classes = new LinkedHashMap<>();

        ClassWriter listener = classWriter(pkg + "/Listener", names.listener());
        MethodVisitor body = listener.visitMethod(Opcodes.ACC_PUBLIC, reload, old, null, null);
        body.visitCode();
        body.visitInsn(Opcodes.ACONST_NULL);
        body.visitInsn(Opcodes.ARETURN);
        end(body);
        classes.put(pkg + "/Listener.class", finish(listener));

        // register() { return Registrar::load; }
        String registrar = pkg + "/Registrar";
        ClassWriter registrarWriter = classWriter(registrar, null);
        MethodVisitor register = registrarWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                "()L" + names.listener() + ";", null, null);
        register.visitCode();
        register.visitInvokeDynamicInsn(reload, "()L" + names.listener() + ";", new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;"
                + "Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                Type.getMethodType(old), new Handle(Opcodes.H_INVOKESTATIC, registrar, "load", old, false),
                Type.getMethodType(old));
        register.visitInsn(Opcodes.ARETURN);
        end(register);
        MethodVisitor load = registrarWriter.visitMethod(Opcodes.ACC_STATIC, "load", old, null, null);
        load.visitCode();
        load.visitInsn(Opcodes.ACONST_NULL);
        load.visitInsn(Opcodes.ARETURN);
        end(load);
        classes.put(registrar + ".class", finish(registrarWriter));

        // forward(listener, old arguments...) { return listener.reload(old arguments...); }
        ClassWriter forwarder = classWriter(pkg + "/Forwarder", null);
        String forwardDesc = "(L" + names.listener() + ";" + old.substring(1);
        MethodVisitor forward = forwarder.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "forward", forwardDesc,
                null, null);
        forward.visitCode();
        for (int slot = 0; slot <= 6; slot++) forward.visitVarInsn(Opcodes.ALOAD, slot);
        forward.visitMethodInsn(Opcodes.INVOKEINTERFACE, names.listener(), reload, old, true);
        forward.visitInsn(Opcodes.ARETURN);
        end(forward);
        classes.put(pkg + "/Forwarder.class", finish(forwarder));
        return classes;
    }

    private static ClassWriter classWriter(String name, String iface) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object",
                iface == null ? null : new String[] {iface});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        end(init);
        return cw;
    }

    private static byte[] finish(ClassWriter cw) {
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static String metadataPath(String source) {
        return switch (source) {
            case "fabric" -> "fabric.mod.json";
            case "forge" -> "META-INF/mods.toml";
            default -> "META-INF/neoforge.mods.toml";
        };
    }

    private static String nestedPath(String source) {
        return source.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    private static byte[] metadata(String source, String modId, boolean nestsLibrary) {
        String text = switch (source) {
            case "fabric" -> "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"1.21.1\",\"fabricloader\":\">=0.15.0\"}"
                    + (nestsLibrary ? ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]" : "") + "}";
            case "forge" -> "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                    + modId + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n"
                    + "mandatory=true\nversionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
            default -> "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                    + modId + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"neoforge\"\n"
                    + "type=\"required\"\nversionRange=\"[21.1,)\"\nordering=\"NONE\"\nside=\"BOTH\"\n"
                    + "[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n"
                    + "type=\"required\"\nversionRange=\"[1.21.1,1.21.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        };
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ---- Reading the output ----

    private static ClassNode read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    /** The method {@code name} with {@code desc}, or the only one named {@code name} when {@code desc} is null. */
    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && (desc == null || m.desc.equals(desc)))
                .findFirst().orElse(null);
    }

    private static <T extends AbstractInsnNode> T only(MethodNode method, Class<T> kind) {
        assertNotNull(method);
        List<T> found = new java.util.ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (kind.isInstance(insn)) found.add(kind.cast(insn));
        }
        assertEquals(1, found.size(), method.name + " should hold one " + kind.getSimpleName() + ": " + found.size());
        return found.get(0);
    }
}
