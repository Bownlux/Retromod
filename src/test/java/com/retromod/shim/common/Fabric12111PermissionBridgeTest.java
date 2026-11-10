/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.Fabric_1_21_10_to_1_21_11;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A 1.21.1 Fabric mod's command permission calls on a Fabric 1.21.11 host, which still runs
 * intermediary names and replaced the int level with a {@code PermissionSet}. The calls run
 * against stand-ins with the 1.21.11 intermediary shape of the stack and the permission classes.
 */
class Fabric12111PermissionBridgeTest {

    private static final String STACK = LegacyCommandSourceBridge.Intermediary.STACK;
    private static final String HELPER = LegacyCommandSourceBridge.Intermediary.HELPER;
    private static final String MOJANG_STACK = LegacyCommandSourceBridge.STACK;
    private static final String COMMANDS = "mod/Commands";
    private static final String SERVER_LEVEL = "net/minecraft/class_3218";

    @TempDir
    static Path stubDir;

    /** The 1.21.11-shaped stand-ins, by internal name. */
    private static Map<String, byte[]> host;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileHost() throws Exception {
        host = compile(Map.ofEntries(
                stub("class_2165", "public interface class_2165 {}"),
                stub("class_243", "public class class_243 {}"),
                stub("class_241", "public class class_241 {}"),
                stub("class_3218", "public class class_3218 {}"),
                stub("class_2561", "public interface class_2561 {}"),
                stub("class_1297", "public class class_1297 {}"),
                Map.entry("net/minecraft/server/MinecraftServer.java",
                        "package net.minecraft.server; public class MinecraftServer {}"),
                stub("class_12094", """
                        public enum class_12094 {
                            ALL, MODERATORS, GAMEMASTERS, ADMINS, OWNERS;
                            public static class_12094 method_75027(int id) {
                                return values()[Math.max(0, Math.min(id, 4))];
                            }
                        }"""),
                stub("class_12087", """
                        public interface class_12087 {
                            record class_12089(class_12094 level) implements class_12087 {}
                        }"""),
                stub("class_12096", """
                        public interface class_12096 {
                            boolean hasPermission(class_12087 permission);
                        }"""),
                stub("class_12086", """
                        public interface class_12086 extends class_12096 {
                            Object[] ASKED = new Object[1];
                            class_12094 level();
                            static class_12086 method_75010(class_12094 level) {
                                return new class_12086() {
                                    public class_12094 level() { return level; }
                                    public boolean hasPermission(class_12087 permission) {
                                        ASKED[0] = permission;
                                        return permission instanceof class_12087.class_12089 command
                                                && command.level().ordinal() <= level.ordinal();
                                    }
                                };
                            }
                        }"""),
                stub("class_12097", """
                        public interface class_12097 {
                            class_12096 method_75037();
                        }"""),
                stub("class_2168", """
                        public class class_2168 implements class_12097 {
                            public final class_3218 level;
                            public final class_12096 permissions;
                            public final String name;
                            public class_2168(class_2165 source, class_243 position, class_241 rotation,
                                    class_3218 level, class_12096 permissions, String name, class_2561 displayName,
                                    net.minecraft.server.MinecraftServer server, class_1297 entity) {
                                this.level = level;
                                this.permissions = permissions;
                                this.name = name;
                            }
                            public class_12096 method_75037() { return permissions; }
                            public class_2168 method_9206(class_12096 set) {
                                return new class_2168(null, null, null, level, set, name, null, null, null);
                            }
                            public class_2168 method_9230(class_12096 set) {
                                return new class_2168(null, null, null, level, set, "max", null, null, null);
                            }
                        }""")));
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("hasPermission(int) on a Fabric 1.21.11 host calls the generated helper")
    void hasPermissionReachesTheHelper() {
        assertEquals(List.of(HELPER + ".hasPermission(L" + STACK + ";I)Z"),
                callsIn(transformAll("1.21.11").get(COMMANDS), "allowed"));
    }

    @Test
    @DisplayName("an old stack built with level 2 answers hasPermission like the old int compare")
    void oldPermissionCallsRunOnTheNewSet() throws Throwable {
        Run run = new Run("1.21.11");
        Object level = run.newInstance(SERVER_LEVEL);
        Object stack = run.call("build", level);
        assertEquals("GAMEMASTERS", run.level(run.field(stack, "permissions")),
                "the old constructor's int 2 became the gamemaster set");

        assertEquals(true, run.call("allowed", stack, 2));
        Object asked = run.asked();
        assertEquals("net.minecraft.class_12087$class_12089", asked.getClass().getName(),
                "the set is asked for a command level, as vanilla's own command checks are");
        assertEquals("GAMEMASTERS", asked.getClass().getMethod("level").invoke(asked).toString());
        assertEquals(false, run.call("allowed", stack, 3));

        assertEquals("OWNERS", run.level(run.field(run.call("raise", stack), "permissions")));
        Object capped = run.call("cap", stack);
        assertEquals("max", run.field(capped, "name"), "withMaximumPermission(int) reached method_9230");
        assertEquals("ADMINS", run.level(run.field(capped, "permissions")));
    }

    @Test
    @DisplayName("the generated intermediary helper passes the stack verifier")
    void generatedHelperVerifies() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(LegacyCommandSourceBridge.generateIntermediaryHelper()).accept(node, 0);
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    @Test
    @DisplayName("Fabric 1.21.11 gets only the intermediary bridge and Fabric 26.1 only the Mojang one")
    void hostGate() {
        Map<RetromodTransformer.MethodKey, RetromodTransformer.MethodTarget> intermediary = registered("1.21.11");
        assertTrue(intermediary.containsKey(new RetromodTransformer.MethodKey(STACK, "method_9259", "(I)Z")),
                intermediary.keySet().toString());
        assertTrue(intermediary.keySet().stream().noneMatch(key -> key.owner().equals(MOJANG_STACK)),
                "the Mojang names never reach a 1.21.11 Fabric host");

        Map<RetromodTransformer.MethodKey, RetromodTransformer.MethodTarget> mojang = registered("26.1");
        assertTrue(mojang.containsKey(new RetromodTransformer.MethodKey(MOJANG_STACK, "hasPermission", "(I)Z")),
                mojang.keySet().toString());
        assertTrue(mojang.keySet().stream().noneMatch(key -> key.owner().equals(STACK)),
                "26.x remaps the mod to Mojang names first");
    }

    @Test
    @DisplayName("a host that still has the int level, or an unknown shape, is left alone")
    void hostShapes() {
        String setForm = LegacyCommandSourceBridge.Intermediary.SET_FORM;
        ClassNode current = stack(setForm);
        current.methods.add(new MethodNode(ACC_PUBLIC, "method_75037",
                LegacyCommandSourceBridge.Intermediary.PERMISSIONS_DESC, null, null));
        assertEquals(LegacyCommandSourceBridge.HostShape.PERMISSION_SET_NAMED,
                LegacyCommandSourceBridge.intermediaryShape(current));

        ClassNode old = stack(LegacyCommandSourceBridge.Intermediary.INT_FORM);
        old.methods.add(new MethodNode(ACC_PUBLIC, "method_9259", "(I)Z", null, null));
        assertEquals(LegacyCommandSourceBridge.HostShape.INT_LEVEL, LegacyCommandSourceBridge.intermediaryShape(old));

        assertEquals(LegacyCommandSourceBridge.HostShape.UNKNOWN,
                LegacyCommandSourceBridge.intermediaryShape(stack(setForm)), "no permissions() to ask");
    }

    /** The method redirects the real 1.21.11 Fabric shim registers for a host of version {@code target}. */
    private static Map<RetromodTransformer.MethodKey, RetromodTransformer.MethodTarget> registered(String target) {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_10_to_1_21_11().registerRedirects(transformer);
        return Map.copyOf(transformer.getMethodRedirects());
    }

    /** The mod class, transformed for a Fabric host of version {@code target}. */
    private static Map<String, byte[]> transformAll(String target) {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_10_to_1_21_11().registerRedirects(transformer);
        Map<String, byte[]> classes = new HashMap<>(host);
        classes.put(COMMANDS, commands());
        try (var scope = transformer.pushJarClassBytesProvider(classes::get)) {
            return Map.of(COMMANDS, transformer.transformClass(commands(), COMMANDS + ".class"));
        }
    }

    /** A stack class node with one public constructor. */
    private static ClassNode stack(String constructor) {
        ClassNode node = new ClassNode();
        node.name = STACK;
        node.methods.add(new MethodNode(ACC_PUBLIC, "<init>", constructor, null, null));
        return node;
    }

    /**
     * The permission calls a 1.21.1 Fabric mod compiled: a stack built with level 2,
     * {@code hasPermission(level)}, {@code withPermission(4)} and {@code withMaximumPermission(3)}.
     */
    private static byte[] commands() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, COMMANDS, null, "java/lang/Object", null);
        String stack = "L" + STACK + ";";

