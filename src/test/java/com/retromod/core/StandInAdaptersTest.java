/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** An addon hands one stand-in to two libraries that each took their own copy, and both calls work. */
class StandInAdaptersTest {

    private static final String FIXTURE = "com/retromod/core/adapterfixture/";
    private static final String TIER = "com/retromod/generated/fixture/LegacyTierFixture";
    private static final String VIEW = "com/retromod/generated/fixture/LegacyTierFixtureView";
    /** Where each fixture class goes: the stand-in and its view, two libraries, and the addon. */
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry(FIXTURE + "TierFixture", TIER),
            Map.entry(FIXTURE + "TierFixtureView", VIEW),
            Map.entry(FIXTURE + "LibraryApi", "first/LibraryApi"),
            Map.entry(FIXTURE + "SecondApi", "second/SecondApi"),
            Map.entry(FIXTURE + "SecondBase", "second/SecondBase"),
            Map.entry(FIXTURE + "SecondRenderer", "second/SecondRenderer"),
            Map.entry(FIXTURE + "AddonTier", "addon/AddonTier"),
            Map.entry(FIXTURE + "AddonCalls", "addon/AddonCalls"),
            Map.entry(FIXTURE + "AddonSub", "addon/AddonSub"),
            Map.entry(FIXTURE + "AddonRenderer", "addon/AddonRenderer"),
            Map.entry(FIXTURE + "ValueTier", "addon/ValueTier"));
    private static final List<String> SECOND_LIBRARY = List.of("SecondApi", "SecondBase", "SecondRenderer");
    private static final List<String> ADDON =
            List.of("AddonTier", "AddonCalls", "AddonSub", "AddonRenderer", "ValueTier");

    private static final String ANNOTATED_MIXIN = "addon/mixin/SubMixin";
    private static final String LISTED_MIXIN = "addon/listed/ListedSub";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @TempDir
    Path dir;

    @BeforeEach
    void registerStandIn() throws IOException {
        transformer.clearRedirectsForTesting();
        transformer.registerSyntheticClass(TIER, fixture("TierFixture"));
        transformer.registerSyntheticClass(VIEW, fixture("TierFixtureView"));
    }

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"embed", "forge", "fabric"})
    @DisplayName("the addon's calls into both libraries run, with their own constants and the same objects coming back")
    void callsIntoBothLibrariesRun(String path) throws Exception {
        SyntheticLibraries libraries = SyntheticLibraries.create();
        Path first = translate(path, library("first", "LibraryApi"), "first", libraries);
        libraries.add(first);
        Path second = translate(path, library("second", SECOND_LIBRARY), "second", libraries);
        libraries.add(second);
        Path addon = translate(path, addon(), "addon", libraries);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{url(first), url(second), url(addon)},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> calls = loader.loadClass("addon.AddonCalls");
            for (boolean secondLibrary : new boolean[]{false, true}) {
                String which = secondLibrary ? "the second library" : "the first library";
                assertEquals(7, call(calls, "ownUses", secondLibrary), which + " reads the addon's own tier");
                assertEquals(true, call(calls, "ironIsIron", secondLibrary),
                        which + " sees the old IRON constant as its own IRON");
                assertEquals(true, call(calls, "ownComesBack", secondLibrary),
                        "an addon tier that goes to " + which + " and back is the same object");
                assertEquals(true, call(calls, "ironComesBack", secondLibrary),
                        "IRON that goes to " + which + " and back is the addon's IRON");
                assertEquals(32, call(calls, "standardUses", secondLibrary), which + "'s tier field reads back");
                assertEquals(40, call(calls, "both", secondLibrary), which + " takes two tiers around an int");
            }
            assertEquals(251, calls.getMethod("overrideRuns").invoke(null),
                    "the second library calls the addon's override with IRON");
            assertEquals(7, calls.getMethod("implementationRuns").invoke(null),
                    "the second library calls the addon's implementation and reads the tier it returns");
            assertEquals(true, calls.getMethod("equalTiersStayApart").invoke(null),
                    "two equal tiers each come back as themselves");
        }
        String links = new String(entries(addon).get(SyntheticLibraries.LINKS_ENTRY), StandardCharsets.UTF_8);
        assertTrue(links.contains(SyntheticLibraries.CROSSES + baseOf(second) + TIER),
                "the addon lists the second library's copy it converts to: " + links);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"embed", "forge", "fabric"})
    @DisplayName("a mixin class gets no bridge, but its calls into the second library are still converted")
    void mixinClassesGetNoBridges(String path) throws Exception {
        SyntheticLibraries libraries = SyntheticLibraries.create();
        Path first = translate(path, library("first", "LibraryApi"), "first", libraries);
        libraries.add(first);
        Path second = translate(path, library("second", SECOND_LIBRARY), "second", libraries);
        libraries.add(second);
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (String name : ADDON) classes.put("addon/" + name, fixture(name));
        classes.put(ANNOTATED_MIXIN, secondBaseMixin(ANNOTATED_MIXIN, true));
        classes.put(LISTED_MIXIN, secondBaseMixin(LISTED_MIXIN, false));
        Map<String, String> config = Map.of("addon.mixins.json",
                "{\"package\": \"addon.listed\", \"mixins\": [\"ListedSub\"]}");
        Path addon = translate(path, jar(dir.resolve("addon-source.jar"), classes, config), "addon", libraries);

        String secondTier = baseOf(second) + TIER;
        for (String mixin : List.of(ANNOTATED_MIXIN, LISTED_MIXIN)) {
            ClassNode node = classNode(addon, mixin);
            assertEquals(1, node.methods.stream().filter(m -> m.name.equals("score")).count(),
                    mixin + " gets no bridge, since Mixin would merge it into its target");
            assertTrue(adaptsBeforeCall(node, "second/SecondApi", secondTier),
                    mixin + " still converts the tier it hands to the second library");
        }
        assertTrue(classNode(addon, "addon/AddonSub").methods.stream()
                        .anyMatch(m -> m.name.equals("score") && m.desc.contains(secondTier)),
                "an ordinary subclass still gets its bridge");
    }

    /** {@code abstract class name extends SecondBase}, overriding score and calling SecondApi.uses. */
    private static byte[] secondBaseMixin(String name, boolean annotated) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, name, null, "second/SecondBase", null);
        if (annotated) {
            AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
            AnnotationVisitor targets = mixin.visitArray("value");
            targets.visit(null, Type.getObjectType("second/SecondBase"));
            targets.visitEnd();
            mixin.visitEnd();
        }
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "second/SecondBase", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor score = cw.visitMethod(ACC_PUBLIC, "score", "(L" + TIER + ";)I", null, null);
        score.visitCode();
        score.visitInsn(ICONST_0);
        score.visitInsn(IRETURN);
        score.visitMaxs(0, 0);
        score.visitEnd();
        MethodVisitor calls = cw.visitMethod(ACC_PUBLIC, "callsSecond", "(L" + TIER + ";)I", null, null);
        calls.visitCode();
        calls.visitVarInsn(ALOAD, 1);
        calls.visitMethodInsn(INVOKESTATIC, "second/SecondApi", "uses", "(L" + TIER + ";)I", false);
        calls.visitInsn(IRETURN);
        calls.visitMaxs(0, 0);
        calls.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Whether a call into {@code owner} takes {@code copy} and is fed by an adapter. */
    private static boolean adaptsBeforeCall(ClassNode node, String owner, String copy) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                        && call.desc.contains("L" + copy + ";")
                        && call.getPrevious() instanceof MethodInsnNode adapt && adapt.name.equals("adapt")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ClassNode classNode(Path jar, String name) throws IOException {
        byte[] bytes = entries(jar).get(name + ".class");
        assertNotNull(bytes, name + " is in " + jar.getFileName());
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Translates a fixture jar through one path: the jar embed the CLI and AOT use, or a loader's mod transformer. */
    private Path translate(String path, Path source, String name, SyntheticLibraries libraries) throws IOException {
        return switch (path) {
            case "embed" -> embedded(source, name + ".jar", libraries);
            case "forge" -> new ForgeModTransformer("26.1").transformMod(withMetadata(source, name, false),
                    Files.createDirectories(dir.resolve("forge-" + name)), libraries);
            default -> new FabricModTransformer("26.1").transformMod(withMetadata(source, name, true),
                    Files.createDirectories(dir.resolve("fabric-" + name)), libraries);
        };
    }

    /** The fixture jar as a 1.20.1 Forge or Fabric mod. */
    private Path withMetadata(Path source, String modId, boolean fabric) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        if (fabric) {
            String json = "{\"schemaVersion\":1,\"id\":\"" + modId
                    + "\",\"version\":\"1.0\",\"depends\":{\"minecraft\":\"1.20.1\"}}";
            entries.put("fabric.mod.json", json.getBytes(StandardCharsets.UTF_8));
        } else {
            entries.put("META-INF/mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                    + "[[mods]]\nmodId=\"" + modId + "\"\nversion=\"1.0\"\n[[dependencies." + modId
                    + "]]\nmodId=\"minecraft\"\nversionRange=\"[1.20.1]\"\n").getBytes(StandardCharsets.UTF_8));
        }
        entries.putAll(entries(source));
        Path jar = dir.resolve(modId + (fabric ? "-fabric" : "-1.20.1") + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    /** The embedded package a translated library keeps its own tier copy in. */
    private static String baseOf(Path jar) throws IOException {
        for (String name : entries(jar).keySet()) {
            if (name.startsWith(SyntheticEmbedder.PREFIX) && name.endsWith("/" + TIER + ".class")) {
                return name.substring(0, name.length() - (TIER + ".class").length());
            }
        }
        return "(no copy)";
    }

    @Test
    @DisplayName("an addon of one library gets no adapters, and its link file is as before")
    void oneLibraryNeedsNoAdapters() throws Exception {
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(embedded(library("first", "LibraryApi"), "first.jar", libraries));
        Path addon = embedded(addon(), "addon.jar", libraries);

        Map<String, byte[]> entries = entries(addon);
        assertTrue(entries.keySet().stream().noneMatch(name -> name.contains("retromod/adapt/")));
        String firstBase = SyntheticEmbedder.embeddedBase("first.jar");
        assertEquals(firstBase + TIER + "\n" + firstBase + VIEW + "\n",
                new String(entries.get(SyntheticLibraries.LINKS_ENTRY), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an addon goes stale when a library it hands a tier to arrives later, or its copy leaves")
    void adaptersFollowTheLibraries() throws Exception {
        Path source = addon();
        SyntheticLibraries firstOnly = SyntheticLibraries.create();
        Path first = embedded(library("first", "LibraryApi"), "first.jar", firstOnly);
        firstOnly.add(first);
        Path early = embedded(source, "addon.jar", firstOnly);
        SyntheticLibraries both = SyntheticLibraries.create();
        both.add(first);
        both.add(embedded(library("second", SECOND_LIBRARY), "second.jar", both));

        assertTrue(both.isStale(early), "the second library arrived after the addon was translated");
        Path again = embedded(source, "addon.jar", both);
        assertFalse(both.isStale(again), "the addon translated with both libraries is current");
        assertTrue(firstOnly.isStale(again), "the second library's copy the adapters name is gone");
    }

    @Test
    @DisplayName("tiers the addon hands to a library and drops are released, and threads share one adapter per tier")
    void adaptersFollowTheValuesTheyHold() throws Exception {
        Path[] jars = firstSecondAndAddon();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{url(jars[0]), url(jars[1]), url(jars[2])},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> calls = loader.loadClass("addon.AddonCalls");
            Method send = calls.getMethod("sendAndForget", int.class);
            for (int i = 0; i < 20_000; i++) send.invoke(null, i);
            int kept = Integer.MAX_VALUE;
            for (int attempt = 0; attempt < 20 && kept > 100; attempt++) {
                System.gc();
                Thread.sleep(20);
                kept = cacheSize(loader, jars[2]);
            }
            assertTrue(kept <= 100, "a dropped tier and its adapter must not stay cached: " + kept + " kept");

            Method seen = calls.getMethod("ownSeenBySecond");
            java.util.Set<Object> identities = java.util.concurrent.ConcurrentHashMap.newKeySet();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                List<java.util.concurrent.Future<?>> runs = new java.util.ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    runs.add(pool.submit(() -> {
                        for (int n = 0; n < 500; n++) identities.add(seen.invoke(null));
                        return null;
                    }));
                }
                for (var run : runs) run.get();
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, identities.size(), "every thread hands the library the same adapter for one tier");
        }
    }

    @Test
    @DisplayName("an addon handing a class stand-in to two libraries is current right after translation")
    void classStandInsDoNotKeepTheAddonStale() throws Exception {
        String box = "com/retromod/generated/fixture/LegacyBox";
        ClassWriter boxClass = new ClassWriter(0);
        boxClass.visit(V17, ACC_PUBLIC, box, null,
                "java/lang/Object", null);
        boxClass.visitEnd();
        transformer.registerSyntheticClass(box, boxClass.toByteArray());
        SyntheticLibraries libraries = SyntheticLibraries.create();
        Path alpha = embedded(jar(dir.resolve("alpha-source.jar"), Map.of("alpha/Api", api("alpha/Api", box))),
                "alpha.jar", libraries);
        libraries.add(alpha);
        Path beta = embedded(jar(dir.resolve("beta-source.jar"), Map.of("beta/Api", api("beta/Api", box))),
                "beta.jar", libraries);
        libraries.add(beta);
        Map<String, byte[]> caller = Map.of("boxaddon/Uses", caller(box));
        Path addon = embedded(jar(dir.resolve("boxaddon-source.jar"), caller), "boxaddon.jar", libraries);

        assertFalse(libraries.isStale(addon), "nothing has changed since the addon was translated");
        assertTrue(entries(addon).keySet().stream().noneMatch(name -> name.contains("retromod/adapt/")),
                "a class stand-in gets no adapters");
    }

    /** The first library, the second, and the addon, embedded in that order. */
    private Path[] firstSecondAndAddon() throws IOException {
        SyntheticLibraries libraries = SyntheticLibraries.create();
        Path first = embedded(library("first", "LibraryApi"), "first.jar", libraries);
        libraries.add(first);
        Path second = embedded(library("second", SECOND_LIBRARY), "second.jar", libraries);
        libraries.add(second);
        return new Path[]{first, second, embedded(addon(), "addon.jar", libraries)};
    }

    /** How many values the addon's adapters still keep an entry for. */
    private static int cacheSize(URLClassLoader loader, Path addon) throws Exception {
        int size = 0;
        for (String name : entries(addon).keySet()) {
            if (!name.contains("retromod/adapt/") || !name.contains("$")) continue;
            Class<?> adapter = loader.loadClass(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
            java.lang.reflect.Field cache = adapter.getDeclaredField("cache");
            cache.setAccessible(true);
            Object kept = cache.get(null);
            size += (int) kept.getClass().getMethod("size").invoke(kept);
        }
        return size;
    }

    /** {@code public class name { public static void use(T value) {} }} */
    private static byte[] api(String name, String type) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor use = cw.visitMethod(
                ACC_PUBLIC | ACC_STATIC, "use", "(L" + type + ";)V",
                null, null);
        use.visitCode();
        use.visitInsn(RETURN);
        use.visitMaxs(0, 0);
        use.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Hands null typed as {@code type} to both box libraries. */
    private static byte[] caller(String type) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "boxaddon/Uses", null,
                "java/lang/Object", null);
        MethodVisitor run = cw.visitMethod(
                ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        run.visitCode();
        for (String api : List.of("alpha/Api", "beta/Api")) {
            run.visitInsn(ACONST_NULL);
            run.visitMethodInsn(INVOKESTATIC, api, "use", "(L" + type + ";)V", false);
        }
        run.visitInsn(RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private Object call(Class<?> calls, String name, boolean second) throws Exception {
        Method method = calls.getMethod(name, boolean.class);
        return method.invoke(null, second);
    }

    /** A library jar holding one copy of the fixture API under its own package. */
    private Path library(String pkg, String api) throws IOException {
        return library(pkg, List.of(api));
    }

    private Path library(String pkg, List<String> fixtures) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (String name : fixtures) classes.put(pkg + "/" + name, fixture(name));
        return jar(dir.resolve(pkg + "-source.jar"), classes);
    }

    private Path addon() throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (String name : ADDON) classes.put("addon/" + name, fixture(name));
        return jar(dir.resolve("addon-source.jar"), classes);
    }

    /** A copy of {@code source} embedded against {@code libraries}, as the offline paths embed. */
    private Path embedded(Path source, String key, SyntheticLibraries libraries) throws IOException {
        Path output = Files.createDirectories(dir.resolve("out-" + key + "-" + System.nanoTime())).resolve(key);
        Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING);
        SyntheticEmbedder.embedIntoJar(output, key, transformer, libraries);
        return output;
    }

    /** A compiled fixture class with every fixture name moved to where this test puts it. */
    private static byte[] fixture(String simpleName) throws IOException {
        try (InputStream in = StandInAdaptersTest.class.getResourceAsStream(
                "/" + FIXTURE + simpleName + ".class")) {
            assertNotNull(in, simpleName + " is compiled with the tests");
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(in.readAllBytes()).accept(new ClassRemapper(writer, new SimpleRemapper(NAMES)), 0);
            return writer.toByteArray();
        }
    }

    private static Path jar(Path path, Map<String, byte[]> classes) throws IOException {
        return jar(path, classes, Map.of());
    }

    private static Path jar(Path path, Map<String, byte[]> classes, Map<String, String> resources)
            throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
            for (Map.Entry<String, String> resource : resources.entrySet()) {
                out.putNextEntry(new JarEntry(resource.getKey()));
                out.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return path;
    }

    private static Map<String, byte[]> entries(Path jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (!entry.isDirectory()) entries.put(entry.getName(), file.getInputStream(entry).readAllBytes());
            }
        }
        return entries;
    }

    private static URL url(Path jar) throws IOException {
        return jar.toUri().toURL();
    }
}
