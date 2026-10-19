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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * An addon and the library it builds on are translated as separate jars, and each embeds the
 * stand-in classes it uses. The addon has to use the library's copy of a stand-in that the
 * library's API takes, or its call into the library names a different class and fails with
 * {@code NoSuchMethodError}. Rockhounding Rocks calling Rockhounding Core's
 * {@code BaseMetaBlock(String, String, Material, String[])} is the case this covers.
 */
class SyntheticEmbedderCrossJarTest {

    /** A removed class both jars use, rebuilt as a stand-in. */
    private static final String MATERIAL = "com/retromod/generated/fixture/LegacyMaterial";
    /** A stand-in the Material stand-in itself links to. */
    private static final String MATERIAL_HELPER = "com/retromod/generated/fixture/LegacyMaterialHelper";
    /** A stand-in only the addon uses. */
    private static final String ADDON_ONLY = "com/retromod/generated/fixture/AddonOnly";
    /** A stand-in the library uses only privately. */
    private static final String LIBRARY_PRIVATE = "com/retromod/generated/fixture/LibraryPrivate";
    /** Two stand-ins of one package; the config calls a package-private method of the events. */
    private static final String EVENTS = "com/retromod/generated/fixture/lifecycle/LegacyEvents";
    private static final String CONFIG = "com/retromod/generated/fixture/lifecycle/LegacyConfig";

    private static final String LIBRARY_BLOCK = "core/blocks/BaseMetaBlock";
    private static final String ADDON_BLOCK = "addon/blocks/MetaIO";
    private static final String CTOR = "(Ljava/lang/String;L" + MATERIAL + ";)V";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void registerStandIns() {
        transformer.clearRedirectsForTesting();
        transformer.registerSyntheticClass(MATERIAL, standIn(MATERIAL, MATERIAL_HELPER));
        transformer.registerSyntheticClass(MATERIAL_HELPER, standIn(MATERIAL_HELPER, null));
        transformer.registerSyntheticClass(ADDON_ONLY, standIn(ADDON_ONLY, null));
        transformer.registerSyntheticClass(LIBRARY_PRIVATE, standIn(LIBRARY_PRIVATE, null));
        transformer.registerSyntheticClass(EVENTS, events());
        transformer.registerSyntheticClass(CONFIG, config());
    }

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a Forge addon calls its library's constructor with the library's stand-in")
    void forgeAddonLinksToLibraryConstructor(@TempDir Path dir) throws Exception {
        SyntheticLibraries libraries = SyntheticLibraries.create();
        Path library = new ForgeModTransformer("26.1").transformMod(
                forgeJar(dir, "core-1.12.2.jar", "core", libraryClasses()), out(dir, "lib"), libraries);
        libraries.add(library);
        Path addon = new ForgeModTransformer("26.1").transformMod(
                forgeJar(dir, "rocks-1.12.2.jar", "rocks", addonClasses()), out(dir, "addon"), libraries);

        assertAddonLinksTo(library, addon);
    }

    @Test
    @DisplayName("a Fabric addon calls its library's constructor with the library's stand-in")
    void fabricAddonLinksToLibraryConstructor(@TempDir Path dir) throws Exception {
        SyntheticLibraries libraries = SyntheticLibraries.create();
        Path library = new FabricModTransformer("26.1").transformMod(
                fabricJar(dir, "core-fabric.jar", "core", libraryClasses()), out(dir, "lib"), libraries);
        libraries.add(library);
        Path addon = new FabricModTransformer("26.1").transformMod(
                fabricJar(dir, "rocks-fabric.jar", "rocks", addonClasses()), out(dir, "addon"), libraries);

        assertAddonLinksTo(library, addon);
    }

    @Test
    @DisplayName("the jar-based embed used offline links the same way")
    void offlineEmbedLinksToLibraryConstructor(@TempDir Path dir) throws Exception {
        Path library = plainJar(dir.resolve("core.jar"), libraryClasses());
        Path addon = plainJar(dir.resolve("rocks.jar"), addonClasses());
        SyntheticLibraries libraries = SyntheticLibraries.create();

        SyntheticEmbedder.embedIntoJar(library, "core.jar", transformer, libraries);
        libraries.add(library);
        int embedded = SyntheticEmbedder.embedIntoJar(addon, "rocks.jar", transformer, libraries);

        assertEquals(2, embedded, "only the stand-ins the library does not expose are embedded");
        assertAddonLinksTo(library, addon);
    }

