/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2 removed DistExecutor; a Forge target gets Retromod's rebuilt copy in Forge's own shape. */
class ForgeDistExecutorShapeTest {

    private static final String DIST_EXECUTOR = LegacyDistExecutorBridge.DIST_EXECUTOR;
    private static final String REBUILT = LegacyDistExecutorBridge.REBUILT;
    private static final String DIST = "net/minecraftforge/api/distmarker/Dist";
    private static final String L_DIST = "L" + DIST + ";";
    private static final String FML_ENVIRONMENT = "net/minecraftforge/fml/loading/FMLEnvironment";
    private static final String SUPPLIER = "Ljava/util/function/Supplier;";
    private static final String IFORGE_REGISTRY = "net/minecraftforge/registries/IForgeRegistry";
    private static final String CONDITION = "net/minecraftforge/common/crafting/conditions/ICondition";
    private static final String HOOKS = ForgeDistExecutorShapeTest.Hooks.class.getName().replace('.', '/');

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Hooks.asked.clear();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Every 1.20.1 method takes Forge's Dist, reads FMLEnvironment.dist, and nothing names NeoForge")
    void rebuiltClassHasTheForgeShape() throws IOException {
        ClassNode executor = node(resource(REBUILT));
        String run = "(" + L_DIST + SUPPLIER + ")V";
        String call = "(" + L_DIST + SUPPLIER + ")Ljava/lang/Object;";
        String forDist = "(" + SUPPLIER + SUPPLIER + ")Ljava/lang/Object;";
        Map<String, String> expected = Map.of(
                "runWhenOn", run, "unsafeRunWhenOn", run, "safeRunWhenOn", run,
                "callWhenOn", call, "unsafeCallWhenOn", call, "safeCallWhenOn", call,
                "runForDist", forDist, "unsafeRunForDist", forDist, "safeRunForDist", forDist);
        expected.forEach((name, desc) -> {
            MethodNode method = method(executor, name);
            assertEquals(desc, method.desc, name);
            assertEquals(ACC_PUBLIC | ACC_STATIC, method.access & (ACC_PUBLIC | ACC_STATIC), name);
        });
        assertTrue(readsDist(executor), "the side comes from FMLEnvironment.dist, the field Forge 26.2 keeps");

        assertEquals(List.of(), node(resource(REBUILT + "$SafeReferent")).interfaces);
        assertEquals(List.of(REBUILT + "$SafeReferent", "java/lang/Runnable", "java/io/Serializable"),
                node(resource(REBUILT + "$SafeRunnable")).interfaces);
        assertEquals(List.of(REBUILT + "$SafeReferent", "java/util/concurrent/Callable", "java/io/Serializable"),
                node(resource(REBUILT + "$SafeCallable")).interfaces);
        assertEquals(List.of(REBUILT + "$SafeReferent", "java/util/function/Supplier", "java/io/Serializable"),
                node(resource(REBUILT + "$SafeSupplier")).interfaces);

        for (String name : List.of("", "$SafeReferent", "$SafeRunnable", "$SafeCallable", "$SafeSupplier")) {
            String text = new String(resource(REBUILT + name), StandardCharsets.ISO_8859_1);
            assertFalse(text.contains("net/neoforged"), REBUILT + name + " names a NeoForge class");
        }
    }

    @Test
    @DisplayName("Forge 26.2 sends DistExecutor and its markers to the rebuilt class; 1.21.11 and NeoForge keep theirs")
    void bridgeFollowsTheHost() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        LegacyDistExecutorBridge.register(t);
        assertEquals(REBUILT, t.getClassRedirects().get(DIST_EXECUTOR));
        for (String marker : List.of("$SafeReferent", "$SafeRunnable", "$SafeCallable", "$SafeSupplier")) {
            assertEquals(REBUILT + marker, t.getClassRedirects().get(DIST_EXECUTOR + marker), marker);
            assertTrue(t.getSyntheticClasses().containsKey(REBUILT + marker), marker + " travels with the class");
        }

