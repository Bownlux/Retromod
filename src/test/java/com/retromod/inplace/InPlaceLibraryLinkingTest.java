/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A 1.12 addon and its core mod dropped into {@code mods/} together are translated in one launch.
 * The core has to be translated first so the addon reuses its stand-in classes, and an addon that
 * was translated before its core arrived is translated again once it does.
 */
class InPlaceLibraryLinkingTest {

    private static final String MATERIAL = "com/retromod/generated/fixture/LegacyMaterial";
    private static final String CORE_BLOCK = "core/blocks/BaseMetaBlock";
    private static final String ADDON_BLOCK = "addon/blocks/MetaIO";
    private static final String TOOL_API = "tools/ToolApi";
    private static final String ADDON_TOOLS = "addon/UsesTools";
    private static final String CTOR = "(Ljava/lang/String;L" + MATERIAL + ";)V";

    @TempDir
    Path tmp;

    @BeforeEach
    void registerStandIn() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        ClassWriter material = new ClassWriter(0);
        material.visit(V17, ACC_PUBLIC | ACC_SUPER, MATERIAL, null, "java/lang/Object", null);
        material.visitEnd();
        transformer.registerSyntheticClass(MATERIAL, material.toByteArray());
    }

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the core is translated before an addon listed ahead of it, and the addon links to it")
    void coreFirstThenAddon() throws IOException {
        Path game = game();
        addon(game.resolve("mods/a-rocks.jar"));
        core(game.resolve("mods/z-core.jar"));
        List<String> order = new ArrayList<>();

        int translated = launchFor(discover(game)).translatePending("1.20.1",
                () -> recording(order));

        assertEquals(2, translated);
        assertEquals(List.of("z-core.jar", "a-rocks.jar"), order);
        assertAddonLinksToCore(discover(game));
    }

    @Test
    @DisplayName("an addon translated before its core arrived is translated again with it")
    void addonIsRelinkedWhenTheCoreArrives() throws IOException {
        Path game = game();
        addon(game.resolve("mods/a-rocks.jar"));
        assertEquals(1, launchFor(discover(game)).translatePending("1.20.1", () -> recording(new ArrayList<>())));

        core(game.resolve("mods/z-core.jar"));
        List<String> order = new ArrayList<>();
        int translated = launchFor(discover(game)).translatePending("1.20.1", () -> recording(order));

        assertEquals(2, translated, "the core, then the addon again");
        assertEquals(List.of("z-core.jar", "a-rocks.jar"), order);
        assertAddonLinksToCore(discover(game));
    }

    @Test
    @DisplayName("an addon of two libraries served from an earlier launch calls each with its own Material")
    void addonOfTwoEarlierLibrariesLinksToBoth() throws IOException {
        // Only an interface stand-in, as the rebuilt Tier is, can be converted between copies.
        ClassWriter material = new ClassWriter(0);
        material.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, MATERIAL, null, "java/lang/Object", null);
        material.visitEnd();
        RetromodTransformer.getInstance().registerSyntheticClass(MATERIAL, material.toByteArray());
        Path game = game();
        tools(game.resolve("mods/m-tools.jar"));
        core(game.resolve("mods/z-core.jar"));
        assertEquals(2, launchFor(discover(game)).translatePending("1.20.1", () -> recording(new ArrayList<>())));
        addonOfBoth(game.resolve("mods/a-rocks.jar"));
        List<String> order = new ArrayList<>();

        assertEquals(1, launchFor(discover(game)).translatePending("1.20.1", () -> recording(order)));

        assertEquals(List.of("a-rocks.jar"), order, "the libraries are not translated again");
        DiscoveryEngine.Outcome next = discover(game);
        Path core = served(next, "z-core");
        Path tools = served(next, "m-tools");
        Path addon = served(next, "a-rocks");
        String coreCtor = constructorOf(read(core, CORE_BLOCK));
        assertEquals(List.of(CORE_BLOCK + ".<init>" + coreCtor), superCalls(read(addon, ADDON_BLOCK)),
                "the addon calls the core's constructor as the core declares it");
        String toolsUse = methodDesc(read(tools, TOOL_API), "use");
        assertEquals(List.of(TOOL_API + ".use" + toolsUse), calls(read(addon, ADDON_TOOLS), TOOL_API),
                "the addon calls the tool library as it declares the method");
        assertNotEquals(coreCtor.replace("(Ljava/lang/String;", "("), toolsUse, "each library keeps its own Material");

        // A later launch with nothing new finds every served copy current.
        com.retromod.core.SyntheticLibraries served = com.retromod.core.SyntheticLibraries.create();
        for (Path jar : List.of(core, tools, addon)) served.add(jar);
        assertFalse(served.isStale(addon), "the addon's adapters match the libraries as served");
    }

    @Test
    @DisplayName("an addon crossing a class stand-in into two libraries stays current, and a new mod translates alone")
    void classStandInCrossingStaysCurrent() throws IOException {
        Path game = game();
        tools(game.resolve("mods/m-tools.jar"));
        core(game.resolve("mods/z-core.jar"));
        assertEquals(2, launchFor(discover(game)).translatePending("1.20.1", () -> recording(new ArrayList<>())));
        addonOfBoth(game.resolve("mods/a-rocks.jar"));
        assertEquals(1, launchFor(discover(game)).translatePending("1.20.1", () -> recording(new ArrayList<>())));

        // A class stand-in cannot be converted, so one call stays unlinked, but the translation is current.
        DiscoveryEngine.Outcome served = discover(game);
        com.retromod.core.SyntheticLibraries libraries = com.retromod.core.SyntheticLibraries.create();
        for (String prefix : List.of("z-core", "m-tools", "a-rocks")) libraries.add(served(served, prefix));
        assertFalse(libraries.isStale(served(served, "a-rocks")), "the addon matches what it was translated with");

        // An unrelated mod arriving later is the only jar translated.
        modJar(game.resolve("mods/b-other.jar"), "other", Map.of("other/Thing", plainClass("other/Thing")));
        List<String> order = new ArrayList<>();
        launchFor(discover(game)).translatePending("1.20.1", () -> recording(order));

        assertEquals(List.of("b-other.jar"), order, "the addon and its libraries are not translated again");
    }

    private static byte[] plainClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Path served(DiscoveryEngine.Outcome outcome, String prefix) throws IOException {
        try (Stream<Path> served = Files.list(outcome.serveFolder())) {
            return served.filter(jar -> jar.getFileName().toString().startsWith(prefix)).findFirst()
                    .orElseThrow(() -> new AssertionError(prefix + " is not served"));
        }
    }

    private static String methodDesc(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow().desc;
    }

    private static List<String> calls(ClassNode node, String owner) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) {
                    calls.add(call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }

    /** {@code public class ToolApi { public static void use(Material m) {} }} */
    private static void tools(Path file) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, TOOL_API, null, "java/lang/Object", null);
        MethodVisitor use = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "use", "(L" + MATERIAL + ";)V", null, null);
        use.visitCode();
        use.visitInsn(RETURN);
        use.visitMaxs(0, 0);
        use.visitEnd();
        cw.visitEnd();
        modJar(file, "tools", Map.of(TOOL_API, cw.toByteArray()));
    }

    /** The core addon, plus a class that hands a Material to the tool library. */
    private static void addonOfBoth(Path file) throws IOException {
        addon(file);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, ADDON_TOOLS, null, "java/lang/Object", null);
        MethodVisitor run = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        run.visitCode();
        run.visitInsn(ACONST_NULL);
        run.visitMethodInsn(INVOKESTATIC, TOOL_API, "use", "(L" + MATERIAL + ";)V", false);
        run.visitInsn(RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        cw.visitEnd();
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(file.toFile())) {
            classes.put(ADDON_BLOCK, jar.getInputStream(jar.getJarEntry(ADDON_BLOCK + ".class")).readAllBytes());
        }
        classes.put(ADDON_TOOLS, cw.toByteArray());
        modJar(file, "rocks", classes);
    }

    private static InPlaceLaunch.LinkingTranslator recording(List<String> order) {
        ForgeModTransformer forge = new ForgeModTransformer("1.20.1");
        return (source, out, libraries) -> {
            order.add(source.getFileName().toString());
            return forge.transformMod(source, out, libraries);
        };
    }

    private static void assertAddonLinksToCore(DiscoveryEngine.Outcome outcome) throws IOException {
        Path core = null;
        Path addon = null;
        try (Stream<Path> served = Files.list(outcome.serveFolder())) {
            for (Path jar : served.toList()) {
                if (jar.getFileName().toString().startsWith("z-core")) core = jar;
                if (jar.getFileName().toString().startsWith("a-rocks")) addon = jar;
            }
        }
        assertNotNull(core, "the core's translation is served");
        assertNotNull(addon, "the addon's translation is served");
        String coreCtor = constructorOf(read(core, CORE_BLOCK));
        assertTrue(coreCtor.contains(SyntheticEmbedder.PREFIX), "the core takes its own embedded Material");
        assertEquals(List.of(CORE_BLOCK + ".<init>" + coreCtor), superCalls(read(addon, ADDON_BLOCK)),
                "the addon calls the constructor the core declares");
    }

    private Path game() throws IOException {
        Path game = tmp.resolve("game");
        Files.createDirectories(game.resolve("mods"));
        return game;
    }

    private static DiscoveryEngine.Outcome discover(Path game) throws IOException {
        List<Path> listing;
        try (Stream<Path> files = Files.list(game.resolve("mods"))) {
            listing = files.sorted().toList();
        }
        return DiscoveryEngine.run(new DiscoveryEngine.Request(game, game.resolve("mods"),
                listing, "forge", "1.20.1", "", ForgeModMetadata.FORGE_TOML,
                DiscoveryEngine.ServeStyle.FOLDER, new Properties(), (level, message) -> { }));
    }

    private static InPlaceLaunch launchFor(DiscoveryEngine.Outcome outcome) {
        Properties properties = new Properties();
        properties.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_IN_PLACE);
        properties.setProperty(InPlaceStatus.STATUS_PROPERTY, outcome.statusFile().toString());
        return InPlaceLaunch.decide(outcome.statusFile().getParent(), properties);
    }

    private static void core(Path file) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, CORE_BLOCK, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", CTOR, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        modJar(file, "core", CORE_BLOCK, cw.toByteArray());
    }

    private static void addon(Path file) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, ADDON_BLOCK, null, CORE_BLOCK, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", CTOR, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, CORE_BLOCK, "<init>", CTOR, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        modJar(file, "rocks", ADDON_BLOCK, cw.toByteArray());
    }

    private static void modJar(Path file, String modId, String className, byte[] classBytes)
            throws IOException {
        modJar(file, modId, Map.of(className, classBytes));
    }

    private static void modJar(Path file, String modId, Map<String, byte[]> classes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", TestModJars.toml(modId, "1.0.0", "[1.19.2,1.20)", "forge",
                "[40,)", "mandatory=true").getBytes(StandardCharsets.UTF_8));
        classes.forEach((className, classBytes) -> entries.put(className + ".class", classBytes));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static ClassNode read(Path jar, String className) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(className + ".class");
            assertNotNull(entry, className + " is in " + jar.getFileName());
            ClassNode node = new ClassNode();
            try (var in = file.getInputStream(entry)) {
                new ClassReader(in.readAllBytes()).accept(node, 0);
            }
            return node;
        }
    }

    private static String constructorOf(ClassNode node) {
        return node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst()
                .orElseThrow().desc;
    }

    private static List<String> superCalls(ClassNode node) {
        List<String> calls = new ArrayList<>();
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