    @Test
    @DisplayName("without its library, an addon keeps its own copies as before")
    void addonAloneKeepsItsOwnCopies(@TempDir Path dir) throws Exception {
        Path addon = plainJar(dir.resolve("rocks.jar"), addonClasses());

        SyntheticEmbedder.embedIntoJar(addon, "rocks.jar", transformer, SyntheticLibraries.NONE);

        String own = SyntheticEmbedder.embeddedBase("rocks.jar");
        Map<String, byte[]> entries = entries(addon);
        assertTrue(entries.containsKey(own + MATERIAL + ".class"));
        assertTrue(entries.containsKey(own + MATERIAL_HELPER + ".class"));
        assertFalse(entries.containsKey(SyntheticLibraries.LINKS_ENTRY));
    }

    @Test
    @DisplayName("a stand-in the library only uses privately stays the addon's own")
    void privateLibraryStandInsAreNotShared(@TempDir Path dir) throws Exception {
        Path library = plainJar(dir.resolve("core.jar"), libraryClasses());
        Path addon = plainJar(dir.resolve("rocks.jar"), addonClasses());
        SyntheticLibraries libraries = SyntheticLibraries.create();
        SyntheticEmbedder.embedIntoJar(library, "core.jar", transformer, libraries);
        libraries.add(library);

        SyntheticEmbedder.embedIntoJar(addon, "rocks.jar", transformer, libraries);

        Map<String, byte[]> entries = entries(addon);
        assertTrue(entries.containsKey(SyntheticEmbedder.embeddedBase("rocks.jar") + LIBRARY_PRIVATE + ".class"),
                "the library's private stand-in never crosses its API, so the addon keeps its copy");
    }

    @Test
    @DisplayName("a stand-in in the library's API that the addon never passes to it stays the addon's own")
    void unusedLibraryApiIsNotShared(@TempDir Path dir) throws Exception {
        Map<String, byte[]> libraryClasses = libraryClasses();
        libraryClasses.put("core/CoreMod", apiTaking("core/CoreMod", EVENTS));
        Path library = plainJar(dir.resolve("core.jar"), libraryClasses);
        Map<String, byte[]> addonClasses = addonClasses();
        addonClasses.put("addon/AddonMod", apiTaking("addon/AddonMod", EVENTS));
        Path addon = plainJar(dir.resolve("rocks.jar"), addonClasses);
        SyntheticLibraries libraries = SyntheticLibraries.create();
        SyntheticEmbedder.embedIntoJar(library, "core.jar", transformer, libraries);
        libraries.add(library);

        SyntheticEmbedder.embedIntoJar(addon, "rocks.jar", transformer, libraries);

        assertTrue(entries(addon).containsKey(SyntheticEmbedder.embeddedBase("rocks.jar") + EVENTS + ".class"),
                "every 1.12 core names its lifecycle events, but an addon keeps its own");
        assertAddonLinksTo(library, addon);
    }

    @Test
    @DisplayName("a stand-in is not shared when one the addon keeps relies on package access to it")
    void packageAccessIsNotSplitAcrossJars(@TempDir Path dir) throws Exception {
        Map<String, byte[]> libraryClasses = new LinkedHashMap<>();
        libraryClasses.put("core/CoreApi", apiTaking("core/CoreApi", EVENTS));
        Path library = plainJar(dir.resolve("core.jar"), libraryClasses);
        Map<String, byte[]> addonClasses = new LinkedHashMap<>();
        addonClasses.put("addon/UsesApi", callerOf("addon/UsesApi", "core/CoreApi", EVENTS, CONFIG));
        Path addon = plainJar(dir.resolve("rocks.jar"), addonClasses);
        SyntheticLibraries libraries = SyntheticLibraries.create();
        SyntheticEmbedder.embedIntoJar(library, "core.jar", transformer, libraries);
        libraries.add(library);

        SyntheticEmbedder.embedIntoJar(addon, "rocks.jar", transformer, libraries);

        String own = SyntheticEmbedder.embeddedBase("rocks.jar");
        Map<String, byte[]> entries = entries(addon);
        assertTrue(entries.containsKey(own + EVENTS + ".class"),
                "the config calls a package-private method of the events, so they stay together");
        assertTrue(entries.containsKey(own + CONFIG + ".class"));
        assertFalse(entries.containsKey(SyntheticLibraries.LINKS_ENTRY));
    }

