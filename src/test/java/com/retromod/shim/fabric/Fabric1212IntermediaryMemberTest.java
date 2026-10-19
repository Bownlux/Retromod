/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.objectweb.asm.Opcodes.*;

/**
 * #347 on a Fabric host from 1.21.2 to 1.21.11, which still runs intermediary names: a 1.21.1
 * mod's entity damage and property values, in the intermediary ids the host changed.
 * {@code method_5643(DamageSource, float)} now names the one-argument client half, and
 * {@code method_11898} returns a {@code List}. Checked against the 1.21.2 and 1.21.11
 * intermediary tables.
 *
 * <p>The damage calls run against stand-ins with the 1.21.4 intermediary shape of
 * {@code Entity}, {@code LivingEntity} and {@code Mob}.
 */
class Fabric1212IntermediaryMemberTest {

    private static final String ENTITY = "net/minecraft/class_1297";
    private static final String LIVING = "net/minecraft/class_1309";
    private static final String MOB = "net/minecraft/class_1308";
    private static final String DAMAGE_SOURCE = "net/minecraft/class_1282";
    private static final String PROPERTY = "net/minecraft/class_2769";
    private static final String INT_PROPERTY = "net/minecraft/class_2758";
    private static final String HURT = "(L" + DAMAGE_SOURCE + ";F)Z";
    private static final String HURT_HELPER = "(L" + ENTITY + ";L" + DAMAGE_SOURCE + ";F)Z";
    private static final String VALUES_OLD = "()Ljava/util/Collection;";
    private static final String CALLS = "mod/Calls";
    private static final String OWN_MOB = "mod/OwnDamageMob";
    private static final String HELPER = "com/retromod/generated/Legacy1212IntermediaryMembers";

    @TempDir
    static Path stubDir;

    /** The 1.21.4-shaped stand-ins, by internal name. */
    private static Map<String, byte[]> host;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileHost() throws Exception {
        host = compile(Map.of(
                "net/minecraft/class_1282.java", """
                        package net.minecraft;
                        public class class_1282 {}""",
                "net/minecraft/class_1297.java", """
                        package net.minecraft;
                        public class class_1297 {
                            public Object[] lastHurt;
                            public boolean method_5643(class_1282 source) { return false; }
                            public final boolean method_64420(class_1282 source, float amount) {
                                lastHurt = new Object[] {source, amount};
                                return true;
                            }
                        }""",
                "net/minecraft/class_1309.java", """
                        package net.minecraft;
                        public class class_1309 extends class_1297 {}""",
                "net/minecraft/class_1308.java", """
                        package net.minecraft;
                        public class class_1308 extends class_1309 {}""",
                "net/minecraft/class_2769.java", """
                        package net.minecraft;
                        public class class_2769 {}""",
                "net/minecraft/class_2758.java", """
                        package net.minecraft;
                        public class class_2758 extends class_2769 {}"""));
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a 1.21.1 mod's damage and property values call what a 1.21.4 Fabric host has")
    void intermediaryCallsReachTheNewMembers() {
        List<String> calls = transform("1.21.4", CALLS, "calls");
        assertEquals(List.of(HELPER + ".method_5643" + HURT_HELPER, PROPERTY + ".method_11898()Ljava/util/List;"), calls);
    }

    @Test
    @DisplayName("a mob's call to its own old damage method is left alone")
    void ownDamageMethodKeepsItsCall() {
        assertEquals(List.of(OWN_MOB + ".method_5643" + HURT), transform("1.21.4", OWN_MOB, "selfDamage"));
    }

    @Test
    @DisplayName("a call through LivingEntity runs a mob's own old damage method")
    void callThroughLivingEntityRunsTheOverride() throws Throwable {
        Run run = new Run("1.21.4");
        Object source = run.newInstance(DAMAGE_SOURCE);

        Object living = run.newInstance(LIVING);
        assertEquals(true, run.call(CALLS, "hit", null, living, source));
        assertArrayEquals(new Object[] {source, 2f}, (Object[]) run.field(living, "lastHurt"),
                "a host entity takes the damage through method_64420");

        Object mob = run.newInstance(OWN_MOB);
        assertEquals(true, run.call(CALLS, "hit", null, mob, source));
        assertEquals(true, run.field(mob, "ran"), "the mob's own damage method ran");
        assertArrayEquals(new Object[] {source, 2f}, (Object[]) run.field(mob, "lastHurt"),
                "its super call reached method_64420");
    }

    @Test
    @DisplayName("a mob's own old damage method links its super call, which names Mob, to the host")
    void overrideSuperCallLinks() throws Throwable {
        Run run = new Run("1.21.4");
        Object mob = run.newInstance(OWN_MOB);
        assertEquals(true, run.call(OWN_MOB, "selfDamage", mob));
        assertEquals(true, run.field(mob, "ran"), "the mob's call on itself ran its own method");
        assertArrayEquals(new Object[] {null, 1f}, (Object[]) run.field(mob, "lastHurt"),
                "the super call reached method_64420");
    }

    @Test
    @DisplayName("on 26.x the Mojang-named bridge owns these calls, so the intermediary redirect stays off")
    void notRegisteredOnUnobfuscatedHosts() {
        assertEquals(List.of(LIVING + ".method_5643" + HURT, INT_PROPERTY + ".method_11898" + VALUES_OLD),
                transform("26.3", CALLS, "calls"));
    }

    private static List<String> transform(String target, String owner, String method) {
        return callsIn(transformAll(target).get(owner), method);
    }

    /** The mod classes, transformed for a Fabric host of version {@code target}. */
    private static Map<String, byte[]> transformAll(String target) {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);
        Map<String, byte[]> mod = Map.of(CALLS, calls(), OWN_MOB, ownDamageMob());
        Map<String, byte[]> classes = new HashMap<>(host);
        classes.putAll(mod);
        Map<String, byte[]> transformed = new HashMap<>();
        try (var scope = transformer.pushJarClassBytesProvider(classes::get)) {
            mod.forEach((name, bytes) -> transformed.put(name, transformer.transformClass(bytes, name + ".class")));
        }
        return transformed;
    }

