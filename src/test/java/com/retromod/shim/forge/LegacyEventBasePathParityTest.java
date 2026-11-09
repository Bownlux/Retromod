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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
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

/** Every class-transform path moves old events onto the rebuilt base and embeds the base beside them. */
class LegacyEventBasePathParityTest {

    private static final String EVENT = LegacyEventBaseAdapter.EVENT;
    private static final String CANCELABLE = "Lnet/minecraftforge/eventbus/api/Cancelable;";

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
    @DisplayName("The Forge runtime, CLI batch and AOT paths rebase old events and move their handlers the same way, outer and nested")
    void everyPathRebases(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("events.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("events.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        Map<String, String> expected = new TreeMap<>(Map.of(
                "mod/MyEvent", "extends embedded com/retromod/legacyapi/forge/eventbus/LegacyEvent, isCancelable",
                "mod/Poster.postAny", "(Ljava/lang/Object;)Z, dispatcher embedded",
                "mod/Handlers.onOwn", "embedded com/retromod/legacyapi/forge/eventbus/LegacySubscribe [priority, HIGH, receiveCanceled, true]",
                "nested:fixturelib/LibEvent",
                "extends embedded com/retromod/legacyapi/forge/eventbus/LegacyGenericEvent"));
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

    /** Each event's superclass, which must be embedded in the same jar, and the poster's descriptor. */
    private static Map<String, String> summary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, "mod/MyEvent", "", found);
            ClassNode poster = node(read(file, "mod/Poster"));
            // The bus bridge the poster names reaches Retromod's dispatcher, which travels in the same jar.
            boolean dispatcher = file.stream().anyMatch(entry -> entry.getName().startsWith(SyntheticEmbedder.PREFIX)
                    && entry.getName().endsWith("com/retromod/legacyapi/forge/eventbus/LegacyEvents.class"));
            found.put("mod/Poster.postAny", postDescriptor(method(poster, "postAny")) + (dispatcher ? ", dispatcher embedded" : ""));
            found.put("mod/Handlers.onOwn", subscription(file, method(node(read(file, "mod/Handlers")), "onOwn")));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/LibEvent", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String name, String prefix, Map<String, String> found) throws Exception {
        ClassNode event = node(read(jar, name));
        String base = event.superName;
        boolean embedded = base.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(base + ".class") != null;
        String rebuilt = base.substring(base.indexOf("com/retromod/legacyapi/") < 0 ? 0 : base.indexOf("com/retromod/legacyapi/"));
        boolean cancelable = event.methods.stream().anyMatch(m -> m.name.equals("isCancelable"));
        found.put(prefix + name, "extends " + (embedded ? "embedded " : "") + rebuilt + (cancelable ? ", isCancelable" : ""));
    }

    // The handler's one annotation, with its type's embedded copy and its values.
    private static String subscription(JarFile jar, MethodNode handler) {
        AnnotationNode annotation = handler.visibleAnnotations.get(0);
        String type = annotation.desc.substring(1, annotation.desc.length() - 1);
        boolean embedded = type.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(type + ".class") != null;
        String rebuilt = type.substring(Math.max(type.indexOf("com/retromod/legacyapi/"), 0));
        return (embedded ? "embedded " : "") + rebuilt + " " + annotation.values;
    }

    private static String postDescriptor(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("post")) return call.desc;
        }
        return "no post call";
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

    /** A Forge 1.20.1 mod with a @Cancelable event and an Event-typed poster, and a nested library with a GenericEvent. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("fixturelib"));
        lib.put("fixturelib/LibEvent.class", LegacyEventBaseAdapterTest.eventClass("fixturelib/LibEvent",
                LegacyEventBaseAdapter.GENERIC_EVENT, null));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("eventmod"));
        mod.put("mod/MyEvent.class", LegacyEventBaseAdapterTest.annotated("mod/MyEvent", CANCELABLE, false));
        mod.put("mod/Poster.class", poster());
        mod.put("mod/Handlers.class", LegacyEventFixtures.listener("mod/Handlers",
                LegacyEventFixtures.Handler.of("own", "mod/MyEvent", "HIGH").receivingCanceled()));
        mod.put("META-INF/jarjar/lib.jar", jar(lib));
        return jar(mod);
    }

    /** {@code static boolean postAny(Event e) { return MinecraftForge.EVENT_BUS.post(e); }}. */
    private static byte[] poster() {
        String bus = "net/minecraftforge/eventbus/api/IEventBus";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Poster", null, "java/lang/Object", null);
        MethodVisitor post = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "postAny", "(L" + EVENT + ";)Z", null, null);
        post.visitCode();
        post.visitFieldInsn(GETSTATIC, "net/minecraftforge/common/MinecraftForge", "EVENT_BUS", "L" + bus + ";");
        post.visitVarInsn(ALOAD, 0);
        post.visitMethodInsn(INVOKEINTERFACE, bus, "post", "(L" + EVENT + ";)Z", true);
        post.visitInsn(IRETURN);
        post.visitMaxs(0, 0);
        post.visitEnd();
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
