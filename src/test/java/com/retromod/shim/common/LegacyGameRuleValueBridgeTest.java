/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
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

/** Old game rule values ({@code getRule(key).get()}, {@code .set(value, server)}) read and write a 26.x host's typed rules. */
class LegacyGameRuleValueBridgeTest {

    private static final String RULES = LegacyGameRuleValueBridge.RULES;
    private static final String RULE = LegacyGameRuleValueBridge.RULE;
    private static final String SERVER = LegacyGameRuleValueBridge.SERVER;
    private static final String OLD = LegacyGameRuleValueBridge.OLD;
    private static final String MOD = "mod/OldRules";

    @TempDir
    static Path stubDir;

    private static Map<String, byte[]> host;

    @BeforeAll
    static void compileHost() throws Exception {
        host = compile(Map.of(
                "com/mojang/serialization/DataResult.java", """
                        package com.mojang.serialization;
                        public interface DataResult<T> { java.util.Optional<T> result(); }""",
                "net/minecraft/server/MinecraftServer.java", """
                        package net.minecraft.server;
                        import net.minecraft.world.level.gamerules.GameRule;
                        public class MinecraftServer {
                            public Object changedRule;
                            public Object changedValue;
                            public <T> void onGameRuleChanged(GameRule<T> rule, T value) { changedRule = rule; changedValue = value; }
                        }""",
                "net/minecraft/world/level/gamerules/GameRule.java", """
                        package net.minecraft.world.level.gamerules;
                        import com.mojang.serialization.DataResult;
                        import java.util.Optional;
                        public final class GameRule<T> {
                            private final Class<T> type;
                            private final T defaultValue;
                            private final String id;
                            GameRule(String id, Class<T> type, T defaultValue) { this.id = id; this.type = type; this.defaultValue = defaultValue; }
                            public String id() { return id; }
                            public Class<T> valueClass() { return type; }
                            public T defaultValue() { return defaultValue; }
                            public int getCommandResult(T value) { return value instanceof Integer i ? i : 1; }
                            public DataResult<T> deserialize(String text) {
                                try {
                                    Object parsed = type == Integer.class ? (Object) Integer.valueOf(text) : (Object) Boolean.valueOf(text);
                                    return () -> Optional.of(type.cast(parsed));
                                } catch (NumberFormatException e) {
                                    return Optional::empty;
                                }
                            }
                        }""",
                "net/minecraft/world/level/gamerules/GameRules.java", """
                        package net.minecraft.world.level.gamerules;
                        import net.minecraft.server.MinecraftServer;
                        import java.util.HashMap;
                        import java.util.Map;
                        public class GameRules {
                            public static final GameRule<Boolean> KEEP_INVENTORY = new GameRule<>("keep_inventory", Boolean.class, false);
                            public static final GameRule<Boolean> RAIDS = new GameRule<>("raids", Boolean.class, true);
                            public static final GameRule<Integer> RANDOM_TICK_SPEED = new GameRule<>("random_tick_speed", Integer.class, 3);
                            public static final GameRule<Integer> FIRE_SPREAD_RADIUS_AROUND_PLAYER = new GameRule<>("fire_spread_radius_around_player", Integer.class, 128);
                            private final Map<GameRule<?>, Object> values = new HashMap<>();
                            public <T> T get(GameRule<T> rule) { return rule.valueClass().cast(values.getOrDefault(rule, rule.defaultValue())); }
                            public <T> void set(GameRule<T> rule, T value, MinecraftServer server) {
                                values.put(rule, value);
                                if (server != null) server.onGameRuleChanged(rule, value);
                            }
                            public <T> String getAsString(GameRule<T> rule) { return String.valueOf(get(rule)); }
                        }"""));
    }

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the views and the fire and inverted rules follow the host shape")
    void followsTheHost() {
        LegacyGameRuleValueBridge.Shape shape = LegacyGameRuleValueBridge.shape(this::hostClass);
        assertTrue(shape.views(), "the stand-in host has typed get, set and the rule methods the views call");
        assertTrue(shape.radius());
        assertEquals(List.of("RAIDS"), shape.inverted(), "only the inverted rules the host declares are compared");
        assertTrue(shape.onChanged());
        assertTrue(shape.ruleId());

        LegacyGameRuleValueBridge.Shape none = LegacyGameRuleValueBridge.shape(name -> null);
        assertFalse(none.views() || none.radius() || none.onChanged() || none.ruleId(), "an unreadable host gets nothing");

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyGameRuleValueBridge.register(transformer, none);
        assertNull(transformer.getClassRedirects().get(OLD + "$BooleanValue"));
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyGameRuleValueBridge.VALUE));
    }

    @Test
    @DisplayName("the generated views verify")
    void viewsVerify() {
        for (byte[] view : LegacyGameRuleValueBridge.generate(LegacyGameRuleValueBridge.shape(this::hostClass)).values()) {
            new ClassReader(view).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
        new ClassReader(LegacyGameRuleCallBridge.generate(LegacyGameRuleCallBridge.MOJANG, true, null))
                .accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    @Test
    @DisplayName("old rule values read and write the host's rules, and writes with a server notify it")
    void valuesReadAndWriteTheHost() throws Throwable {
        Run run = transformed();
        Object rules = run.newInstance(RULES);
        Object server = run.newInstance(SERVER);

        assertEquals(false, run.call("keepInventory", rules));
        run.call("keepInventoryOn", rules, server);
        assertEquals(true, run.call("keepInventory", rules), "the write goes to the host's rules");
        assertEquals(true, server.getClass().getField("changedValue").get(server), "the server hears about the change");

        run.call("tickSpeed", rules, server);
        assertEquals("7", run.call("tickSpeedText", rules));
        assertEquals(7, hostValue(run, rules, "RANDOM_TICK_SPEED"));
    }

    @Test
    @DisplayName("the old fire switch reads the radius as on or off, and off stores 0 and on restores the default")
    void fireSwitchUsesTheRadius() throws Throwable {
        Run run = transformed();
        Object rules = run.newInstance(RULES);

        assertEquals(true, run.call("fireTicks", rules), "the default radius spreads fire");
        assertEquals(true, run.call("fireTicksRead", rules));
        run.call("stopFire", rules);
        assertEquals(0, hostValue(run, rules, "FIRE_SPREAD_RADIUS_AROUND_PLAYER"));
        assertEquals(false, run.call("fireTicks", rules));
        assertEquals(false, run.call("fireTicksRead", rules));
        run.call("startFire", rules);
        assertEquals(128, hostValue(run, rules, "FIRE_SPREAD_RADIUS_AROUND_PLAYER"), "turning it on restores the host default");
    }

    @Test
    @DisplayName("the old disable-raids rule reads the positive raids rule negated")
    void invertedRuleReadsNegated() throws Throwable {
        Run run = transformed();
        assertEquals(false, run.call("raidsDisabled", run.newInstance(RULES)));
    }

    @Test
    @DisplayName("two views of the same rule in the same rules are equal, and views of other rules or rules are not")
    void viewsCompareByRulesAndRule() throws Throwable {
        Run run = transformed();
        Object rules = run.newInstance(RULES);
        Object first = run.call("keepInventoryValue", rules);
        Object second = run.call("keepInventoryValue", rules);
        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, run.call("keepInventoryValue", run.newInstance(RULES)), "another world's rules");
        assertNotEquals(first, run.call("tickSpeedValue", rules), "another rule");
    }

    /** The old-shaped mod, transformed with the bridges registered against the stand-in host. */
    private Run transformed() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyGameRuleValueBridge.Shape shape = LegacyGameRuleValueBridge.shape(this::hostClass);
        LegacyGameRuleCallBridge.register(transformer, shape);
        Common_1_21_11_to_26_1_ClassMoves.registerGameRuleFieldRenames(transformer, shape);
        Map<String, byte[]> classes = new HashMap<>(host);
        classes.put(MOD, transformer.transformClass(oldMod(), MOD + ".class"));
        classes.putAll(transformer.getSyntheticClasses());
        return new Run(classes);
    }

    private ClassNode hostClass(String name) {
        byte[] bytes = host.get(name);
        if (bytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static Object hostValue(Run run, Object rules, String field) throws Exception {
        Class<?> owner = run.loadClass(RULES.replace('/', '.'));
        Object rule = owner.getField(field).get(null);
        return owner.getMethod("get", run.loadClass(RULE.replace('/', '.'))).invoke(rules, rule);
    }

    /** A 1.21.1 mod's rule code, after the 26.1 class move: old rule names, getRule, and the old value classes. */
    private static byte[] oldMod() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        String bool = OLD + "$BooleanValue";
        String integer = OLD + "$IntegerValue";

        MethodVisitor mv = start(cw, "keepInventory", "(L" + RULES + ";)Z", "RULE_KEEPINVENTORY", bool);
        mv.visitMethodInsn(INVOKEVIRTUAL, bool, "get", "()Z", false);
        end(mv, IRETURN);

        mv = start(cw, "keepInventoryOn", "(L" + RULES + ";L" + SERVER + ";)V", "RULE_KEEPINVENTORY", bool);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, bool, "set", "(ZL" + SERVER + ";)V", false);
        end(mv, RETURN);

        mv = start(cw, "tickSpeed", "(L" + RULES + ";L" + SERVER + ";)V", "RULE_RANDOMTICKING", integer);
        mv.visitIntInsn(BIPUSH, 7);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, integer, "set", "(IL" + SERVER + ";)V", false);
        end(mv, RETURN);

        mv = start(cw, "tickSpeedText", "(L" + RULES + ";)Ljava/lang/String;", "RULE_RANDOMTICKING", null);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD + "$Value", "serialize", "()Ljava/lang/String;", false);
        end(mv, ARETURN);

        mv = start(cw, "fireTicks", "(L" + RULES + ";)Z", "RULE_DOFIRETICK", bool);
        mv.visitMethodInsn(INVOKEVIRTUAL, bool, "get", "()Z", false);
        end(mv, IRETURN);

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fireTicksRead", "(L" + RULES + ";)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, RULES, "RULE_DOFIRETICK", "L" + RULE + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, RULES, "getBoolean", "(L" + RULE + ";)Z", false);
        end(mv, IRETURN);

        for (String[] toggle : new String[][]{{"stopFire", "0"}, {"startFire", "1"}}) {
            mv = start(cw, toggle[0], "(L" + RULES + ";)V", "RULE_DOFIRETICK", bool);
            mv.visitInsn(toggle[1].equals("1") ? ICONST_1 : ICONST_0);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKEVIRTUAL, bool, "set", "(ZL" + SERVER + ";)V", false);
            end(mv, RETURN);
        }

        for (String[] value : new String[][]{{"keepInventoryValue", "RULE_KEEPINVENTORY"}, {"tickSpeedValue", "RULE_RANDOMTICKING"}}) {
            mv = start(cw, value[0], "(L" + RULES + ";)Ljava/lang/Object;", value[1], null);
            end(mv, ARETURN);
        }

        mv = start(cw, "raidsDisabled", "(L" + RULES + ";)Z", "RULE_DISABLE_RAIDS", bool);
        mv.visitMethodInsn(INVOKEVIRTUAL, bool, "get", "()Z", false);
        end(mv, IRETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Opens a static method that pushes {@code rules.getRule(RULES.<field>)}, cast to {@code cast} when given. */
    private static MethodVisitor start(ClassWriter cw, String name, String desc, String field, String cast) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, RULES, field, "L" + RULE + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, RULES, "getRule", "(L" + RULE + ";)L" + OLD + "$Value;", false);
        if (cast != null) mv.visitTypeInsn(CHECKCAST, cast);
        return mv;
    }

    private static void end(MethodVisitor mv, int returnOpcode) {
        mv.visitInsn(returnOpcode);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The transformed mod class, Retromod's generated classes and the stand-ins in one loader. */
    private static final class Run extends ClassLoader {
        private final Map<String, byte[]> classes;

        Run(Map<String, byte[]> classes) {
            super(LegacyGameRuleValueBridgeTest.class.getClassLoader());
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
        Object call(String name, Object... args) throws Throwable {
            for (Method method : loadClass(MOD.replace('/', '.')).getMethods()) {
                if (method.getName().equals(name)) {
                    try {
                        return method.invoke(null, args);
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
        assertNotNull(compiler, "the tests need a JDK to compile the 26.x stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the 26.x stand-ins must compile");
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
