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
import com.retromod.shim.forge.ForgeNeoForgeSynthetics;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

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

/** Every class-transform path gives a Forge 1.20.1 mod the startup shapes NeoForge needs, in the mod and its nested library. */
class NeoForgeStartupPathParityTest {

    private static final String FORGE_CONTEXT = "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext";
    private static final String FORGE_BUS = "net/minecraftforge/eventbus/api/IEventBus";
    private static final String FORGE_ADVANCEMENT = "net/minecraftforge/event/entity/player/AdvancementEvent";
    private static final String FORGE_PLAYER_TICK = "net/minecraftforge/event/TickEvent$PlayerTickEvent";
    private static final String FORGE_SPEC = "net/minecraftforge/common/ForgeConfigSpec";
    private static final String NIGHT_CONFIG = "com/electronwill/nightconfig/core/CommentedConfig";
    private static final String NEO_ADVANCEMENT = "net/neoforged/neoforge/event/entity/player/AdvancementEvent";

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

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({"runtime, 1.21.1", "runtime, 26.3", "transform, 1.21.1", "transform, 26.3", "batch, 1.21.1",
            "batch, 26.3", "aot, 1.21.1", "aot, 26.3"})
    @DisplayName("#392 #394 #398 #400: each path rewrites the @Mod constructor, parent listeners, versions and configs")
    void everyPathPreparesTheModForNeoForge(String path, String host) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("backrooms.jar");
        Files.write(input, modJar());

        Path output = translate(path, host, input);