    @Test
    @DisplayName("a package the library carries whole is shared whole")
    void wholePackageIsShared(@TempDir Path dir) throws Exception {
        Map<String, byte[]> libraryClasses = new LinkedHashMap<>();
        libraryClasses.put("core/CoreApi", apiTaking("core/CoreApi", EVENTS));
        libraryClasses.put("core/CoreConfig", apiTaking("core/CoreConfig", CONFIG));
        Path library = plainJar(dir.resolve("core.jar"), libraryClasses);
        Map<String, byte[]> addonClasses = new LinkedHashMap<>();
        addonClasses.put("addon/UsesApi", callerOf("addon/UsesApi", "core/CoreApi", EVENTS, CONFIG));
        Path addon = plainJar(dir.resolve("rocks.jar"), addonClasses);
        SyntheticLibraries libraries = SyntheticLibraries.create();
        SyntheticEmbedder.embedIntoJar(library, "core.jar", transformer, libraries);
        libraries.add(library);

        SyntheticEmbedder.embedIntoJar(addon, "rocks.jar", transformer, libraries);

        String core = SyntheticEmbedder.embeddedBase("core.jar");
        assertEquals(core + CONFIG + "\n" + core + EVENTS + "\n",
                new String(entries(addon).get(SyntheticLibraries.LINKS_ENTRY), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a library is ordered before the addon that references its classes")
    void librariesComeFirst(@TempDir Path dir) throws Exception {
        Path library = plainJar(dir.resolve("a-core.jar"), libraryClasses());
        Path addon = plainJar(dir.resolve("0-rocks.jar"), addonClasses());

        assertEquals(List.of(library, addon),
                SyntheticLibraries.librariesFirst(List.of(addon, library), jar -> jar));
    }

    @Test
    @DisplayName("an addon translated before its library, or after it was removed, is stale")
    void staleLinksAreNoticed(@TempDir Path dir) throws Exception {
        Path library = plainJar(dir.resolve("core.jar"), libraryClasses());
        Path addon = plainJar(dir.resolve("rocks.jar"), addonClasses());
        SyntheticEmbedder.embedIntoJar(addon, "rocks.jar", transformer, SyntheticLibraries.NONE);
        SyntheticEmbedder.embedIntoJar(library, "core.jar", transformer, SyntheticLibraries.NONE);
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(library);

        assertTrue(libraries.isStale(addon), "the addon has its own copy of the library's Material");

        Path relinked = plainJar(dir.resolve("rocks-again.jar"), addonClasses());
        SyntheticEmbedder.embedIntoJar(relinked, "rocks.jar", transformer, libraries);
        assertFalse(libraries.isStale(relinked), "a jar linked against these libraries is current");

        assertTrue(SyntheticLibraries.create().isStale(relinked),
                "the Material the addon reuses is gone once its library is");
    }

    @Test
    @DisplayName("a cached Forge translation made before its library arrived is translated again")
    void forgeCacheIsRefreshedWhenALibraryArrives(@TempDir Path dir) throws Exception {
        Path addonSource = forgeJar(dir, "rocks-1.12.2.jar", "rocks", addonClasses());
        Path addonOut = out(dir, "addon");
        Path first = new ForgeModTransformer("26.1").transformMod(
                addonSource, addonOut, SyntheticLibraries.NONE);
        SyntheticLibraries libraries = SyntheticLibraries.create();
        Path library = new ForgeModTransformer("26.1").transformMod(
                forgeJar(dir, "core-1.12.2.jar", "core", libraryClasses()), out(dir, "lib"), libraries);
        libraries.add(library);

        Path second = new ForgeModTransformer("26.1").transformMod(addonSource, addonOut, libraries);

        assertEquals(first, second);
        assertAddonLinksTo(library, second);
    }

    private void assertAddonLinksTo(Path library, Path addon) throws IOException {
        String libraryBase = baseOf(library);
        String addonBase = baseOf(addon);
        assertNotNull(libraryBase, "the library embeds its own Material");
        assertNotEquals(libraryBase, addonBase, "each jar keeps its own package for its own copies");

        Map<String, byte[]> addonEntries = entries(addon);
        String expectedCtor = "(Ljava/lang/String;L" + libraryBase + MATERIAL + ";)V";
        assertTrue(declaresConstructor(entries(library).get(LIBRARY_BLOCK + ".class"), expectedCtor),
                "the library declares its constructor with its own Material");
        assertEquals(List.of(LIBRARY_BLOCK + ".<init>" + expectedCtor),
                superCalls(addonEntries.get(ADDON_BLOCK + ".class")),
                "the addon must call the constructor the library declares");

        assertTrue(addonEntries.keySet().stream().noneMatch(name -> name.endsWith(MATERIAL + ".class")
                        || name.endsWith(MATERIAL_HELPER + ".class")),
                "the addon reuses the library's Material and its helper instead of embedding them");
        assertTrue(addonEntries.containsKey(addonBase + ADDON_ONLY + ".class"),
                "a stand-in only the addon uses is still embedded into the addon");
        assertEquals(libraryBase + MATERIAL + "\n" + libraryBase + MATERIAL_HELPER + "\n",
                new String(addonEntries.get(SyntheticLibraries.LINKS_ENTRY), StandardCharsets.UTF_8),
                "the addon lists the copies it reuses");
    }

    /** The embedded package of a translated jar's own Material or AddonOnly copy. */
    private static String baseOf(Path jar) throws IOException {
        for (String name : entries(jar).keySet()) {
            for (String standIn : List.of(MATERIAL, ADDON_ONLY)) {
                if (name.startsWith(SyntheticEmbedder.PREFIX) && name.endsWith("/" + standIn + ".class")) {
                    return name.substring(0, name.length() - (standIn + ".class").length());
                }
            }
        }
        return null;
    }

    private static Map<String, byte[]> libraryClasses() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, LIBRARY_BLOCK, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "scratch", "L" + LIBRARY_PRIVATE + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", CTOR, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        classes.put(LIBRARY_BLOCK, cw.toByteArray());
        return classes;
    }

    private static Map<String, byte[]> addonClasses() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, ADDON_BLOCK, null, LIBRARY_BLOCK, null);
        cw.visitField(ACC_PRIVATE, "own", "L" + ADDON_ONLY + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "scratch", "L" + LIBRARY_PRIVATE + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", CTOR, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, LIBRARY_BLOCK, "<init>", CTOR, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        classes.put(ADDON_BLOCK, cw.toByteArray());
        return classes;
    }

    /** {@code public class name { public static void use(T value) {} }} */
    private static byte[] apiTaking(String name, String type) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor use = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "use", "(L" + type + ";)V", null, null);
        use.visitCode();
        use.visitInsn(RETURN);
        use.visitMaxs(0, 0);
        use.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Calls {@code api.use(null)} with the events type and holds a config. */
    private static byte[] callerOf(String name, String api, String events, String config) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "config", "L" + config + ";", null, null).visitEnd();
        MethodVisitor run = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        run.visitCode();
        run.visitInsn(ACONST_NULL);
        run.visitMethodInsn(INVOKESTATIC, api, "use", "(L" + events + ";)V", false);
        run.visitInsn(RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code public class LegacyEvents { static void note() {} }} */
    private static byte[] events() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, EVENTS, null, "java/lang/Object", null);
        MethodVisitor note = cw.visitMethod(ACC_STATIC, "note", "()V", null, null);
        note.visitCode();
        note.visitInsn(RETURN);
        note.visitMaxs(0, 0);
        note.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code public class LegacyConfig { public static void load() { LegacyEvents.note(); } }} */
    private static byte[] config() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, CONFIG, null, "java/lang/Object", null);
        MethodVisitor load = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "load", "()V", null, null);
        load.visitCode();
        load.visitMethodInsn(INVOKESTATIC, EVENTS, "note", "()V", false);
        load.visitInsn(RETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A stand-in class, optionally holding a static field of another stand-in. */
    private static byte[] standIn(String name, String linkedStandIn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        if (linkedStandIn != null) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC, "helper", "L" + linkedStandIn + ";", null, null).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Path forgeJar(Path dir, String name, String modId, Map<String, byte[]> classes)
            throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"" + modId + "\"\nversion=\"1.0\"\n"
                + "[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nversionRange=\"[1.20.1]\"\n")
                .getBytes(StandardCharsets.UTF_8));
        classes.forEach((className, bytes) -> entries.put(className + ".class", bytes));
        return jar(dir.resolve(name), entries);
    }

    private static Path fabricJar(Path dir, String name, String modId, Map<String, byte[]> classes)
            throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + modId
                + "\",\"version\":\"1.0\",\"depends\":{\"minecraft\":\"1.20.1\"}}")
                .getBytes(StandardCharsets.UTF_8));
        classes.forEach((className, bytes) -> entries.put(className + ".class", bytes));
        return jar(dir.resolve(name), entries);
    }

    private static Path plainJar(Path path, Map<String, byte[]> classes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        classes.forEach((className, bytes) -> entries.put(className + ".class", bytes));
        return jar(path, entries);
    }

    private static Path jar(Path path, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return path;
    }

    private static Path out(Path dir, String name) throws IOException {
        return Files.createDirectories(dir.resolve("out-" + name));
    }

    private static Map<String, byte[]> entries(Path jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (entry.isDirectory()) continue;
                try (var in = file.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static boolean declaresConstructor(byte[] classBytes, String descriptor) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(descriptor));
    }

    private static List<String> superCalls(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<String> calls = new java.util.ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL
                        && call.owner.equals(node.superName)) {
                    calls.add(call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }
}
