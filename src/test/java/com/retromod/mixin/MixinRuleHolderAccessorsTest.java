/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Regions Unexplored's and Lithostitched's surface rule accessors move to 26.3's rule holder together with their callers. */
class MixinRuleHolderAccessorsTest {

    private static final String SETTINGS = "net/minecraft/world/level/levelgen/NoiseGeneratorSettings";
    private static final String RULE = "net/minecraft/world/level/levelgen/material/rule/MaterialRule";
    private static final String HOLDER = "net/minecraft/core/Holder";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("the accessors name the rule holder, and the mod's swap of the rule runs against an accessor of the holder")
    void accessorsAndCallersFollowTheHolder() throws Throwable {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Map<String, byte[]> mod = compile(SOURCES);
        ClassNode access = node(mod.get("mod/SettingsAccess"));

        assertTrue(MixinRuleHolderAccessors.retarget(access));

        for (MethodNode method : access.methods) {
            assertEquals("materialRule", value(accessor(method)), method.name);
            assertTrue(method.desc.contains("L" + HOLDER + ";") && !method.desc.contains(RULE), method.name + method.desc);
        }
        byte[] swap = MixinRuleHolderAccessors.rewriteCalls(mod.get("mod/Swap"), mod::get);
        assertNotSame(mod.get("mod/Swap"), swap, "the callers were rewritten");

        // Mixin generates the accessor on the settings; a proxy over the repaired interface stands in for it.
        Map<String, byte[]> runtime = new HashMap<>(mod);
        runtime.put("mod/SettingsAccess", write(access));
        runtime.put("mod/Swap", swap);
        Loader loader = new Loader(runtime);
        Class<?> accessType = loader.loadClass("mod.SettingsAccess");
        Object[] field = {direct(loader, "old rule")};
        Object settings = Proxy.newProxyInstance(loader, new Class<?>[]{accessType}, (proxy, method, args) -> {
            if (args == null) return field[0];
            field[0] = args[0];
            return null;
        });
        Object next = loader.loadClass("mod.Rule").getConstructor(String.class).newInstance("next rule");
        Object old = call(loader, "swap", settings, next);
        assertEquals("old rule", String.valueOf(old), "the getter took the rule out of the holder");
        assertEquals("next rule", String.valueOf(loader.loadClass(HOLDER.replace('/', '.')).getMethod("value").invoke(field[0])),
                "the setter stored the new rule in a holder");
    }

