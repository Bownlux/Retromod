/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A class redirect never moves a library away from the classes its own jar defines, unless the name is the host's. */
class OwnLibraryClassRedirectTest {

    private static final String WIDGET = "lib/api/Widget";
    private static final String NEXT_WIDGET = "lib/next/Widget";
    private static final String LIBRARY_USER = "lib/api/WidgetUser";
    private static final String ADDON_USER = "addon/WidgetUser";
    private static final String LIBRARY_MAIN = "lib/api/LibraryMain";
    private static final String STAND_IN = "com/retromod/generated/fixture/OwnLibraryStandIn";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @TempDir
    Path dir;

    @BeforeEach
    void registerMove() {
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(WIDGET, NEXT_WIDGET);
        transformer.registerMethodRedirect(WIDGET, "draw", "()V", NEXT_WIDGET, "render", "()V");
    }

    @AfterEach
    void reset() {
        transformer.clearLinkedLibraries();
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A library's own classes keep their names and their calls to each other")
    void aLibraryKeepsItsOwnClasses() {
        Map<String, byte[]> library = Map.of(WIDGET, widget(WIDGET), LIBRARY_USER, user(LIBRARY_USER, WIDGET));

        try (var jar = transformer.pushJarClassBytesProvider(library::get)) {
            assertEquals(WIDGET, read(transform(library.get(WIDGET), WIDGET)).name,
                    "the class file stays at its entry path, so the loader can define it");
            assertEquals(List.of(WIDGET + ".draw()V"), calls(transform(library.get(LIBRARY_USER), LIBRARY_USER)));
        }
    }

    @Test
    @DisplayName("A jar that only uses the library still takes the move and its member redirect")
    void anotherJarStillTakesTheMove() {
        byte[] addon = user(ADDON_USER, WIDGET);

        try (var jar = transformer.pushJarClassBytesProvider(Map.of(ADDON_USER, addon)::get)) {
            assertEquals(List.of(NEXT_WIDGET + ".render()V"), calls(transform(addon, ADDON_USER)));
        }
    }

    @Test
    @DisplayName("A nested library keeps its own names while the jar around it takes the move")
    void aNestedLibraryKeepsItsOwnWhileTheOuterJarMoves() {
        byte[] addon = user(ADDON_USER, WIDGET);
        Map<String, byte[]> nested = Map.of(WIDGET, widget(WIDGET), LIBRARY_USER, user(LIBRARY_USER, WIDGET));

        try (var outer = transformer.pushJarClassBytesProvider(Map.of(ADDON_USER, addon)::get)) {
            try (var inner = transformer.pushJarClassBytesProvider(nested::get)) {
                assertEquals(List.of(WIDGET + ".draw()V"), calls(transform(nested.get(LIBRARY_USER), LIBRARY_USER)));
            }
            assertEquals(List.of(NEXT_WIDGET + ".render()V"), calls(transform(addon, ADDON_USER)));
        }
    }

    @Test
    @DisplayName("A kept library class still takes the redirect of a host method it inherits")
    void aKeptClassStillTakesInheritedHostRedirects() {
        String entity = "net/minecraft/world/entity/Entity";
        String sub = "lib/api/Sub";
        transformer.registerClassRedirect(sub, "lib/next/Sub");
        transformer.registerInheritedMethodRedirect(entity, "oldKill", "()V", entity, "newKill", "()V");
        Map<String, byte[]> library = Map.of(sub, plainSubclass(sub, entity), LIBRARY_USER, killer(LIBRARY_USER, sub));

        try (var jar = transformer.pushJarClassBytesProvider(library::get)) {
            assertEquals(List.of(entity + ".newKill()V"), calls(transform(library.get(LIBRARY_USER), LIBRARY_USER)),
                    "Entity.oldKill moves, though the library keeps its own Sub");
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "Forge Config API Port on NeoForge, net/minecraftforge/common/ForgeConfigSpec, "
                    + "net/neoforged/neoforge/common/ModConfigSpec",
            "a bundled Fabric API module, net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings, "
                    + "net/minecraft/world/level/block/state/BlockBehaviour$Properties",
            "a bundled MixinExtras, com/llamalad7/mixinextras/injector/WrapOperation, "
                    + "com/llamalad7/mixinextras/injector/wrapoperation/WrapOperation",
            "a mod shipping a Minecraft class, net/minecraft/util/OldThing, net/minecraft/util/NewThing",
            "a Retromod stand-in copy, com/retromod/legacyapi/OldStandIn, net/minecraft/util/NewThing"})
    @DisplayName("A jar's copy of a host name follows its users, so a port's API still links")
    void hostNamesFollowTheirUsers(String jarKind, String hostName, String target) {
        transformer.registerClassRedirect(hostName, target);
        String registry = "port/Registry";
        Map<String, byte[]> port = Map.of(hostName, widget(hostName), registry, registry(registry, hostName));
        byte[] dependent = callsRegistry("dependent/Uses", registry, hostName);

        String declared;
        try (var jar = transformer.pushJarClassBytesProvider(port::get)) {
            declared = registryDescriptor(transform(port.get(registry), registry));
        }
        String called;
        try (var jar = transformer.pushJarClassBytesProvider(Map.of("dependent/Uses", dependent)::get)) {
            called = calls(transform(dependent, "dependent/Uses")).get(0);
        }

        assertEquals("(L" + target + ";)V", declared, jarKind + " moves its own copy with its users");
        assertEquals(registry + ".register(L" + target + ";)V", called, "the dependent calls what the port declares");
    }

    @Test
    @DisplayName("The embedder leaves a library's own names alone and still moves a dependent's")
    void theEmbedderKeepsWhatTheClassPassKept() throws Exception {
        transformer.registerSyntheticClass(STAND_IN, widget(STAND_IN));
        Path library = jar("library.jar", Map.of(WIDGET, widget(WIDGET),
                LIBRARY_USER, user(LIBRARY_USER, WIDGET, STAND_IN)));
        Path addon = jar("addon.jar", Map.of(ADDON_USER, user(ADDON_USER, WIDGET, STAND_IN)));

        // As after a class pass: no jar is in scope while the embedder runs.
        assertTrue(SyntheticEmbedder.embedIntoJar(library, "library", transformer, SyntheticLibraries.NONE) > 0);
        assertTrue(SyntheticEmbedder.embedIntoJar(addon, "addon", transformer, SyntheticLibraries.NONE) > 0);

        assertEquals(List.of(WIDGET + ".draw()V"), calls(entry(library, LIBRARY_USER)),
                "the library keeps its own Widget");
        assertEquals(WIDGET, read(entry(library, WIDGET)).name);
        assertEquals(List.of(NEXT_WIDGET + ".draw()V"), calls(entry(addon, ADDON_USER)),
                "a reference the class pass left in a dependent still takes the move");
    }

    @Test
    @DisplayName("A move that yields to a library stays off while the mod ships it or is linked with it")
    void aMoveYieldsToALibraryTheModIsTranslatedWith() throws Exception {
        transformer.yieldClassRedirectToLibrary(WIDGET, LIBRARY_MAIN);
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(jar("library.jar", Map.of(LIBRARY_MAIN, widget(LIBRARY_MAIN))));

        try (var jar = transformer.pushJarClassBytesProvider(Map.of(LIBRARY_MAIN, widget(LIBRARY_MAIN))::get)) {
            assertEquals(WIDGET, transformer.classRedirectForCurrentMod(WIDGET), "a mod that ships the library");
        }
        assertEquals(NEXT_WIDGET, transformer.classRedirectForCurrentMod(WIDGET), "a mod alone");

        transformer.setModSourceVersion("1.20.1");
        transformer.setLinkedLibraries(libraries);
        assertEquals(WIDGET, transformer.classRedirectForCurrentMod(WIDGET), "a mod linked with the library");
        transformer.clearLinkedLibraries();
        assertEquals(WIDGET, transformer.classRedirectForCurrentMod(WIDGET),
                "the embedder runs after the class pass cleared the links and decides the same way");
        assertEquals(WIDGET, transformer.classRedirectForJar(WIDGET, name -> false));

        transformer.setModSourceVersion("1.20.1");
        assertEquals(NEXT_WIDGET, transformer.classRedirectForCurrentMod(WIDGET), "the next mod starts without them");
    }

    @Test
    @DisplayName("A move that does not yield ignores the libraries a mod is linked with")
    void aPlainMoveIgnoresLinkedLibraries() throws Exception {
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(jar("library.jar", Map.of(WIDGET, widget(WIDGET))));

        transformer.setLinkedLibraries(libraries);

        assertEquals(NEXT_WIDGET, transformer.classRedirectForCurrentMod(WIDGET));
    }

    private byte[] transform(byte[] classBytes, String name) {
        byte[] out = transformer.transformClass(classBytes, name);
        return out != null ? out : classBytes;
    }

    /** {@code public class name { public void draw() {} }} */
    private static byte[] widget(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor draw = writer.visitMethod(ACC_PUBLIC, "draw", "()V", null, null);
        draw.visitCode();
        draw.visitInsn(RETURN);
        draw.visitMaxs(0, 0);
        draw.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class whose {@code use(Widget)} calls {@code draw()}, and that names each extra type in a field. */
    private static byte[] user(String name, String widget, String... fieldTypes) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        for (int i = 0; i < fieldTypes.length; i++) {
            writer.visitField(ACC_PUBLIC | ACC_STATIC, "f" + i, "L" + fieldTypes[i] + ";", null, null).visitEnd();
        }
        MethodVisitor use = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "use", "(L" + widget + ";)V", null, null);
        use.visitCode();
        use.visitVarInsn(ALOAD, 0);
        use.visitMethodInsn(INVOKEVIRTUAL, widget, "draw", "()V", false);
        use.visitInsn(RETURN);
        use.visitMaxs(0, 0);
        use.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code public class name extends parent {}} */
    private static byte[] plainSubclass(String name, String parent) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, parent, null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class whose {@code run(Sub)} calls the inherited {@code oldKill()} on it. */
    private static byte[] killer(String name, String sub) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor run = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(L" + sub + ";)V", null, null);
        run.visitCode();
        run.visitVarInsn(ALOAD, 0);
        run.visitMethodInsn(INVOKEVIRTUAL, sub, "oldKill", "()V", false);
        run.visitInsn(RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code public class name { public static void register(Spec spec) {} }}, a port's API taking its own type. */
    private static byte[] registry(String name, String spec) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor register = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "(L" + spec + ";)V",
                null, null);
        register.visitCode();
        register.visitInsn(RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A dependent's {@code run()} that hands null to {@code registry.register(Spec)}. */
    private static byte[] callsRegistry(String name, String registry, String spec) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor run = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        run.visitCode();
        run.visitInsn(ACONST_NULL);
        run.visitMethodInsn(INVOKESTATIC, registry, "register", "(L" + spec + ";)V", false);
        run.visitInsn(RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String registryDescriptor(byte[] classBytes) {
        return read(classBytes).methods.stream().filter(m -> m.name.equals("register")).findFirst().orElseThrow().desc;
    }

    private static List<String> calls(byte[] classBytes) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : read(classBytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && !call.name.equals("<init>")) {
                    calls.add(call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }

    private static ClassNode read(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node;
    }

    private Path jar(String name, Map<String, byte[]> classes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        classes.forEach((className, bytes) -> entries.put(className + ".class", bytes));
        return Files.write(dir.resolve(name), LoaderApiFixtures.jar(entries));
    }

    private static byte[] entry(Path jar, String className) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getInputStream(file.getJarEntry(className + ".class")).readAllBytes();
        }
    }
}
