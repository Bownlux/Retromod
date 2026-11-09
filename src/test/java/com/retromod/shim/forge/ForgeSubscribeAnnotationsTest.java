/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
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
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

/** An old {@code @SubscribeEvent(priority = HIGH)} must reach Forge 26.2's EventBus 7 as a byte priority. */
class ForgeSubscribeAnnotationsTest {

    private static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    private static final String OLD_PRIORITY = "Lnet/minecraftforge/eventbus/api/EventPriority;";
    private static final String SUBSCRIBE7 = ForgeSubscribeAnnotations.SUBSCRIBE7;
    private static final String STARTED = "Lnet/minecraftforge/event/server/ServerStartedEvent;";
    private static final String HANDLERS = "fixture/Handlers";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedTarget;
    private boolean savedNeoForge;
    private boolean savedForge;

    @BeforeEach
    void save() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        savedNeoForge = McReflect.isForceNeoForge();
        savedForge = McReflect.isForceForge();
        // RetromodForge and --target-loader forge set this before any shim registers.
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        McReflect.setForceForge(savedForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("An old enum priority becomes EventBus 7's byte on static and instance handlers, and receiveCanceled goes")
    void enumPriorityBecomesAByte(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        new Forge_26_1_to_26_2().registerRedirects(transformer);

        byte[] out = transformer.transformClass(handlers(HANDLERS), HANDLERS + ".class");
        new ClassReader(out).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        ClassNode node = node(out);

        assertEquals(List.of("priority", (byte) 64), subscribe(node, "onStart").values,
                "the static HIGH handler keeps its priority and loses receiveCanceled");
        assertEquals(List.of("priority", (byte) -127), subscribe(node, "onStop").values,
                "the instance LOWEST handler keeps its priority");
        assertNull(subscribe(node, "onPlain").values, "a bare annotation stays bare");
    }

    @Test
    @DisplayName("An annotation that already carries a byte is left as it is")
    void byteAnnotationIsUnchanged() {
        byte[] normalized = classWithByteAnnotation();

        assertSame(normalized, ForgeSubscribeAnnotations.apply(normalized));
    }

    @Test
    @DisplayName("Stand-in and relocated enum values convert by one table, and a second pass changes nothing")
    void priorityTable() {
        AnnotationNode old = new AnnotationNode(SUBSCRIBE7);
        old.values = new java.util.ArrayList<>(List.of("priority", new String[]{"L" + ForgeEventBusSynthetics.PRI + ";", "LOW"},
                "receiveCanceled", true));
        assertEquals(-64, ForgeSubscribeAnnotations.priorityOf(old));
        assertTrue(ForgeSubscribeAnnotations.normalize(old));
        assertEquals(List.of("priority", (byte) -64), old.values);
        assertEquals(-64, ForgeSubscribeAnnotations.priorityOf(old), "a normalized annotation reads the same");
        assertFalse(ForgeSubscribeAnnotations.normalize(old), "a second pass changes nothing");

        // The embedder relocates the stand-in enum into each mod's own package.
        AnnotationNode relocated = new AnnotationNode(SUBSCRIBE7);
        relocated.values = List.of("priority", new String[]{
                "Lcom/retromod/embedded/mod_1234/" + ForgeEventBusSynthetics.PRI + ";", "HIGHEST"});
        assertEquals(127, ForgeSubscribeAnnotations.priorityOf(relocated));
    }

    @Test
    @DisplayName("A NeoForge target keeps its own annotation and enum")
    void neoForgeIsUntouched() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        ForgeSubscribeAnnotations.register(transformer);

        byte[] out = transformer.transformClass(handlers(HANDLERS), HANDLERS + ".class");

        assertFalse(ForgeSubscribeAnnotations.hostUsesEventBus7());
        assertNull(annotation(method(node(out), "onStart"), SUBSCRIBE7), "nothing rewrites to EventBus 7 there");
        assertEquals(OLD_PRIORITY, ((String[]) annotation(method(node(out), "onStart"), OLD_SUBSCRIBE).values.get(1))[0]);
    }

    @Test
    @DisplayName("A Forge 1.20.1 target does not turn the class pass on")
    void oldForgeTargetIsUntouched() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        ForgeEventBusSynthetics.register(transformer);
        ForgeSubscribeAnnotations.register(transformer);

        byte[] out = transformer.transformClass(handlers(HANDLERS), HANDLERS + ".class");

