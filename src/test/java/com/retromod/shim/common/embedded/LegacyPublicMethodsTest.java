/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Methods of non-public classes from another loader and package, the way a host's internals look to an embedded helper. */
class LegacyPublicMethodsTest {

    private static final String REGISTRY = "fixture/api/Registry";
    private static final String BASE = "fixture/api/Base";
    private static final String WRAPPER = "fixture/impl/Wrapper";
    private static final String LONELY = "fixture/impl/Lonely";
    private static final String SHAPE = "fixture/api/Shape";
    private static final String DRIFTED = "fixture/impl/Drifted";
    private static final String FACTORY = "fixture/impl/Factory";
    private static final String TEMPLATE = "net/minecraft/world/item/ItemStackTemplate";
    private static final String HIDDEN_TEMPLATE = "fixture/impl/HiddenTemplate";
    private static final String RECIPE = "net/minecraft/world/item/crafting/FakeRecipe";
    private static final String INTERNALS = "com/retromod/shim/common/embedded/LegacyRecipeInternals";
    private static final String PUBLIC_METHODS = "com/retromod/shim/common/embedded/LegacyPublicMethods";
    private static final String MOD = "mod/ReadsResult";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a public method found on a non-public class cannot be invoked, which is the failure this fixes")
    void plainLookupFailsOnANonPublicClass() throws Exception {
        Object wrapper = fixtures().factory("create");
        Method own = wrapper.getClass().getMethod("wrapAsHolder", Object.class);
        assertThrows(IllegalAccessException.class, () -> own.invoke(wrapper, "x"));
    }

    @Test
    @DisplayName("method() answers with the public interface that declares it, and the object's override runs")
    void methodComesFromThePublicInterface() throws Exception {
        Object wrapper = fixtures().factory("create");
        Method wrap = LegacyPublicMethods.method(wrapper.getClass(), "wrapAsHolder", Object.class);
        assertEquals(REGISTRY.replace('/', '.'), wrap.getDeclaringClass().getName());
        assertEquals(List.of("x"), wrap.invoke(wrapper, "x"));
    }

    @Test
    @DisplayName("a public superclass is more specific than an interface")
    void methodPrefersAPublicSuperclass() throws Exception {
        Object wrapper = fixtures().factory("create");
        Method name = LegacyPublicMethods.method(wrapper.getClass(), "name");
        assertEquals(BASE.replace('/', '.'), name.getDeclaringClass().getName());
        assertEquals("wrapper", name.invoke(wrapper), "the call still runs the wrapper's own override");
    }

    @Test
    @DisplayName("methods() lists each method once, and every one of them can be invoked")
    void methodsAreAllInvocable() throws Exception {
        Object wrapper = fixtures().factory("create");
        Method[] methods = LegacyPublicMethods.methods(wrapper.getClass());
        assertEquals(1, Arrays.stream(methods).filter(m -> m.getName().equals("self")).count(),
                "a covariant override and its bridge resolve to one declaration");
        for (Method method : methods) {
            if (method.getDeclaringClass() == Object.class || method.getParameterCount() != 0) continue;
            assertDoesNotThrow(() -> method.invoke(wrapper), method.toString());
        }
        assertNotSame(methods, LegacyPublicMethods.methods(wrapper.getClass()), "callers get their own array");
    }

    @Test
    @DisplayName("a method nothing public declares is opened when the module allows it, as for a mod's own class")
    void anUndeclaredMethodIsOpenedWhereAllowed() throws Exception {
        Object lonely = fixtures().factory("lonely");
        assertEquals("own", LegacyPublicMethods.method(lonely.getClass(), "own").invoke(lonely));
    }

    @Test
    @DisplayName("a supertype method whose descriptor the class does not implement is never used in its place")
    void aDriftedReturnTypeKeepsTheClassesOwnMethod() throws Exception {
        Object drifted = fixtures().factory("drifted");
        Method codec = LegacyPublicMethods.method(drifted.getClass(), "codec");
        assertEquals(DRIFTED.replace('/', '.'), codec.getDeclaringClass().getName(),
                "the public Shape.codec() returns Object, which this class never implemented");
        assertEquals("drifted", codec.invoke(drifted));
    }