    /**
     * {@code living.damage(source, 2)} and {@code property.getValues()}, as a 1.21.1 mod compiled
     * them, and {@code hit}, which damages a living entity it is given.
     */
    private static byte[] calls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, CALLS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "calls",
                "(L" + LIVING + ";L" + INT_PROPERTY + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ACONST_NULL);
        mv.visitLdcInsn(2f);
        mv.visitMethodInsn(INVOKEVIRTUAL, LIVING, "method_5643", HURT, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, INT_PROPERTY, "method_11898", VALUES_OLD, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        MethodVisitor hit = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "hit",
                "(L" + LIVING + ";L" + DAMAGE_SOURCE + ";)Z", null, null);
        hit.visitCode();
        hit.visitVarInsn(ALOAD, 0);
        hit.visitVarInsn(ALOAD, 1);
        hit.visitLdcInsn(2f);
        hit.visitMethodInsn(INVOKEVIRTUAL, LIVING, "method_5643", HURT, false);
        hit.visitInsn(IRETURN);
        hit.visitMaxs(0, 0);
        hit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A mob that overrides the old damage method and ends with {@code super.damage(source, amount)},
     * which javac names after its direct superclass {@code Mob}, and calls the method on itself.
     */
    private static byte[] ownDamageMob() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, OWN_MOB, null, MOB, null);
        cw.visitField(ACC_PUBLIC, "ran", "Z", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, MOB, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor damage = cw.visitMethod(ACC_PUBLIC, "method_5643", HURT, null, null);
        damage.visitCode();
        damage.visitVarInsn(ALOAD, 0);
        damage.visitInsn(ICONST_1);
        damage.visitFieldInsn(PUTFIELD, OWN_MOB, "ran", "Z");
        damage.visitVarInsn(ALOAD, 0);
        damage.visitVarInsn(ALOAD, 1);
        damage.visitVarInsn(FLOAD, 2);
        damage.visitMethodInsn(INVOKESPECIAL, MOB, "method_5643", HURT, false);
        damage.visitInsn(IRETURN);
        damage.visitMaxs(0, 0);
        damage.visitEnd();
        MethodVisitor self = cw.visitMethod(ACC_PUBLIC, "selfDamage", "()Z", null, null);
        self.visitCode();
        self.visitVarInsn(ALOAD, 0);
        self.visitInsn(ACONST_NULL);
        self.visitInsn(FCONST_1);
        self.visitMethodInsn(INVOKEVIRTUAL, OWN_MOB, "method_5643", HURT, false);
        self.visitInsn(IRETURN);
        self.visitMaxs(0, 0);
        self.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The transformed mod classes, Retromod's generated helpers and the stand-ins in one loader. */
    private static final class Run extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        Run(String target) {
            super(Fabric1212IntermediaryMemberTest.class.getClassLoader());
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

        /** Calls {@code owner.name} on {@code receiver}, or statically when it is null. */
        Object call(String owner, String name, Object receiver, Object... args) throws Throwable {
            for (Method method : loadClass(owner.replace('/', '.')).getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    try {
                        return method.invoke(receiver, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
            throw new AssertionError("no method " + owner + "." + name);
        }

        Object field(Object owner, String name) throws Exception {
            return owner.getClass().getField(name).get(owner);
        }
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
        assertNotNull(compiler, "the tests need a JDK to compile the 1.21.4 stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the 1.21.4 stand-ins must compile");
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