        Object priority = subscribe(node(out), "onStart").values.get(1);
        assertTrue(priority instanceof String[], "EventBus 6 reads the enum, so it stays: " + priority);
    }

    @Test
    @DisplayName("The host's own classes decide over the target version when they can be read")
    void hostClassesDecide() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(withHostClasses(Set.of(ForgeHostShape.ANCHOR, "net/minecraftforge/eventbus/api/SubscribeEvent"),
                ForgeSubscribeAnnotations::hostUsesEventBus7), "an EventBus 6 host keeps the enum");

        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertTrue(withHostClasses(Set.of(ForgeHostShape.ANCHOR, "net/minecraftforge/eventbus/api/listener/SubscribeEvent"),
                ForgeSubscribeAnnotations::hostUsesEventBus7), "an EventBus 7 host gets bytes whatever the target says");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertTrue(ForgeSubscribeAnnotations.hostUsesEventBus7(), "offline, a Forge 26.2 target");

        McReflect.setForceForge(false);
        assertFalse(ForgeSubscribeAnnotations.hostUsesEventBus7(), "a target that is not Forge never takes the pass");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch and AOT paths write the same normalized handlers, outer and nested")
    void everyPathNormalizes(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("handlers.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", handlerAnnotations(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("handlers.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", handlerAnnotations(onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", handlerAnnotations(aot));

        Map<String, String> expected = new TreeMap<>(Map.of(
                "fixture/Handlers.onStart", "priority=64", "fixture/Handlers.onStop", "priority=-127",
                "fixture/Handlers.onPlain", "",
                "nested:fixturelib/LibHandlers.onStart", "priority=64", "nested:fixturelib/LibHandlers.onStop", "priority=-127",
                "nested:fixturelib/LibHandlers.onPlain", ""));
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

    /** Each EventBus 7 handler's annotation values as text, keyed by class and method, nested jar included. */
    private static Map<String, String> handlerAnnotations(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            collect(file.getInputStream(file.getJarEntry(HANDLERS + ".class")).readAllBytes(), "", found);
            byte[] nested = file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes();
            Path nestedFile = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nestedFile, nested);
                try (JarFile lib = new JarFile(nestedFile.toFile())) {
                    collect(lib.getInputStream(lib.getJarEntry("fixturelib/LibHandlers.class")).readAllBytes(),
                            "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nestedFile);
            }
        }
        return found;
    }

    private static void collect(byte[] classBytes, String prefix, Map<String, String> found) {
        ClassNode node = node(classBytes);
        for (MethodNode method : node.methods) {
            AnnotationNode subscribe = annotation(method, SUBSCRIBE7);
            if (subscribe == null) continue;
            StringBuilder text = new StringBuilder();
            List<Object> values = subscribe.values == null ? List.of() : subscribe.values;
            for (int i = 0; i + 1 < values.size(); i += 2) {
                Object value = values.get(i + 1);
                text.append(text.length() == 0 ? "" : ",").append(values.get(i)).append('=')
                        .append(value instanceof String[] constant ? Arrays.toString(constant) : value);
            }
            found.put(prefix + node.name + "." + method.name, text.toString());
        }
    }

    private static Path onlyJar(Path dir) throws Exception {
        try (var jars = Files.list(dir)) {
            List<Path> all = jars.filter(path -> path.toString().endsWith(".jar")).toList();
            assertEquals(1, all.size(), "the batch wrote one jar: " + all);
            return all.get(0);
        }
    }

    /** A Forge 1.20.1 mod with the handler class, and a nested library with the same shape. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("fixturelib"));
        lib.put("fixturelib/LibHandlers.class", handlers("fixturelib/LibHandlers"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("handlers"));
        mod.put(HANDLERS + ".class", handlers(HANDLERS));
        mod.put("META-INF/jarjar/lib.jar", jar(lib));
        return jar(mod);
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

    /**
     * A Forge 1.20.1 handler class: a static {@code onStart} with {@code priority = HIGH, receiveCanceled = true},
     * an instance {@code onStop} with {@code priority = LOWEST}, and a bare {@code onPlain}.
     */
    private static byte[] handlers(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        handler(cw, "onStart", ACC_PUBLIC | ACC_STATIC, subscribe -> {
            subscribe.visitEnum("priority", OLD_PRIORITY, "HIGH");
            subscribe.visit("receiveCanceled", true);
        });
        handler(cw, "onStop", ACC_PUBLIC, subscribe -> subscribe.visitEnum("priority", OLD_PRIORITY, "LOWEST"));
        handler(cw, "onPlain", ACC_PUBLIC, subscribe -> {});
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void handler(ClassWriter cw, String name, int access, java.util.function.Consumer<AnnotationVisitor> values) {
        MethodVisitor mv = cw.visitMethod(access, name, "(" + STARTED + ")V", null, null);
        AnnotationVisitor subscribe = mv.visitAnnotation(OLD_SUBSCRIBE, true);
        values.accept(subscribe);
        subscribe.visitEnd();
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** A class the bridge already normalized, which still names EventPriority in a string. */
    private static byte[] classWithByteAnnotation() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, HANDLERS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onStart", "(" + STARTED + ")V", null, null);
        AnnotationVisitor subscribe = mv.visitAnnotation(SUBSCRIBE7, true);
        subscribe.visit("priority", (byte) 64);
        subscribe.visitEnd();
        mv.visitCode();
        mv.visitLdcInsn("EventPriority");
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Runs the check with the named classes readable through the context class loader, as a host's would be. */
    private static boolean withHostClasses(Set<String> names, java.util.function.BooleanSupplier check) {
        ClassLoader host = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String resource) {
                String name = resource.endsWith(".class") ? resource.substring(0, resource.length() - 6) : resource;
                if (!names.contains(name)) return null;
                ClassWriter cw = new ClassWriter(0);
                cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT | ACC_ANNOTATION, name, null, "java/lang/Object",
                        new String[]{"java/lang/annotation/Annotation"});
                cw.visitEnd();
                return new ByteArrayInputStream(cw.toByteArray());
            }
        };
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        thread.setContextClassLoader(host);
        try {
            return check.getAsBoolean();
        } finally {
            thread.setContextClassLoader(saved);
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    private static AnnotationNode subscribe(ClassNode node, String method) {
        AnnotationNode subscribe = annotation(method(node, method), SUBSCRIBE7);
        assertNotNull(subscribe, method + " carries EventBus 7's annotation");
        return subscribe;
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        List<AnnotationNode> annotations = method.visibleAnnotations == null ? List.of() : method.visibleAnnotations;
        return annotations.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }
}