    @Test
    @DisplayName("a Fabric owner, read in intermediary names from the jar, is recognized as the same accessor")
    void intermediaryOwner() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Map<String, byte[]> mod = compile(SOURCES);
        Map<String, byte[]> jar = new HashMap<>(mod);
        jar.put("mod/SettingsAccess", intermediaryAccess());
        byte[] swap = MixinRuleHolderAccessors.rewriteCalls(mod.get("mod/Swap"), jar::get);
        List<String> calls = calls(node(swap), "swap");
        assertTrue(calls.contains(HOLDER + ".direct(Ljava/lang/Object;)L" + HOLDER + ";"), calls.toString());
        assertTrue(calls.contains("mod/SettingsAccess.mod$setSurfaceRule(L" + HOLDER + ";)V"), calls.toString());
    }

    @Test
    @DisplayName("a 26.2 host keeps surfaceRule, so the accessors and their callers stay as they were")
    void olderHostLeftAlone() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        Map<String, byte[]> mod = compile(SOURCES);
        assertFalse(MixinRuleHolderAccessors.retarget(node(mod.get("mod/SettingsAccess"))));
        assertSame(mod.get("mod/Swap"), MixinRuleHolderAccessors.rewriteCalls(mod.get("mod/Swap"), mod::get));
    }

    @Test
    @DisplayName("another accessor of the settings, and a method of the same shape on another class, are left alone")
    void unrelatedMembersLeftAlone() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Map<String, String> sources = new HashMap<>(SOURCES);
        sources.put("mod/SettingsAccess.java", """
                package mod;
                import org.spongepowered.asm.mixin.Mixin;
                import org.spongepowered.asm.mixin.gen.Accessor;
                import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
                import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
                @Mixin(NoiseGeneratorSettings.class)
                public interface SettingsAccess {
                    @Accessor("defaultRule") void mod$setSurfaceRule(MaterialRule rule);
                    @Accessor("defaultRule") MaterialRule mod$getRule();
                    @Accessor("defaultRule") MaterialRule getSurfaceRule();
                }""");
        Map<String, byte[]> mod = compile(sources);
        assertFalse(MixinRuleHolderAccessors.retarget(node(mod.get("mod/SettingsAccess"))));
        assertSame(mod.get("mod/Swap"), MixinRuleHolderAccessors.rewriteCalls(mod.get("mod/Swap"), mod::get));
    }

    /** Annotation stand-ins, the post-translation host types, and the mod: its accessor mixin and the code that swaps the rule. */
    private static final Map<String, String> SOURCES = Map.of(
            "org/spongepowered/asm/mixin/Mixin.java", """
                    package org.spongepowered.asm.mixin;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.CLASS) public @interface Mixin { Class<?>[] value(); }""",
            "org/spongepowered/asm/mixin/Mutable.java", """
                    package org.spongepowered.asm.mixin;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.CLASS) public @interface Mutable {}""",
            "org/spongepowered/asm/mixin/gen/Accessor.java", """
                    package org.spongepowered.asm.mixin.gen;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.CLASS) public @interface Accessor { String value() default ""; }""",
            "net/minecraft/core/Holder.java", """
                    package net.minecraft.core;
                    public interface Holder<T> {
                        T value();
                        static <T> Holder<T> direct(T value) { return () -> value; }
                    }""",
            "net/minecraft/world/level/levelgen/material/rule/MaterialRule.java",
            "package net.minecraft.world.level.levelgen.material.rule; public interface MaterialRule {}",
            "net/minecraft/world/level/levelgen/NoiseGeneratorSettings.java",
            "package net.minecraft.world.level.levelgen; public final class NoiseGeneratorSettings {}",
            "mod/Rule.java", """
                    package mod;
                    public record Rule(String name) implements net.minecraft.world.level.levelgen.material.rule.MaterialRule {
                        @Override public String toString() { return name; }
                    }""",
            "mod/SettingsAccess.java", """
                    package mod;
                    import org.spongepowered.asm.mixin.Mixin;
                    import org.spongepowered.asm.mixin.Mutable;
                    import org.spongepowered.asm.mixin.gen.Accessor;
                    import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
                    import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
                    @Mixin(NoiseGeneratorSettings.class)
                    public interface SettingsAccess {
                        @Accessor("surfaceRule") @Mutable void mod$setSurfaceRule(MaterialRule rule);
                        @Accessor("surfaceRule") MaterialRule mod$getRule();
                        @Accessor MaterialRule getSurfaceRule();
                    }""",
            "mod/Swap.java", """
                    package mod;
                    import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
                    public final class Swap {
                        public static MaterialRule swap(Object settings, MaterialRule next) {
                            SettingsAccess access = (SettingsAccess) settings;
                            MaterialRule old = access.mod$getRule();
                            if (old == null) return null;
                            access.mod$setSurfaceRule(next);
                            return access.getSurfaceRule() == next ? old : null;
                        }
                    }""");

    /** The accessor as a Fabric jar carries it before the remap: intermediary target, field and rule type. */
    private static byte[] intermediaryAccess() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, "mod/SettingsAccess", null,
                "java/lang/Object", null);
        var mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitArray("value");
        mixin.visit(null, Type.getObjectType("net/minecraft/class_5284"));
        mixin.visitEnd();
        var setter = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod$setSurfaceRule",
                "(Lnet/minecraft/class_6686$class_6708;)V", null, null);
        setter.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", false).visit("value", "comp_478");
        setter.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Object direct(Loader loader, String name) throws Exception {
        Object rule = loader.loadClass("mod.Rule").getConstructor(String.class).newInstance(name);
        return loader.loadClass(HOLDER.replace('/', '.')).getMethod("direct", Object.class).invoke(null, rule);
    }

    private static Object call(Loader loader, String name, Object... args) throws Throwable {
        for (Method method : loader.loadClass("mod.Swap").getMethods()) {
            if (method.getName().equals(name)) {
                try {
                    return method.invoke(null, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new AssertionError("no method " + name);
    }

    private Map<String, byte[]> compile(Map<String, String> sources) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src-" + System.nanoTime()));
        Path out = Files.createDirectories(dir.resolve("out-" + System.nanoTime()));
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test needs a JDK to compile the stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static AnnotationNode accessor(MethodNode method) {
        List<AnnotationNode> all = new ArrayList<>();
        if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
        return all.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")).findFirst().orElseThrow();
    }

    private static Object value(AnnotationNode annotation) {
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if ("value".equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static List<String> calls(ClassNode node, String method) {
        List<String> calls = new ArrayList<>();
        MethodNode found = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
        for (var insn : found.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    /** Loads the given classes ahead of the test's own, so the JVM verifies the rewritten frames. */
    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes;

        Loader(Map<String, byte[]> classes) {
            super(MixinRuleHolderAccessorsTest.class.getClassLoader());
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
    }
}