    @Test
    @DisplayName("a JDK bridge resolves to a public declaration whose return type is narrower, as JumboEnumSet.clone() does")
    void aJdkBridgeResolvesToANarrowerPublicDeclaration() throws Exception {
        // More than 64 constants, so EnumSet hands back its package-private JumboEnumSet.
        EnumSet<Character.UnicodeScript> scripts = EnumSet.allOf(Character.UnicodeScript.class);
        Class<?> jumbo = scripts.getClass();
        assertFalse(java.lang.reflect.Modifier.isPublic(jumbo.getModifiers()), "the fixture must not be public");
        assertThrows(IllegalAccessException.class, () -> jumbo.getMethod("clone").invoke(scripts));

        int clones = 0;
        for (Method method : LegacyPublicMethods.methods(jumbo)) {
            if (!method.getName().equals("clone")) continue;
            clones++;
            assertTrue(java.lang.reflect.Modifier.isPublic(method.getDeclaringClass().getModifiers()), method.toString());
            assertEquals(scripts, method.invoke(scripts), method.toString());
        }
        assertTrue(clones > 0, "methods() lists clone()");
        assertEquals(scripts, LegacyPublicMethods.method(jumbo, "clone").invoke(scripts));
    }

    @Test
    @DisplayName("invocable() hands a method that is not public back unchanged")
    void aMethodThatIsNotPublicComesBackUnchanged() throws Exception {
        Method protectedClone = Object.class.getDeclaredMethod("clone");
        assertSame(protectedClone, LegacyPublicMethods.invocable(fixtures().factory("create").getClass(), protectedClone));
    }

    @Test
    @DisplayName("a null parameter type is a missing method, as it is for getMethod")
    void aNullParameterTypeIsMissing() throws Exception {
        Object wrapper = fixtures().factory("create");
        assertThrows(NoSuchMethodException.class,
                () -> LegacyPublicMethods.method(wrapper.getClass(), "wrapAsHolder", (Class<?>) null));
    }

    @Test
    @DisplayName("lookups are cached per class, and a missing method still throws")
    void cachedAndStillStrict() throws Exception {
        Object wrapper = fixtures().factory("create");
        assertSame(LegacyPublicMethods.method(wrapper.getClass(), "name"),
                LegacyPublicMethods.method(wrapper.getClass(), "name"));
        assertThrows(NoSuchMethodException.class, () -> LegacyPublicMethods.method(wrapper.getClass(), "absent"));
    }

