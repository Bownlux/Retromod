/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiContext;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.loaderapi.LoaderApiProvider;
import com.retromod.loaderapi.LoaderApiRegistration;
import com.retromod.loaderapi.LoaderApiTestSupport;
import com.retromod.loaderapi.fabric.v26.synth.BoundaryTypeSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A library and a mod translated as separate jars share one name for a removed callback type. */
class BoundaryTypeTwoJarTest {

    private static final String HOST = "26.3";
    private static final String OLD = "fixture/old/BlockColor";
    private static final String SHARED = "com/retromod/loaderapi/shared/TestColor";
    private static final String TYPED = "(Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/Long;I)I";
    private static final String ERASED = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)I";
    private static final BoundaryTypeSpec SPEC = new BoundaryTypeSpec(OLD, SHARED, "getColor", TYPED,
            "colorOf", ERASED, "com/retromod/loaderapi/fabric/v26/synth/TestColorBridge");
    // A second old type with the same method name and the same erased shape.
    private static final String OLD_TINT = "fixture/old/LeafTint";
    private static final String TINT_TYPED = "(Ljava/lang/Long;Ljava/lang/Integer;Ljava/lang/String;I)I";
    private static final BoundaryTypeSpec TINT_SPEC = new BoundaryTypeSpec(OLD_TINT,
            "com/retromod/loaderapi/shared/TestTint", "getColor", TINT_TYPED, "tintOf", ERASED,
            "com/retromod/loaderapi/fabric/v26/synth/TestTintBridge");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private AutoCloseable runtime;
    private String savedHost;

    @TempDir
    Path dir;

    /** Registers only the boundary spec, as a batch would. */
    static final class BoundaryProvider implements LoaderApiProvider {
        @Override
        public String id() {
            return "boundary-test";
        }

        @Override
        public String rowsVersion() {
            return "1";
        }

        @Override
        public boolean appliesTo(LoaderApiContext context) {
            return "fabric".equals(context.loader());
        }

        @Override
        public void register(LoaderApiRegistration registration, LoaderApiContext context) {
            SPEC.register(registration);
            TINT_SPEC.register(registration);
        }
    }

    @BeforeEach
    void setUp() {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = HOST;
        transformer.clearRedirectsForTesting();
        runtime = LoaderApiTestSupport.runtimeOn(new BoundaryProvider());
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    // Balm and Waystones pass BlockColor this way. Without one shared name the call fails with
    // NoSuchMethodError.
    @Test
    @DisplayName("A library and its caller, translated apart, link through one shared type")
    void libraryAndCallerShareOneType() throws Exception {
        Path library = write("boundary-lib.jar", modJar("boundary_lib", "lib/ColorRegistry.class",
                registryClass()));
        Map<String, byte[]> dependent = new LinkedHashMap<>();
        dependent.put("dep/Main.class", mainClass());
        dependent.put("dep/Overrider.class", overriderClass());
        Path caller = write("boundary-dep.jar", modJar("boundary_dep", dependent));

        Path libraryOut = new FabricModTransformer(HOST).transformMod(library,
                Files.createDirectories(dir.resolve("out-lib")));
        Path callerOut = new FabricModTransformer(HOST).transformMod(caller,
                Files.createDirectories(dir.resolve("out-dep")));

        String libraryText = text(libraryOut, "lib/ColorRegistry.class");
        String callerText = text(callerOut, "dep/Main.class");
        assertTrue(libraryText.contains("(L" + SHARED + ";)V"),
                "the library's public method must take the shared type");
        assertTrue(callerText.contains("(L" + SHARED + ";)V"),
                "the caller must name the same shared type in its call");
        assertTrue(!libraryText.contains(OLD) && !callerText.contains(OLD));

        try (URLClassLoader loader = new URLClassLoader(new URL[]{libraryOut.toUri().toURL(),
                callerOut.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> main = Class.forName("dep.Main", true, loader);
            assertEquals(42, main.getMethod("lambdaPath").invoke(null),
                    "the library must call the caller's lambda through the shared type");
            assertEquals(7, main.getMethod("overridePath").invoke(null),
                    "the library must call the caller's override through its bridge");
        }
    }

    @Test
    @DisplayName("A class whose library base implements the old type, and that names it again, has one method to run")
    void libraryBaseAndDependentBothNameTheOldType() throws Exception {
        Map<String, byte[]> library = new LinkedHashMap<>();
        library.put("lib/ColorRegistry.class", registryClass());
        library.put("lib/BaseColor.class", colorClass("lib/BaseColor", "java/lang/Object",
                new String[]{OLD}, TYPED, 11));
        Map<String, byte[]> dependent = new LinkedHashMap<>();
        dependent.put("dep/BothColor.class", colorClass("dep/BothColor", "lib/BaseColor",
                new String[]{OLD}, null, 0));
        dependent.put("dep/Registers.class", registers("dep/Registers", "dep/BothColor"));

        try (URLClassLoader loader = translateAndLoad(library, dependent)) {
            Class<?> registers = Class.forName("dep.Registers", true, loader);
            assertEquals(11, registers.getMethod("viaLibrary").invoke(null),
                    "two jars' bridges meet in one class, and its own method must decide");
        }
    }

    @Test
    @DisplayName("A class that implements two shared types of one erased shape answers each through its own method")
    void oneClassImplementsTwoSharedTypes() throws Exception {
        Map<String, byte[]> library = new LinkedHashMap<>();
        library.put("lib/ColorRegistry.class", registryClass());
        Map<String, byte[]> dependent = new LinkedHashMap<>();
        dependent.put("dep/Dual.class", dualClass());
        dependent.put("dep/AsTint.class", asTintClass());

        try (URLClassLoader loader = translateAndLoad(library, dependent)) {
            Object dual = Class.forName("dep.Dual", true, loader).getConstructor().newInstance();
            Class<?> color = Class.forName("com.retromod.loaderapi.shared.TestColor", true, loader);
            assertEquals(21, color.getMethod("colorOf", Object.class, Object.class, Object.class, int.class)
                    .invoke(dual, null, null, null, 0));
            assertEquals(22, Class.forName("dep.AsTint", true, loader).getMethod("tint", Object.class)
                    .invoke(null, dual), "the old call on the second type reaches its own method");
        }
    }

    /** Translates the two jars apart, as two mods are, and loads them together. */
    private URLClassLoader translateAndLoad(Map<String, byte[]> library, Map<String, byte[]> dependent)
            throws Exception {
        Path libraryOut = new FabricModTransformer(HOST).transformMod(
                write("bridge-lib.jar", modJar("bridge_lib", library)),
                Files.createDirectories(dir.resolve("out-bridge-lib")));
        Path dependentOut = new FabricModTransformer(HOST).transformMod(
                write("bridge-dep.jar", modJar("bridge_dep", dependent)),
                Files.createDirectories(dir.resolve("out-bridge-dep")));
        return new URLClassLoader(new URL[]{libraryOut.toUri().toURL(), dependentOut.toUri().toURL()},
                getClass().getClassLoader());
    }

    private Path write(String name, byte[] jar) throws Exception {
        Path path = dir.resolve(name);
        Files.write(path, jar);
        return path;
    }

    private static byte[] modJar(String id, String className, byte[] classBytes) throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(className, classBytes);
        return modJar(id, classes);
    }

    private static byte[] modJar(String id, Map<String, byte[]> classes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"1.20.1\"}}").getBytes(StandardCharsets.UTF_8));
        entries.putAll(classes);
        return LoaderApiFixtures.jar(entries);
    }

    private static String text(Path jar, String entry) throws Exception {
        try (var file = new java.util.jar.JarFile(jar.toFile())) {
            return new String(file.getInputStream(file.getJarEntry(entry)).readAllBytes(),
                    StandardCharsets.ISO_8859_1);
        }
    }

    /** {@code lib/ColorRegistry}: stores the old type and calls its typed method. */
    private static byte[] registryClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "lib/ColorRegistry", null,
                "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "last", "L" + OLD + ";", null, null)
                .visitEnd();
        MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                "(L" + OLD + ";)V", null, null);
        register.visitCode();
        register.visitVarInsn(Opcodes.ALOAD, 0);
        register.visitFieldInsn(Opcodes.PUTSTATIC, "lib/ColorRegistry", "last", "L" + OLD + ";");
        register.visitInsn(Opcodes.RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        MethodVisitor apply = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "apply",
                "(Ljava/lang/String;)I", null, null);
        apply.visitCode();
        apply.visitFieldInsn(Opcodes.GETSTATIC, "lib/ColorRegistry", "last", "L" + OLD + ";");
        apply.visitVarInsn(Opcodes.ALOAD, 0);
        apply.visitInsn(Opcodes.ICONST_1);
        apply.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                "(I)Ljava/lang/Integer;", false);
        apply.visitInsn(Opcodes.LCONST_1);
        apply.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                "(J)Ljava/lang/Long;", false);
        apply.visitInsn(Opcodes.ICONST_3);
        apply.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD, "getColor", TYPED, true);
        apply.visitInsn(Opcodes.IRETURN);
        apply.visitMaxs(0, 0);
        apply.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code dep/Main}: registers a lambda, then an override, and asks the library for colors. */
    private static byte[] mainClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "dep/Main", null,
                "java/lang/Object", null);
        MethodVisitor lambdaPath = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lambdaPath", "()I", null, null);
        lambdaPath.visitCode();
        LoaderApiFixtures.lambda(lambdaPath, OLD, "getColor", TYPED, "dep/Main", "lambda$color$0");
        callRegistry(lambdaPath, "x");
        LoaderApiFixtures.end(lambdaPath, Opcodes.IRETURN);

        MethodVisitor body = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                | Opcodes.ACC_SYNTHETIC, "lambda$color$0", TYPED, null, null);
        body.visitCode();
        body.visitVarInsn(Opcodes.ILOAD, 3);
        body.visitIntInsn(Opcodes.BIPUSH, 39);
        body.visitInsn(Opcodes.IADD);
        LoaderApiFixtures.end(body, Opcodes.IRETURN);

        MethodVisitor overridePath = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "overridePath", "()I", null, null);
        overridePath.visitCode();
        overridePath.visitTypeInsn(Opcodes.NEW, "dep/Overrider");
        overridePath.visitInsn(Opcodes.DUP);
        overridePath.visitMethodInsn(Opcodes.INVOKESPECIAL, "dep/Overrider", "<init>", "()V", false);
        callRegistry(overridePath, "y");
        LoaderApiFixtures.end(overridePath, Opcodes.IRETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void callRegistry(MethodVisitor mv, String state) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "lib/ColorRegistry", "register", "(L" + OLD + ";)V",
                false);
        mv.visitLdcInsn(state);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "lib/ColorRegistry", "apply", "(Ljava/lang/String;)I",
                false);
    }

    /** A class that names {@code interfaces} and, unless {@code typed} is null, returns {@code value} from it. */
    private static byte[] colorClass(String name, String superName, String[] interfaces, String typed,
            int value) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName, interfaces);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        LoaderApiFixtures.end(init, Opcodes.RETURN);
        if (typed != null) {
            MethodVisitor color = cw.visitMethod(Opcodes.ACC_PUBLIC, "getColor", typed, null, null);
            color.visitCode();
            color.visitIntInsn(Opcodes.BIPUSH, value);
            LoaderApiFixtures.end(color, Opcodes.IRETURN);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code viaLibrary()}: hands a new {@code handler} to the library and asks it for a color. */
    private static byte[] registers(String name, String handler) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "viaLibrary", "()I",
                null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, handler);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, handler, "<init>", "()V", false);
        callRegistry(mv, "w");
        LoaderApiFixtures.end(mv, Opcodes.IRETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code dep/Dual}: implements both old types, each with its own typed method. */
    private static byte[] dualClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "dep/Dual", null, "java/lang/Object",
                new String[]{OLD, OLD_TINT});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        LoaderApiFixtures.end(init, Opcodes.RETURN);
        for (String[] shape : new String[][]{{TYPED, "21"}, {TINT_TYPED, "22"}}) {
            MethodVisitor color = cw.visitMethod(Opcodes.ACC_PUBLIC, "getColor", shape[0], null, null);
            color.visitCode();
            color.visitIntInsn(Opcodes.BIPUSH, Integer.parseInt(shape[1]));
            LoaderApiFixtures.end(color, Opcodes.IRETURN);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code dep/AsTint.tint(Object)}: the old typed call on the second old type. */
    private static byte[] asTintClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "dep/AsTint", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tint", "(Ljava/lang/Object;)I",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, OLD_TINT);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD_TINT, "getColor", TINT_TYPED, true);
        LoaderApiFixtures.end(mv, Opcodes.IRETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code dep/Overrider}: implements the old type with its typed method. */
    private static byte[] overriderClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "dep/Overrider", null,
                "java/lang/Object", new String[]{OLD});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        LoaderApiFixtures.end(init, Opcodes.RETURN);
        MethodVisitor color = cw.visitMethod(Opcodes.ACC_PUBLIC, "getColor", TYPED, null, null);
        color.visitCode();
        color.visitIntInsn(Opcodes.BIPUSH, 7);
        LoaderApiFixtures.end(color, Opcodes.IRETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
