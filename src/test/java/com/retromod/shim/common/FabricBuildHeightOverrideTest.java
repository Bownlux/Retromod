/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mapping.IntermediaryToMojangMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A Fabric mod built for 1.21.1 that overrides the exclusive max build height getter itself, run
 * against small stand-ins for the host's height accessor and level. The old exclusive value comes
 * back once through every call, and the host's own inclusive getter answers one lower.
 */
class FabricBuildHeightOverrideTest {

    // The intermediary names the mod carries.
    private static final String I_HEIGHT = "net/minecraft/class_5539";
    private static final String I_LEVEL = "net/minecraft/class_1937";
    private static final String MIN_ID = "method_31607";
    private static final String HEIGHT_ID = "method_31605";
    private static final String MAX_ID = "method_31600";
    private static final String MAX_SECTION_ID = "method_31597";

    private final String savedHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.3", "1.21.11"})
    @DisplayName("an own override answers its exclusive value through the host types and its own class, once")
    void ownOverrideKeepsItsMeaning(String host) throws Exception {
        boolean mojang = host.startsWith("26");
        Names names = mojang
                ? new Names("net/minecraft/world/level/LevelHeightAccessor", "net/minecraft/world/level/Level",
                        "getMinY", "getHeight", "getMaxY", "getMaxSectionY")
                : new Names(I_HEIGHT, I_LEVEL, MIN_ID, HEIGHT_ID, MAX_ID, MAX_SECTION_ID);
        RetromodVersion.TARGET_MC_VERSION = host;
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        if (mojang) IntermediaryToMojangMapper.applyTo(transformer);
        LegacyBuildHeightBridge.registerFabric(transformer);
        transformer.setModSourceVersion("1.21.1");

        // The mod: a flat world whose override returns min + height, one that adds to its super's
        // answer, and a class that calls the getters through each type a mod names.
        Map<String, byte[]> jar = new HashMap<>();
        jar.put("mod/FlatWorld", flatWorld());
        jar.put("mod/RaisedWorld", raisedWorld());
        jar.put("mod/Calls", calls());
        Map<String, byte[]> loaded = new HashMap<>(hostClasses(names));
        try (var scope = transformer.pushJarClassBytesProvider(jar::get)) {
            for (Map.Entry<String, byte[]> entry : jar.entrySet()) {
                loaded.put(entry.getKey(), transformer.transformClass(entry.getValue(), entry.getKey()));
            }
        }
        loaded.putAll(transformer.getSyntheticClasses());
        ClassLoader loader = new MapLoader(loaded);
        Class<?> calls = loader.loadClass("mod.Calls");
        Object flat = loader.loadClass("mod.FlatWorld").getConstructor().newInstance();
        Object raised = loader.loadClass("mod.RaisedWorld").getConstructor().newInstance();

        // Old exclusive values, once each: 0 + 16 for the flat world, and its super's 16 plus 2.
        assertEquals(16, call(calls, "viaLevel", flat), "through Level");
        assertEquals(16, call(calls, "viaAccessor", flat), "through LevelHeightAccessor");
        assertEquals(16, call(calls, "viaOwn", flat), "through the mod's own class");
        assertEquals(18, call(calls, "viaLevel", raised), "an override that adds to its super call");
        assertEquals(1, call(calls, "sectionViaLevel", flat), "the old exclusive section count");
        // The host reads the inclusive top from the mod's object.
        Method hostTop = loader.loadClass(names.level().replace('/', '.')).getMethod("hostTop");
        assertEquals(15, hostTop.invoke(flat), "the host's inclusive getter on the mod's world");
        assertEquals(17, hostTop.invoke(raised), "the host's inclusive getter on the raised world");
    }

    private static int call(Class<?> calls, String name, Object world) throws Exception {
        for (Method method : calls.getMethods()) {
            if (method.getName().equals(name)) return (Integer) method.invoke(null, world);
        }
        throw new AssertionError("no " + name);
    }

    /** The host's accessor and level in one namespace's names. */
    private record Names(String height, String level, String minY, String heightName, String maxY,
            String maxSectionY) {}

