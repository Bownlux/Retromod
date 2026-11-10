/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Every class-transform path splits a base TickEvent listener into ten and reaches the rebuilt type and unregister helpers. */
class LegacyTickPathParityTest {

    private static final String TICK = LegacyTickEventBridge.TICK;
    private static final String BUS = "net/minecraftforge/eventbus/api/IEventBus";
    private static final String SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch and AOT paths split a spark-style tick hook the same way, outer and nested")
    void everyPathSplitsTheBaseListener(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("ticks.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("ticks.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        Map<String, String> expected = new TreeMap<>(Map.of(
                "mod/TickHook listeners", "10 EventBus 7 listeners",
                "mod/TickHook.onTick type", "embedded com/retromod/legacyapi/forge/event/TickEvents.type"
                        + "(Ljava/lang/Object;)Lcom/retromod/legacyapi/forge/event/TickType;",
                "mod/TickHook.close", "unregister(Ljava/lang/Object;)V, listener sets embedded",
                "nested:fixturelib/LibTicks listeners", "10 EventBus 7 listeners"));
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    /** What RetromodForge.loadForgeShims registers on the host, then one runtime transform. */
    private Path forgeRuntime(Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        Path out = new ForgeModTransformer(host).transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        assertNotNull(out, "the Forge runtime wrote a jar");
        return out;
    }

    private static Map<String, String> summary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            ClassNode hook = node(read(file, "mod/TickHook"));
            found.put("mod/TickHook listeners", listeners(hook));
            found.put("mod/TickHook.onTick type", typeRead(file, method(hook, "onTick")));
            // The unregister call reaches the bus bridge, whose listener sets travel in the same jar.
            boolean sets = file.stream().anyMatch(entry -> entry.getName().startsWith(SyntheticEmbedder.PREFIX)
                    && entry.getName().endsWith("com/retromod/legacyapi/forge/eventbus/LegacyListenerSets.class"));
            found.put("mod/TickHook.close", call(method(hook, "close"), "unregister") + (sets ? ", listener sets embedded" : ""));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested:fixturelib/LibTicks listeners", listeners(node(read(lib, "fixturelib/LibTicks"))));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static String listeners(ClassNode node) {
        long count = node.methods.stream().filter(m -> m.visibleAnnotations != null
                && m.visibleAnnotations.stream().map(a -> a.desc).anyMatch(SUBSCRIBE::equals)).count();
        return count + " EventBus 7 listeners";
    }

    // The helper the type read goes to, marked when its class is embedded in the jar.
    private static String typeRead(JarFile jar, MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("type")) {
                boolean embedded = call.owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(call.owner + ".class") != null;
                String rebuilt = call.owner.substring(Math.max(call.owner.indexOf("com/retromod/legacyapi/"), 0));
                return (embedded ? "embedded " : "") + rebuilt + ".type" + call.desc.replaceAll("L[^;]*com/retromod/legacyapi/", "Lcom/retromod/legacyapi/");
            }
        }
        return "no type call";
    }

    private static String call(MethodNode method, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals(name)) return name + call.desc;
        }
        return "no " + name + " call";
    }

    private static byte[] read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return jar.getInputStream(entry).readAllBytes();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static Path onlyJar(Path dir) throws Exception {
        try (var jars = Files.list(dir)) {
            List<Path> all = jars.filter(path -> path.toString().endsWith(".jar")).toList();
            assertEquals(1, all.size(), "the batch wrote one jar: " + all);
            return all.get(0);
        }
    }

    /** A Forge 1.20.1 mod with spark's tick hook, and a nested library with a base TickEvent listener of its own. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("fixturelib"));
        lib.put("fixturelib/LibTicks.class", tickHook("fixturelib/LibTicks"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("tickmod"));
        mod.put("mod/TickHook.class", tickHook("mod/TickHook"));
        mod.put("META-INF/jarjar/lib.jar", jar(lib));
        return jar(mod);
    }

    /**
     * spark's {@code ForgeTickHook}: a base TickEvent listener that reads the phase and type, registered by
     * {@code start()} and unregistered by {@code close()} on {@code MinecraftForge.EVENT_BUS}.
     */
    private static byte[] tickHook(String name) {
        String type = "L" + TICK + "$Type;";
        String phase = "L" + TICK + "$Phase;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "type", type, null, null).visitEnd();
        MethodVisitor tick = cw.visitMethod(ACC_PUBLIC, "onTick", "(L" + TICK + ";)V", null, null);
        tick.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true).visitEnd();
        tick.visitCode();
        Label done = new Label();
        tick.visitVarInsn(ALOAD, 1);
        tick.visitFieldInsn(GETFIELD, TICK, "phase", phase);
        tick.visitFieldInsn(GETSTATIC, TICK + "$Phase", "START", phase);
        tick.visitJumpInsn(IF_ACMPNE, done);
        tick.visitVarInsn(ALOAD, 1);
        tick.visitFieldInsn(GETFIELD, TICK, "type", type);
        tick.visitVarInsn(ALOAD, 0);
        tick.visitFieldInsn(GETFIELD, name, "type", type);
        tick.visitJumpInsn(IF_ACMPNE, done);
        tick.visitInsn(NOP);
        tick.visitLabel(done);
        tick.visitInsn(RETURN);
        tick.visitMaxs(0, 0);
        tick.visitEnd();
        for (String[] busCall : new String[][]{{"start", "register"}, {"close", "unregister"}}) {
            MethodVisitor m = cw.visitMethod(ACC_PUBLIC, busCall[0], "()V", null, null);
            m.visitCode();
            m.visitFieldInsn(GETSTATIC, "net/minecraftforge/common/MinecraftForge", "EVENT_BUS", "L" + BUS + ";");
            m.visitVarInsn(ALOAD, 0);
            m.visitMethodInsn(INVOKEINTERFACE, BUS, busCall[1], "(Ljava/lang/Object;)V", true);
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] toml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