        MethodVisitor build = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "build", "(L" + SERVER_LEVEL + ";)" + stack, null, null);
        build.visitCode();
        build.visitTypeInsn(NEW, STACK);
        build.visitInsn(DUP);
        build.visitInsn(ACONST_NULL);
        build.visitInsn(ACONST_NULL);
        build.visitInsn(ACONST_NULL);
        build.visitVarInsn(ALOAD, 0);
        build.visitInsn(ICONST_2);
        build.visitLdcInsn("old");
        build.visitInsn(ACONST_NULL);
        build.visitInsn(ACONST_NULL);
        build.visitInsn(ACONST_NULL);
        build.visitMethodInsn(INVOKESPECIAL, STACK, "<init>", LegacyCommandSourceBridge.Intermediary.INT_FORM, false);
        build.visitInsn(ARETURN);
        build.visitMaxs(0, 0);
        build.visitEnd();

        MethodVisitor allowed = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "allowed", "(" + stack + "I)Z", null, null);
        allowed.visitCode();
        allowed.visitVarInsn(ALOAD, 0);
        allowed.visitVarInsn(ILOAD, 1);
        allowed.visitMethodInsn(INVOKEVIRTUAL, STACK, "method_9259", "(I)Z", false);
        allowed.visitInsn(IRETURN);
        allowed.visitMaxs(0, 0);
        allowed.visitEnd();

        withLevel(cw, "raise", "method_9206", ICONST_4);
        withLevel(cw, "cap", "method_9230", ICONST_3);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void withLevel(ClassWriter cw, String name, String id, int level) {
        String stack = "L" + STACK + ";";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(" + stack + ")" + stack, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(level);
        mv.visitMethodInsn(INVOKEVIRTUAL, STACK, id, "(I)" + stack, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The transformed mod class, Retromod's generated helpers and the stand-ins in one loader. */
    private static final class Run extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        Run(String target) {
            super(Fabric12111PermissionBridgeTest.class.getClassLoader());
            host.forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
            transformAll(target).forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
            RetromodTransformer.getInstance().getSyntheticClasses()
                    .forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            byte[] bytes = classes.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            return defineClass(name, bytes, 0, bytes.length);
        }

        Object newInstance(String internalName) throws Exception {
            return loadClass(internalName.replace('/', '.')).getConstructor().newInstance();
        }

        /** Calls the mod's static method {@code name}. */
        Object call(String name, Object... args) throws Throwable {
            for (Method method : loadClass(COMMANDS.replace('/', '.')).getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    try {
                        return method.invoke(null, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
            throw new AssertionError("no method " + COMMANDS + "." + name);
        }

        Object field(Object owner, String name) throws Exception {
            return owner.getClass().getField(name).get(owner);
        }

        /** The permission level a stand-in level-based set holds. */
        String level(Object set) throws Exception {
            Method level = loadClass("net.minecraft.class_12086").getMethod("level");
            return level.invoke(set).toString();
        }

        /** The last permission a stand-in set was asked about. */
        Object asked() throws Exception {
            return ((Object[]) loadClass("net.minecraft.class_12086").getField("ASKED").get(null))[0];
        }
    }

    private static Map.Entry<String, String> stub(String name, String body) {
        return Map.entry("net/minecraft/" + name + ".java", "package net.minecraft;\n" + body);
    }

    private static Map<String, byte[]> compile(Map<String, String> sources) throws Exception {
        Path src = stubDir.resolve("src");
        Path out = stubDir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the 1.21.11 stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the 1.21.11 stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    private static List<String> callsIn(byte[] bytes, String method) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        MethodNode target = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : target.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }
}
