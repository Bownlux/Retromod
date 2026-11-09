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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
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
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/** A Forge 1.21.1 mod's registrations reach NeoForge 26.3 the same way on every translation path. */
class ForgeRegistrationNeoForgePathTest {

    private static final String HOST = "26.3";
    private static final String REG = "regmod/Registrations";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String EVENT = "net/minecraftforge/registries/RegisterEvent";
    private static final String HELPER = "net/minecraftforge/registries/RegisterEvent$RegisterHelper";
    private static final String DEFERRED = "net/minecraftforge/registries/DeferredRegister";

    @TempDir
    Path dir;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedTarget;
    private boolean savedNeoForge;
    private boolean savedForge;

    @BeforeEach
    void neoForgeTarget() throws Exception {
        // The game reads NeoForge's own classes; every path here reads the same stand-ins, as --host-jar gives the CLI.
        ClassResourceInspector.setOfflineHost(new java.net.URLClassLoader(
                new java.net.URL[]{hostJar().toUri().toURL()}, null));
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        savedNeoForge = McReflect.isForceNeoForge();
        savedForge = McReflect.isForceForge();
        // RetromodNeoForge and --target-loader neoforge set this before any shim registers.
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
    }

    @AfterEach
    void restore() {
        ClassResourceInspector.setOfflineHost(null);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        McReflect.setForceForge(savedForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A Forge mod bound for NeoForge gets the game's shims, so the Forge to NeoForge move applies to 1.21.1 mods")
    void offlineShimsMatchTheEntryPoint() {
        ShimRegistry registry = new ShimRegistry();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) registry.register(shim);
        Set<String> chosen = names(registry.findTranslationShims("forge", "1.21.1", HOST, true));
        assertTrue(chosen.contains("com.retromod.shim.forge.Forge_1_20_to_NeoForge_1_21"), chosen.toString());
        assertEquals(names(entryPointShims()), chosen, "the offline set is what RetromodNeoForge registers");
        // Off NeoForge, and before NeoForge ran its own entry point, a Forge mod keeps its version chain.
        assertEquals(names(registry.findShimChain("forge", "1.21.1", HOST)),
                names(registry.findTranslationShims("forge", "1.21.1", HOST, false)));
        assertEquals(names(registry.findShimChain("forge", "1.20.1", "1.20.1")),
                names(registry.findTranslationShims("forge", "1.20.1", "1.20.1", true)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"transform", "batch", "aot"})
    @DisplayName("Offline translation writes the same registration calls as the game")
    void offlineRegistrationMatchesRuntime(String path) throws Exception {
        List<String> runtime = references(translate("runtime"));
        List<String> offline = references(translate(path));
        assertTrue(runtime.stream().noneMatch(r -> r.startsWith("net/minecraftforge/registries/")), runtime.toString());
        assertTrue(runtime.contains("net/minecraft/core/registries/Registries.ITEM"), runtime.toString());
        assertTrue(offline.stream().noneMatch(r -> r.startsWith("net/minecraftforge/")),
                path + " must not keep Forge's registration API: " + offline);
        assertEquals(new TreeSet<>(runtime), new TreeSet<>(offline), path + " and the game write the same calls");
    }

    @Test
    @DisplayName("The batch output subscribes the registration class on NeoForge's mod bus")
    void subscriberAnnotationMoves() throws Exception {
        ClassNode node = classNode(translate("batch"));
        List<String> annotations = new ArrayList<>();
        if (node.visibleAnnotations != null) for (AnnotationNode a : node.visibleAnnotations) annotations.add(a.desc);
        assertTrue(annotations.stream().anyMatch(a -> a.startsWith("Lnet/neoforged/")), annotations.toString());
        assertFalse(annotations.stream().anyMatch(a -> a.startsWith("Lnet/minecraftforge/")), annotations.toString());
    }

    /** NeoForge's registration classes as the host has them, without bodies. */
    private Path hostJar() throws Exception {
        Path jar = Files.createDirectories(dir.resolve("host")).resolve("neoforge-host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String[] type : new String[][]{
                    {"net/neoforged/neoforge/registries/RegisterEvent", "class"},
                    {"net/neoforged/neoforge/registries/RegisterEvent$RegisterHelper", "interface"},
                    {"net/neoforged/neoforge/registries/DeferredRegister", "class"}}) {
                ClassWriter cw = new ClassWriter(0);
                int access = type[1].equals("interface") ? ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT : ACC_PUBLIC | ACC_SUPER;
                cw.visit(V21, access, type[0], null, "java/lang/Object", null);
                cw.visitEnd();
                out.putNextEntry(new JarEntry(type[0] + ".class"));
                out.write(cw.toByteArray());
                out.closeEntry();
            }
        }
        return jar;
    }

    private static Set<String> names(List<VersionShim> shims) {
        Set<String> out = new TreeSet<>();
        for (VersionShim shim : shims) out.add(shim.getClass().getName());
        return out;
    }