    /** An accessor whose inclusive getters default from the floor and height, and a level of height 16 from 0. */
    private static Map<String, byte[]> hostClasses(Names names) {
        ClassWriter accessor = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        accessor.visit(V21, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, names.height(), null, "java/lang/Object", null);
        accessor.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, names.minY(), "()I", null, null).visitEnd();
        accessor.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, names.heightName(), "()I", null, null).visitEnd();
        MethodVisitor mv = accessor.visitMethod(ACC_PUBLIC, names.maxY(), "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, names.height(), names.minY(), "()I", true);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, names.height(), names.heightName(), "()I", true);
        mv.visitInsn(IADD);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = accessor.visitMethod(ACC_PUBLIC, names.maxSectionY(), "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, names.height(), names.maxY(), "()I", true);
        mv.visitInsn(ICONST_4);
        mv.visitInsn(ISHR);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        accessor.visitEnd();

        ClassWriter level = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        level.visit(V21, ACC_PUBLIC | ACC_ABSTRACT, names.level(), null, "java/lang/Object",
                new String[]{names.height()});
        constructor(level, "java/lang/Object");
        constant(level, names.minY(), 0);
        constant(level, names.heightName(), 16);
        mv = level.visitMethod(ACC_PUBLIC, "hostTop", "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, names.level(), names.maxY(), "()I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        level.visitEnd();
        return Map.of(names.height(), accessor.toByteArray(), names.level(), level.toByteArray());
    }

    /** {@code class FlatWorld extends Level}: the old getters, exclusive, in intermediary names. */
    private static byte[] flatWorld() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/FlatWorld", null, I_LEVEL, null);
        constructor(cw, I_LEVEL);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, MAX_ID, "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "mod/FlatWorld", MIN_ID, "()I", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "mod/FlatWorld", HEIGHT_ID, "()I", false);
        mv.visitInsn(IADD);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(ACC_PUBLIC, MAX_SECTION_ID, "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "mod/FlatWorld", MAX_ID, "()I", false);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitInsn(ICONST_4);
        mv.visitInsn(ISHR);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class RaisedWorld extends Level}: {@code getMaxBuildHeight()} is {@code super.getMaxBuildHeight() + 2}. */
    private static byte[] raisedWorld() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/RaisedWorld", null, I_LEVEL, null);
        constructor(cw, I_LEVEL);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, MAX_ID, "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, I_LEVEL, MAX_ID, "()I", false);
        mv.visitInsn(ICONST_2);
        mv.visitInsn(IADD);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Static calls to the old getters through Level, the accessor, and the mod's own class. */
    private static byte[] calls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Calls", null, "java/lang/Object", null);
        caller(cw, "viaLevel", "Ljava/lang/Object;", I_LEVEL, MAX_ID, INVOKEVIRTUAL);
        caller(cw, "viaAccessor", "Ljava/lang/Object;", I_HEIGHT, MAX_ID, INVOKEINTERFACE);
        caller(cw, "viaOwn", "Ljava/lang/Object;", "mod/FlatWorld", MAX_ID, INVOKEVIRTUAL);
        caller(cw, "sectionViaLevel", "Ljava/lang/Object;", I_LEVEL, MAX_SECTION_ID, INVOKEVIRTUAL);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void caller(ClassWriter cw, String name, String param, String owner, String getter, int opcode) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(" + param + ")I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(CHECKCAST, owner);
        mv.visitMethodInsn(opcode, owner, getter, "()I", opcode == INVOKEINTERFACE);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void constructor(ClassWriter cw, String superName) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void constant(ClassWriter cw, String name, int value) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "()I", null, null);
        mv.visitCode();
        mv.visitIntInsn(BIPUSH, value);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Defines the given classes itself, ahead of the test class path. */
    private static final class MapLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        MapLoader(Map<String, byte[]> classes) {
            super(FabricBuildHeightOverrideTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes == null) return super.loadClass(name, resolve);
                    found = defineClass(name, bytes, 0, bytes.length);
                }
                return found;
            }
        }
    }
}