        for (Map.Entry<String, Map<String, ClassNode>> jar : classes(output).entrySet()) {
            String where = jar.getKey() + " (" + path + " on " + host + ")";
            Map<String, ClassNode> classes = jar.getValue();
            assertModConstructor(classes.get("ContextMod"), where);
            assertListeners(classes.get("Listeners"), where);
            if (jar.getKey().equals("mod")) {
                assertEmbedded(classes, "net/minecraftforge/versions/forge/ForgeVersion", where);
                assertEmbedded(classes, "LegacyConfigSpecs", where);
                assertEmbedded(classes, "net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext", where);
            }
        }
    }

    private static void assertModConstructor(ClassNode mod, String where) {
        MethodNode constructor = method(mod, "<init>");
        assertEquals("(Lnet/neoforged/fml/ModContainer;)V", constructor.desc,
                "NeoForge hands a @Mod constructor its container, not a context: " + where);
        assertTrue(calls(constructor, "net/minecraftforge/versions/forge/ForgeVersion", "getVersion"),
                "the version call goes to the embedded ForgeVersion: " + where);
    }

    private static void assertListeners(ClassNode listeners, String where) {
        MethodNode original = method(listeners, "onAdvancement");
        assertFalse(subscribed(original), "the abstract listener must not reach NeoForge: " + where);
        long children = listeners.methods.stream()
                .filter(m -> subscribed(m) && m.desc.startsWith("(L" + NEO_ADVANCEMENT + "$")).count();
        assertEquals(2, children, "one listener per AdvancementEvent child: " + where);
        assertTrue(calls(method(listeners, "onTick"), "TickEventSide", "side"), "the side read moves: " + where);
        for (var insn : method(listeners, "onTick").instructions.toArray()) {
            assertFalse(insn instanceof FieldInsnNode field && field.name.equals("side"), "no side field: " + where);
        }
        MethodNode config = method(listeners, "loadConfig");
        assertTrue(calls(config, "LegacyConfigSpecs", "setConfig"), "setConfig moves to the rebuilt helper: " + where);
        assertTrue(calls(config, "net/neoforged/fml/loading/FMLConfig", "defaultConfigPath"),
                "FMLConfig is NeoForge's: " + where);
    }

    private static void assertEmbedded(Map<String, ClassNode> classes, String suffix, String where) {
        assertTrue(classes.keySet().stream().anyMatch(name -> name.startsWith("com/retromod/") && name.endsWith(suffix)),
                suffix + " must travel with the mod: " + where + " " + classes.keySet());
    }

    private Path translate(String path, String host, Path input) throws Exception {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> runtime(host, input, out);
            case "transform" -> {
                CliHarness.transform(input, out.resolve("backrooms.jar"), host);
                yield out.resolve("backrooms.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("backrooms.jar"), host);
                yield out.resolve("backrooms.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    List<Path> all = jars.filter(jar -> jar.toString().endsWith(".jar")).toList();
                    assertEquals(1, all.size(), "the batch wrote one jar: " + all);
                    yield all.get(0);
                }
            }
        };
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what the NeoForge entry point registers on {@code host}, then runs its mod transformer. */
    private Path runtime(String host, Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Set<String> types = Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        ForgeNeoForgeSynthetics.register(transformer);
        return new ForgeModTransformer(host).transformMod(input, out);
    }

    /** The classes of the mod and of its nested library, by simple name, plus every class of the mod by its name. */
    private static Map<String, Map<String, ClassNode>> classes(Path output) throws Exception {
        Map<String, Map<String, ClassNode>> found = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod", read(jar, "mod/"));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                JarEntry lib = jar.getJarEntry("META-INF/jarjar/lib.jar");
                assertNotNull(lib, "the nested library is missing from " + output);
                Files.write(nested, jar.getInputStream(lib).readAllBytes());
                try (JarFile library = new JarFile(nested.toFile())) {
                    found.put("nested", read(library, "lib/"));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static Map<String, ClassNode> read(JarFile jar, String ownPackage) throws Exception {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        for (JarEntry entry : java.util.Collections.list(jar.entries())) {
            if (!entry.getName().endsWith(".class")) continue;
            ClassNode node = new ClassNode();
            new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
            String name = entry.getName().substring(0, entry.getName().length() - ".class".length());
            classes.put(name.startsWith(ownPackage) ? name.substring(ownPackage.length()) : name, node);
        }
        return classes;
    }

    private static MethodNode method(ClassNode node, String name) {
        assertNotNull(node, "class missing");
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static boolean subscribed(MethodNode method) {
        if (method.visibleAnnotations == null) return false;
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (annotation.desc.equals("Lnet/neoforged/bus/api/SubscribeEvent;")) return true;
        }
        return false;
    }

    private static boolean calls(MethodNode method, String ownerSuffix, String name) {
        for (var insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.endsWith(ownerSuffix) && call.name.equals(name)) return true;
        }
        return false;
    }

    /** A Forge 1.20.1 mod shaped like Meulti's Backrooms and Villager Recruits, with a nested library doing the same. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", modsToml("backroomslib"));
        lib.put("lib/ContextMod.class", contextMod("lib/ContextMod", "backroomslib"));
        lib.put("lib/Listeners.class", listeners("lib/Listeners"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", modsToml("mbackrooms"));
        mod.put("mod/ContextMod.class", contextMod("mod/ContextMod", "mbackrooms"));
        mod.put("mod/Listeners.class", listeners("mod/Listeners"));
        mod.put("META-INF/jarjar/lib.jar", LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    /** {@code @Mod} whose constructor takes the context, reads its bus and logs Forge's version. */
    private static byte[] contextMod(String name, String modId) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        AnnotationVisitor mod = cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        mod.visit("value", modId);
        mod.visitEnd();
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + FORGE_CONTEXT + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE_CONTEXT, "getModEventBus", "()L" + FORGE_BUS + ";", false);
        m.visitInsn(Opcodes.POP);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraftforge/versions/forge/ForgeVersion", "getVersion",
                "()Ljava/lang/String;", false);
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A parent listener, a tick listener reading its side, and a config loader, as Bloodbathen, Backrooms and Recruits have. */
    private static byte[] listeners(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        AnnotationVisitor subscriber = cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;", true);
        subscriber.visitEnd();
        MethodVisitor advancement = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onAdvancement",
                "(L" + FORGE_ADVANCEMENT + ";)V", null, null);
        advancement.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true).visitEnd();
        advancement.visitCode();
        advancement.visitInsn(Opcodes.RETURN);
        advancement.visitMaxs(0, 0);
        advancement.visitEnd();
        MethodVisitor tick = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onTick",
                "(L" + FORGE_PLAYER_TICK + ";)V", null, null);
        tick.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true).visitEnd();
        tick.visitCode();
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitFieldInsn(Opcodes.GETFIELD, FORGE_PLAYER_TICK, "side", "Lnet/minecraftforge/fml/LogicalSide;");
        tick.visitInsn(Opcodes.POP);
        tick.visitInsn(Opcodes.RETURN);
        tick.visitMaxs(0, 0);
        tick.visitEnd();
        MethodVisitor config = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "loadConfig",
                "(L" + FORGE_SPEC + ";L" + NIGHT_CONFIG + ";)Ljava/lang/String;", null, null);
        config.visitCode();
        config.visitVarInsn(Opcodes.ALOAD, 0);
        config.visitVarInsn(Opcodes.ALOAD, 1);
        config.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE_SPEC, "setConfig", "(L" + NIGHT_CONFIG + ";)V", false);
        config.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraftforge/fml/loading/FMLConfig", "defaultConfigPath",
                "()Ljava/lang/String;", false);
        config.visitInsn(Opcodes.ARETURN);
        config.visitMaxs(0, 0);
        config.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] modsToml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n"
                + "mandatory=true\nversionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