    /** What RetromodNeoForge registers on {@link #HOST}: every Forge, NeoForge and common shim up to it. */
    private static List<VersionShim> entryPointShims() {
        List<VersionShim> out = new ArrayList<>();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("neoforge", "forge", "common").contains(shim.getModLoaderType())
                    && ShimRegistry.isAvailableOnHost(shim, HOST)) {
                out.add(shim);
            }
        }
        return out;
    }

    /** Translates the fixture for NeoForge {@link #HOST} through one path and returns the output jar. */
    private Path translate(String path) throws Exception {
        Path input = writeMod(path);
        Path out = Files.createDirectories(dir.resolve("out-" + path));
        Path output = switch (path) {
            case "runtime" -> {
                RetromodVersion.TARGET_MC_VERSION = HOST;
                transformer.clearRedirectsForTesting();
                for (VersionShim shim : entryPointShims()) ShimRegistration.register(shim, transformer);
                SrgToMojangMapper.getInstance().applyTo(transformer);
                IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
                yield new ForgeModTransformer(HOST).transformMod(input, out);
            }
            case "transform" -> {
                CliHarness.transform(input, out.resolve("regmod.jar"), HOST);
                yield out.resolve("regmod.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("regmod.jar"), HOST);
                yield out.resolve("regmod.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, HOST);
                try (var jars = Files.list(out)) {
                    yield jars.filter(jar -> jar.toString().endsWith(".jar")).findFirst().orElseThrow();
                }
            }
        };
        assertNotNull(output, path + " must produce a jar");
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    private Path writeMod(String path) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in-" + path)).resolve("regmod-1.21.1.jar");
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[52,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"regmod\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.regmod]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[52,)\"\n"
                + "[[dependencies.regmod]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.21.1,1.21.2)\"\n";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(REG + ".class"));
            out.write(registrations());
            out.closeEntry();
        }
        return input;
    }

    /** The registration shapes a Forge 1.21.1 mod writes: a RegisterEvent subscriber and DeferredRegisters. */
    private static byte[] registrations() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, REG, null, "java/lang/Object", null);
        AnnotationVisitor sub = cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;", true);
        sub.visit("modid", "regmod");
        sub.visitEnum("bus", "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus;", "MOD");
        sub.visitEnd();

        // event.register(ForgeRegistries.Keys.ITEMS, helper -> ...)
        MethodVisitor items = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerItems", "(L" + EVENT + ";)V", null, null);
        items.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true).visitEnd();
        items.visitCode();
        items.visitVarInsn(ALOAD, 0);
        items.visitFieldInsn(GETSTATIC, "net/minecraftforge/registries/ForgeRegistries$Keys", "ITEMS", KEY);
        items.visitInsn(ACONST_NULL);
        items.visitMethodInsn(INVOKEVIRTUAL, EVENT, "register", "(" + KEY + "Ljava/util/function/Consumer;)V", false);
        items.visitInsn(RETURN);
        items.visitMaxs(0, 0);
        items.visitEnd();

        // helper.register(id, value), as the lambda body does
        MethodVisitor one = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerOne",
                "(L" + HELPER + ";Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Object;)V", null, null);
        one.visitCode();
        one.visitVarInsn(ALOAD, 0);
        one.visitVarInsn(ALOAD, 1);
        one.visitVarInsn(ALOAD, 2);
        one.visitMethodInsn(INVOKEINTERFACE, HELPER, "register",
                "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Object;)V", true);
        one.visitInsn(RETURN);
        one.visitMaxs(0, 0);
        one.visitEnd();

        // DeferredRegister.create(ForgeRegistries.Keys.BLOCKS, "regmod")
        MethodVisitor blocks = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "blocks", "()Ljava/lang/Object;", null, null);
        blocks.visitCode();
        blocks.visitFieldInsn(GETSTATIC, "net/minecraftforge/registries/ForgeRegistries$Keys", "BLOCKS", KEY);
        blocks.visitLdcInsn("regmod");
        blocks.visitMethodInsn(INVOKESTATIC, DEFERRED, "create", "(" + KEY + "Ljava/lang/String;)L" + DEFERRED + ";", false);
        blocks.visitInsn(ARETURN);
        blocks.visitMaxs(0, 0);
        blocks.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode classNode(Path jar) throws Exception {
        ClassNode node = new ClassNode();
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(REG + ".class");
            assertNotNull(entry, REG + " is missing from " + jar.getFileName());
            new ClassReader(file.getInputStream(entry).readAllBytes()).accept(node, 0);
        }
        return node;
    }

    /** Every field and method reference in the registration class, as owner.name, with Retromod's per-mod prefix cut. */
    private static List<String> references(Path jar) throws Exception {
        List<String> refs = new ArrayList<>();
        for (MethodNode method : classNode(jar).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(strip(call.owner) + "." + call.name);
                else if (insn instanceof FieldInsnNode field) refs.add(strip(field.owner) + "." + field.name);
            }
        }
        return refs;
    }

    /** Embedded helpers carry a per-jar package; the class after it is what both paths share. */
    private static String strip(String owner) {
        if (!owner.startsWith(SyntheticEmbedder.PREFIX)) return owner;
        String rest = owner.substring(SyntheticEmbedder.PREFIX.length());
        return "embedded:" + rest.substring(rest.indexOf('/') + 1);
    }
}