    @Test
    @DisplayName("an embedded helper brings its own copy along and reads through a non-public class")
    void embeddedCopyTravelsWithItsHelper(@TempDir Path dir) throws Throwable {
        transformer.clearRedirectsForTesting();
        SyntheticEmbedder.registerClassResource(transformer, INTERNALS, LegacyRecipeInternals.class);
        assertTrue(transformer.getSyntheticClasses().containsKey(PUBLIC_METHODS),
                "registering a helper that calls LegacyPublicMethods registers it too");

        Path jar = dir.resolve("mod.jar");
        writeJar(jar, Map.of(MOD + ".class", modClass()));
        assertTrue(SyntheticEmbedder.embedIntoJar(jar, "testmod", transformer) >= 2);

        Map<String, byte[]> classes = new HashMap<>();
        String base = SyntheticEmbedder.embeddedBase("testmod");
        try (JarFile out = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(out.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                classes.put(entry.getName().substring(0, entry.getName().length() - 6),
                        out.getInputStream(entry).readAllBytes());
            }
        }
        assertTrue(classes.containsKey(base + PUBLIC_METHODS), "the shared helper is embedded beside its caller");
        assertTrue(calls(classes.get(base + INTERNALS)).contains(base + PUBLIC_METHODS),
                "the embedded helper calls the embedded copy, never Retromod's own");

        Fixtures host = new Fixtures(classes);
        Object recipe = host.type(RECIPE).getConstructor().newInstance();
        Method run = host.type(MOD).getMethod("run", Object.class);
        try {
            assertEquals("stack", run.invoke(null, recipe));
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Fixtures fixtures() {
        return new Fixtures(Map.of());
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance).map(i -> ((MethodInsnNode) i).owner).toList();
    }

    private static void writeJar(Path jar, Map<String, byte[]> entries) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    /** {@code mod/ReadsResult.run(recipe)}: a mod class whose redirected call reaches the helper. */
    private static byte[] modClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor run = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        run.visitCode();
        run.visitVarInsn(ALOAD, 0);
        run.visitMethodInsn(INVOKESTATIC, INTERNALS, "result", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        run.visitInsn(ARETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Host-like classes in their own loader: public API types, and package-private implementations. */
    private static final class Fixtures {
        private final ClassLoader loader;

        Fixtures(Map<String, byte[]> extra) {
            Map<String, byte[]> classes = new HashMap<>(extra);
            classes.put(REGISTRY, registry());
            classes.put(BASE, base());
            classes.put(WRAPPER, wrapper());
            classes.put(LONELY, lonely());
            classes.put(SHAPE, shape());
            classes.put(DRIFTED, drifted());
            classes.put(FACTORY, factory());
            classes.put(TEMPLATE, template());
            classes.put(HIDDEN_TEMPLATE, hiddenTemplate());
            classes.put(RECIPE, recipe());
            loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes == null) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
        }

        Class<?> type(String internalName) throws ClassNotFoundException {
            return Class.forName(internalName.replace('/', '.'), true, loader);
        }

        Object factory(String method) throws Exception {
            return type(FACTORY).getMethod(method).invoke(null);
        }

        private static byte[] registry() {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, REGISTRY, null, "java/lang/Object", null);
            cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "wrapAsHolder", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null).visitEnd();
            cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "self", "()Ljava/lang/Object;", null, null).visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] base() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_SUPER, BASE, null, "java/lang/Object", null);
            constructor(cw, "java/lang/Object");
            constant(cw, ACC_PUBLIC, "name", "base");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] wrapper() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_SUPER, WRAPPER, null, BASE, new String[] {REGISTRY});
            constructor(cw, BASE);
            constant(cw, ACC_PUBLIC, "name", "wrapper");
            MethodVisitor wrap = cw.visitMethod(ACC_PUBLIC, "wrapAsHolder", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
            wrap.visitCode();
            wrap.visitVarInsn(ALOAD, 1);
            wrap.visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "(Ljava/lang/Object;)Ljava/util/List;", true);
            wrap.visitInsn(ARETURN);
            wrap.visitMaxs(0, 0);
            wrap.visitEnd();
            // A covariant self() and the bridge javac writes beside it.
            MethodVisitor self = cw.visitMethod(ACC_PUBLIC, "self", "()L" + WRAPPER + ";", null, null);
            self.visitCode();
            self.visitVarInsn(ALOAD, 0);
            self.visitInsn(ARETURN);
            self.visitMaxs(0, 0);
            self.visitEnd();
            MethodVisitor bridge = cw.visitMethod(ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC, "self", "()Ljava/lang/Object;", null, null);
            bridge.visitCode();
            bridge.visitVarInsn(ALOAD, 0);
            bridge.visitMethodInsn(INVOKEVIRTUAL, WRAPPER, "self", "()L" + WRAPPER + ";", false);
            bridge.visitInsn(ARETURN);
            bridge.visitMaxs(0, 0);
            bridge.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] lonely() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_SUPER, LONELY, null, "java/lang/Object", null);
            constructor(cw, "java/lang/Object");
            constant(cw, ACC_PUBLIC, "own", "own");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] factory() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_SUPER, FACTORY, null, "java/lang/Object", null);
            for (String[] made : new String[][] {{"create", WRAPPER}, {"lonely", LONELY}, {"template", HIDDEN_TEMPLATE},
                    {"drifted", DRIFTED}}) {
                MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, made[0], "()Ljava/lang/Object;", null, null);
                mv.visitCode();
                mv.visitTypeInsn(NEW, made[1]);
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKESPECIAL, made[1], "<init>", "()V", false);
                mv.visitInsn(ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** A public interface whose codec() returns Object. */
        private static byte[] shape() {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, SHAPE, null, "java/lang/Object", null);
            cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "codec", "()Ljava/lang/Object;", null, null).visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** Implements Shape with a codec() returning String and no bridge, as a class built against an older Shape would. */
        private static byte[] drifted() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_SUPER, DRIFTED, null, "java/lang/Object", new String[] {SHAPE});
            constructor(cw, "java/lang/Object");
            constant(cw, ACC_PUBLIC, "codec", "drifted");
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** 26.1's public ItemStackTemplate, reduced to the create() the helper calls. */
        private static byte[] template() {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, TEMPLATE, null, "java/lang/Object", null);
            cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "create", "()Ljava/lang/Object;", null, null).visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] hiddenTemplate() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_SUPER, HIDDEN_TEMPLATE, null, "java/lang/Object", new String[] {TEMPLATE});
            constructor(cw, "java/lang/Object");
            constant(cw, ACC_PUBLIC, "create", "stack", "()Ljava/lang/Object;");
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** A vanilla recipe whose private result field holds a template of a non-public class. */
        private static byte[] recipe() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_SUPER, RECIPE, null, "java/lang/Object", null);
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "result", "L" + TEMPLATE + ";", null, null).visitEnd();
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESTATIC, FACTORY, "template", "()Ljava/lang/Object;", false);
            init.visitTypeInsn(CHECKCAST, TEMPLATE);
            init.visitFieldInsn(PUTFIELD, RECIPE, "result", "L" + TEMPLATE + ";");
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static void constructor(ClassWriter cw, String superName) {
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }

        private static void constant(ClassWriter cw, int access, String name, String value) {
            constant(cw, access, name, value, "()Ljava/lang/String;");
        }

        private static void constant(ClassWriter cw, int access, String name, String value, String descriptor) {
            MethodVisitor mv = cw.visitMethod(access, name, descriptor, null, null);
            mv.visitCode();
            mv.visitLdcInsn(value);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }
}