        // Forge 1.21.11 still ships DistExecutor.
        t.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyDistExecutorBridge.register(t);
        assertFalse(t.getClassRedirects().containsKey(DIST_EXECUTOR), "1.21.11");

        t.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        LegacyDistExecutorBridge.register(t);
        assertFalse(t.getClassRedirects().containsKey(DIST_EXECUTOR), "NeoForge");
    }

    @Test
    @DisplayName("A static initializer's runForDist builds only the running side's proxy")
    void staticInitializerPicksTheRunningSide() throws Exception {
        Map<String, byte[]> classes = translatedProxies();
        for (String side : List.of("DEDICATED_SERVER", "CLIENT")) {
            Hooks.asked.clear();
            Class<?> proxies = onSide(side, classes);
            String expected = side.equals("CLIENT") ? "client" : "server";
            assertEquals(expected + " proxy", proxies.getField("PROXY").get(null), side);
            assertEquals(List.of(expected), Hooks.asked, "the other side's supplier must never run");
        }
    }

    @Test
    @DisplayName("The run, call and safe forms run only on the side they name")
    void otherSideNeverRuns() throws Exception {
        Class<?> proxies = onSide("DEDICATED_SERVER", translatedProxies());
        Object client = dist(proxies, "CLIENT");
        Object server = dist(proxies, "DEDICATED_SERVER");
        List<String> ran = new ArrayList<>();

        invoke(proxies, "runOn", client, (Supplier<Runnable>) () -> () -> ran.add("client"));
        invoke(proxies, "runOn", server, (Supplier<Runnable>) () -> () -> ran.add("server"));
        assertEquals(List.of("server"), ran);

        assertNull(invoke(proxies, "callOn", client, (Supplier<Callable<String>>) () -> () -> "client"));
        assertEquals("server", invoke(proxies, "callOn", server, (Supplier<Callable<String>>) () -> () -> "server"));
        assertEquals("server", invoke(proxies, "pickSafe",
                (Supplier<Supplier<String>>) () -> () -> "client", (Supplier<Supplier<String>>) () -> () -> "server"));

        // Forge 1.20.1 threw a checked exception from the call wrapped in a RuntimeException.
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () ->
                invoke(proxies, "callOn", server, (Supplier<Callable<String>>) () -> () -> {
                    throw new IOException("disk");
                }));
        assertInstanceOf(RuntimeException.class, thrown.getCause());
        assertInstanceOf(IOException.class, thrown.getCause().getCause());
    }

    @Test
    @DisplayName("registerAll on a Forge 26.2 target embeds no IForgeRegistry or ICondition stand-in")
    void forgeTargetEmbedsOnlyWhatTheHostLacks() throws IOException {
        RetromodTransformer t = RetromodTransformer.getInstance();
        ForgeNeoForgeSynthetics.registerAll(t);
        Map<String, byte[]> synthetics = t.getSyntheticClasses();
        for (String shadowing : List.of(IFORGE_REGISTRY, CONDITION, CONDITION + "$IContext",
                DIST_EXECUTOR, DIST_EXECUTOR + "$SafeRunnable", DIST_EXECUTOR + "$SafeSupplier")) {
            assertFalse(synthetics.containsKey(shadowing), shadowing + " would shadow or misshape a Forge class");
        }
        assertTrue(synthetics.containsKey(REBUILT));
        assertTrue(synthetics.containsKey(ForgeRecipeConditionSynthetics.CRAFTING_HELPER), "26.2 has no CraftingHelper");
        assertTrue(synthetics.containsKey(ForgeRecipeConditionSynthetics.SERIALIZER), "26.2 has no IConditionSerializer");

        // The embedder copies only registered stand-ins, so the host's own classes stay referenced.
        byte[] user = t.transformClass(registryUser(), "mod/Registers");
        SyntheticEmbedder.ByteEmbeddingResult embedded =
                SyntheticEmbedder.embedIntoJarBytes(jar("mod/Registers", user), "registers.jar", t);
        assertTrue(embedded.succeeded());
        Map<String, byte[]> entries = entries(embedded.jarBytes());
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(REBUILT + ".class")), entries.keySet().toString());
        assertTrue(entries.keySet().stream().noneMatch(name -> name.startsWith(SyntheticEmbedder.PREFIX)
                && (name.endsWith(IFORGE_REGISTRY + ".class") || name.endsWith(CONDITION + ".class"))),
                entries.keySet().toString());
        String text = new String(entries.get("mod/Registers.class"), StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("(L" + IFORGE_REGISTRY + ";L" + CONDITION + ";)V"), "the mod keeps the host types");
    }

    @Test
    @DisplayName("registerAll on an older Forge target adds nothing the host already ships")
    void olderForgeTargetGetsNoStandIns() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        ForgeNeoForgeSynthetics.registerAll(t);
        for (String kept : List.of(IFORGE_REGISTRY, CONDITION, DIST_EXECUTOR, REBUILT,
                ForgeRecipeConditionSynthetics.CRAFTING_HELPER, ForgeRecipeConditionSynthetics.SERIALIZER)) {
            assertFalse(t.getSyntheticClasses().containsKey(kept), kept);
        }
    }

    @Test
    @DisplayName("registerAll on a NeoForge target is unchanged")
    void neoForgeTargetKeepsItsShapes() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        McReflect.setForceNeoForge(true);
        ForgeNeoForgeSynthetics.registerAll(t);
        Map<String, byte[]> synthetics = t.getSyntheticClasses();
        for (String name : List.of(IFORGE_REGISTRY, CONDITION, DIST_EXECUTOR, DIST_EXECUTOR + "$SafeSupplier")) {
            assertTrue(synthetics.containsKey(name), name);
        }
        assertFalse(synthetics.containsKey(REBUILT));
        String text = new String(synthetics.get(DIST_EXECUTOR), StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("net/neoforged/api/distmarker/Dist"), "NeoForge's DistExecutor takes NeoForge's Dist");
    }

    /** Suppliers a translated static initializer reads, recording which side was asked. */
    public static final class Hooks {
        public static final List<String> asked = new ArrayList<>();
        public static final Supplier<Supplier<Object>> CLIENT = () -> {
            asked.add("client");
            return () -> "client proxy";
        };
        public static final Supplier<Supplier<Object>> SERVER = () -> {
            asked.add("server");
            return () -> "server proxy";
        };
    }

    /** The translated proxy class, the rebuilt DistExecutor it now names, and stand-ins for Forge's side types. */
    private static Map<String, byte[]> translatedProxies() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        LegacyDistExecutorBridge.register(t);
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("mod/Proxies", t.transformClass(proxies(), "mod/Proxies"));
        t.getSyntheticClasses().forEach((name, bytes) -> {
            if (name.startsWith(REBUILT)) classes.put(name, bytes);
        });
        classes.put(DIST, distStub());
        classes.put(FML_ENVIRONMENT, environmentStub());
        return classes;
    }

    /** Loads the classes in a fresh loader whose FMLEnvironment.dist is {@code side}, without initializing the proxies. */
    private static Class<?> onSide(String side, Map<String, byte[]> classes) throws Exception {
        HostLoader loader = new HostLoader(classes);
        Class<?> dist = Class.forName(DIST.replace('/', '.'), true, loader);
        Class.forName(FML_ENVIRONMENT.replace('/', '.'), true, loader).getField("dist").set(null, dist.getField(side).get(null));
        return Class.forName("mod.Proxies", false, loader);
    }

    private static Object dist(Class<?> proxies, String side) throws ReflectiveOperationException {
        return Class.forName(DIST.replace('/', '.'), true, proxies.getClassLoader()).getField(side).get(null);
    }

    private static Object invoke(Class<?> proxies, String name, Object... args) throws Exception {
        for (Method method : proxies.getMethods()) {
            if (method.getName().equals(name)) return method.invoke(null, args);
        }
        throw new AssertionError("no method " + name);
    }

    /**
     * An old mod's proxy holder: {@code PROXY = DistExecutor.runForDist(...)} in its static
     * initializer, plus the run, call and safe forms with the suppliers passed in.
     */
    private static byte[] proxies() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Proxies", null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "PROXY", "Ljava/lang/Object;", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitFieldInsn(GETSTATIC, HOOKS, "CLIENT", SUPPLIER);
        init.visitFieldInsn(GETSTATIC, HOOKS, "SERVER", SUPPLIER);
        init.visitMethodInsn(INVOKESTATIC, DIST_EXECUTOR, "runForDist", "(" + SUPPLIER + SUPPLIER + ")Ljava/lang/Object;", false);
        init.visitFieldInsn(PUTSTATIC, "mod/Proxies", "PROXY", "Ljava/lang/Object;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        forward(cw, "runOn", "safeRunWhenOn", "(" + L_DIST + SUPPLIER + ")V");
        forward(cw, "callOn", "callWhenOn", "(" + L_DIST + SUPPLIER + ")Ljava/lang/Object;");
        forward(cw, "pickSafe", "safeRunForDist", "(" + SUPPLIER + SUPPLIER + ")Ljava/lang/Object;");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code public static <desc> name(a, b) { return DistExecutor.target(a, b); }}. */
    private static void forward(ClassWriter cw, String name, String target, String desc) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESTATIC, DIST_EXECUTOR, target, desc, false);
        m.visitInsn(desc.endsWith(")V") ? RETURN : ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code void use(IForgeRegistry registry, ICondition condition)} plus a DistExecutor call. */
    private static byte[] registryUser() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Registers", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "use", "(L" + IFORGE_REGISTRY + ";L" + CONDITION + ";)V", null, null);
        m.visitCode();
        m.visitFieldInsn(GETSTATIC, DIST, "CLIENT", L_DIST);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKESTATIC, DIST_EXECUTOR, "unsafeRunWhenOn", "(" + L_DIST + SUPPLIER + ")V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Dist stand-in with Forge's two constants. */
    private static byte[] distStub() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, DIST, null, "java/lang/Object", null);
        for (String side : List.of("CLIENT", "DEDICATED_SERVER")) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, side, L_DIST, null, null).visitEnd();
        }
        MethodVisitor ctor = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        for (String side : List.of("CLIENT", "DEDICATED_SERVER")) {
            init.visitTypeInsn(NEW, DIST);
            init.visitInsn(DUP);
            init.visitMethodInsn(INVOKESPECIAL, DIST, "<init>", "()V", false);
            init.visitFieldInsn(PUTSTATIC, DIST, side, L_DIST);
        }
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An FMLEnvironment stand-in whose dist the test sets. */
    private static byte[] environmentStub() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, FML_ENVIRONMENT, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "dist", L_DIST, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Defines its own classes first, so the rebuilt copy on the test classpath never wins. */
    private static final class HostLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        HostLoader(Map<String, byte[]> classes) {
            super(ForgeDistExecutorShapeTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
            }
        }
    }

    private static boolean readsDist(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC
                        && field.owner.equals(FML_ENVIRONMENT) && field.name.equals("dist") && field.desc.equals(L_DIST)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] resource(String internalName) throws IOException {
        try (InputStream in = ForgeDistExecutorShapeTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, internalName + " is missing from the build");
            return in.readAllBytes();
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no method " + name));
    }

    private static byte[] jar(String name, byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name + ".class"));
            zip.write(bytes);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> entries(byte[] jar) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
