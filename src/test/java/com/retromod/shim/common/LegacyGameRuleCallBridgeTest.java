/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.Fabric_1_21_10_to_1_21_11;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

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

/** #366: ProjectE reads {@code level.getGameRules().getBoolean(rule)}, which 26.x answers only on a server level with {@code get(rule)}. */
class LegacyGameRuleCallBridgeTest {

    private static final LegacyGameRuleCallBridge.Names INTERMEDIARY = LegacyGameRuleCallBridge.INTERMEDIARY;
    private static final String RULES = "net/minecraft/class_1928";
    private static final String OLD_KEY = "net/minecraft/class_1928$class_4313";
    private static final String RULE = "net/minecraft/class_12279";
    private static final String LEVEL = "net/minecraft/class_1937";
    private static final String SERVER_LEVEL = "net/minecraft/class_3218";
    private static final String CLIENT_LEVEL = "net/minecraft/class_638";
    private static final String MOD = "mod/AppleRules";

    @TempDir
    static Path stubDir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Level game rules and typed rule reads reach the generated helpers")
    void ruleReadsAreRedirected() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyGameRuleCallBridge.register(transformer);

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(reader(), "mod/Rules.class")).accept(node, 0);

        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        String helper = LegacyGameRuleCallBridge.CALLS;
        assertEquals(List.of(helper + ".gameRules", helper + ".getBoolean"), calls);
    }

    @Test
    @DisplayName("The generated helper verifies")
    void generatedHelperVerifies() {
        new ClassReader(LegacyGameRuleCallBridge.generate()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    @Test
    @DisplayName("a Mojang-named client level's game rules reach the helper too")
    void clientLevelCallReachesTheMojangHelper() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyGameRuleCallBridge.register(transformer);
        String clientLevel = LegacyGameRuleCallBridge.MOJANG.clientLevel();
        String rules = "()L" + LegacyGameRuleCallBridge.MOJANG.rules() + ";";
        byte[] mod = caller("mod/ClientRules", new String[][]{{clientLevel, "getGameRules", rules}});

        assertEquals(List.of(LegacyGameRuleCallBridge.CALLS + ".gameRules(Lnet/minecraft/world/level/Level;)"
                + rules.substring(2)), calls(transformer.transformClass(mod, "mod/ClientRules.class")));
    }

    @Test
    @DisplayName("a 1.21.1 Fabric mod's rule reads reach the intermediary helper on a Fabric 1.21.11 host")
    void intermediaryRuleReadsAreRedirected() {
        byte[] transformed = registered("1.21.11").transformClass(appleSkinShape(), MOD + ".class");

        String helper = INTERMEDIARY.helper();
        String gameRules = helper + ".gameRules(L" + LEVEL + ";)L" + RULES + ";";
        assertEquals(List.of(
                gameRules, helper + ".getBoolean(L" + RULES + ";L" + RULE + ";)Z",
                SERVER_LEVEL + ".method_64395()L" + RULES + ";", helper + ".getBoolean(L" + RULES + ";L" + RULE + ";)Z",
                gameRules, helper + ".getInt(L" + RULES + ";L" + RULE + ";)I"), calls(transformed));
        for (String field : fieldDescriptors(transformed)) {
            assertEquals("L" + RULE + ";", field, "the rule fields take the 1.21.11 rule type");
        }
    }

    @Test
    @DisplayName("the intermediary helper verifies")
    void generatedIntermediaryHelperVerifies() {
        new ClassReader(LegacyGameRuleCallBridge.generate(INTERMEDIARY))
                .accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    @Test
    @DisplayName("getBoolean compares the rule against the three rules 1.21.11 turned positive")
    void invertedRulesAreNegated() {
        assertEquals(List.of(RULES + ".field_19404", RULES + ".field_54909", RULES + ".field_19422"),
                comparedRules(LegacyGameRuleCallBridge.generate(INTERMEDIARY), "getBoolean"));
        String mojangRules = LegacyGameRuleCallBridge.MOJANG.rules();
        assertEquals(List.of(mojangRules + ".ELYTRA_MOVEMENT_CHECK", mojangRules + ".PLAYER_MOVEMENT_CHECK",
                mojangRules + ".RAIDS"), comparedRules(LegacyGameRuleCallBridge.generate(), "getBoolean"));
        assertEquals(List.of(), comparedRules(LegacyGameRuleCallBridge.generate(), "getInt"));
    }

    @Test
    @DisplayName("the old reads answer from the host's rules, with the inverted rules negated")
    void intermediaryReadsRunOnTheHostShape() throws Throwable {
        Map<String, byte[]> classes = compileHost();
        RetromodTransformer transformer = registered("1.21.11");
        classes.put(MOD, transformer.transformClass(appleSkinShape(), MOD + ".class"));
        classes.putAll(transformer.getSyntheticClasses());
        Run run = new Run(classes);

        Object server = run.newInstance(SERVER_LEVEL);
        assertEquals(true, run.call("regenerates", server), "a server level reads its own rules");
        assertEquals(false, run.call("raidsDisabled", server), "raids are on, so the old DISABLE_RAIDS reads false");
        assertEquals(3, run.call("tickSpeed", run.newInstance(CLIENT_LEVEL)), "a client level reads the defaults");
    }

    @Test
    @DisplayName("the old rule key type is redirected on a Fabric 1.21.11 host and not on 26.3")
    void hostGate() {
        assertEquals(RULE, registered("1.21.11").getClassRedirects().get(OLD_KEY));
        assertNull(registered("26.3").getClassRedirects().get(OLD_KEY),
                "26.x remaps the mod to Mojang names, where the Mojang-named bridge owns these reads");
    }

    /** The real Fabric 1.21.11 shim, registered for a host of version {@code target}. */
    private static RetromodTransformer registered(String target) {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_10_to_1_21_11().registerRedirects(transformer);
        return transformer;
    }

    /** The 1.21.1 shape, after the 26.1 class move. */
    private static byte[] reader() {
        String rules = "net/minecraft/world/level/gamerules/GameRules";
        String rule = "net/minecraft/world/level/gamerules/GameRule";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Rules", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "decay", "(Lnet/minecraft/world/level/Level;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/Level", "getGameRules", "()L" + rules + ";", false);
        mv.visitFieldInsn(GETSTATIC, rules, "TNT_EXPLOSION_DROP_DECAY", "L" + rule + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, rules, "getBoolean", "(L" + rule + ";)Z", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * AppleSkin's read, {@code level.getGameRules().getBoolean(NATURAL_REGENERATION)}, as a 1.21.1
     * Fabric mod compiled it, plus the same read through a server level and an int read through a
     * client level.
     */
    private static byte[] appleSkinShape() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        read(cw, "regenerates", LEVEL, "field_19395", "method_8355", "Z");
        read(cw, "raidsDisabled", SERVER_LEVEL, "field_19422", "method_8355", "Z");
        read(cw, "tickSpeed", CLIENT_LEVEL, "field_19399", "method_8356", "I");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void read(ClassWriter cw, String name, String level, String rule, String read, String result) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + level + ";)" + result, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, level, "method_8450", "()L" + RULES + ";", false);
        mv.visitFieldInsn(GETSTATIC, RULES, rule, "L" + OLD_KEY + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, RULES, read, "(L" + OLD_KEY + ";)" + result, false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** One static method per {@code {owner, name, desc}} that calls it on its argument and returns. */
    private static byte[] caller(String name, String[][] calls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, name, null, "java/lang/Object", null);
        for (int i = 0; i < calls.length; i++) {
            String[] call = calls[i];
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call" + i, "(L" + call[0] + ";)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, call[0], call[1], call[2], false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Every call in the class, in method order. */
    private static List<String> calls(byte[] bytes) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    private static List<String> fieldDescriptors(byte[] bytes) {
        List<String> fields = new ArrayList<>();
        for (MethodNode method : node(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode field) fields.add(field.desc);
            }
        }
        return fields;
    }

    /** The static rule fields one helper method compares its rule against. */
    private static List<String> comparedRules(byte[] helper, String name) {
        MethodNode method = node(helper).methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
        List<String> rules = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC
                    && field.getNext().getOpcode() == IF_ACMPEQ) {
                rules.add(field.owner + "." + field.name);
            }
        }
        return rules;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Stand-ins with the 1.21.11 intermediary shape: every boolean rule is on, and the tick speed is 3. */
    private static Map<String, byte[]> compileHost() throws Exception {
        return compile(Map.of(
                "net/minecraft/class_7699.java", """
                        package net.minecraft;
                        public class class_7699 {}""",
                "net/minecraft/class_12279.java", """
                        package net.minecraft;
                        public final class class_12279<T> {
                            final T value;
                            class_12279(T value) { this.value = value; }
                        }""",
                "net/minecraft/class_1928.java", """
                        package net.minecraft;
                        public class class_1928 {
                            public static final class_12279<Boolean> field_19395 = new class_12279<>(true);
                            public static final class_12279<Boolean> field_19404 = new class_12279<>(true);
                            public static final class_12279<Boolean> field_19422 = new class_12279<>(true);
                            public static final class_12279<Boolean> field_54909 = new class_12279<>(true);
                            public static final class_12279<Integer> field_19399 = new class_12279<>(3);
                            public class_1928(class_7699 features) {}
                            public <T> T method_76185(class_12279<T> rule) { return rule.value; }
                        }""",
                "net/minecraft/class_1937.java", """
                        package net.minecraft;
                        public abstract class class_1937 {
                            public class_7699 method_45162() { return new class_7699(); }
                        }""",
                "net/minecraft/class_3218.java", """
                        package net.minecraft;
                        public class class_3218 extends class_1937 {
                            private final class_1928 rules = new class_1928(null);
                            public class_1928 method_64395() { return rules; }
                        }""",
                "net/minecraft/class_638.java", """
                        package net.minecraft;
                        public class class_638 extends class_1937 {}"""));
    }

    /** The transformed mod class, Retromod's generated helpers and the stand-ins in one loader. */
    private static final class Run extends ClassLoader {
        private final Map<String, byte[]> classes;

        Run(Map<String, byte[]> classes) {
            super(LegacyGameRuleCallBridgeTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) return super.loadClass(name, resolve);
            return defineClass(name, bytes, 0, bytes.length);
        }

        Object newInstance(String internalName) throws Exception {
            return loadClass(internalName.replace('/', '.')).getConstructor().newInstance();
        }

        /** Calls the mod's static method {@code name}. */
        Object call(String name, Object level) throws Throwable {
            for (Method method : loadClass(MOD.replace('/', '.')).getMethods()) {
                if (method.getName().equals(name)) {
                    try {
                        return method.invoke(null, level);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
            throw new AssertionError("no method " + MOD + "." + name);
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
}
