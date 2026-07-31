/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.bridge;

import com.retromod.core.bridge.BridgeMethodSynthesizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BridgeMethodSynthesizer}.
 *
 * <p>Covers the override-orphan bridge case: a mod class extends an MC class
 * and declares a method that used to override the parent; the parent method
 * got renamed; without a bridge the mod's override is orphaned.</p>
 */
class BridgeMethodSynthesizerTest {

    @Test
    @DisplayName("Bridge method is added when mod class overrides renamed MC method")
    void bridgeAddedForOrphanedOverride() {
        // Setup: MC renamed Entity.getWorld() → Entity.getLevel()
        Function<String, String> lookup = BridgeMethodSynthesizer.buildLookupFrom(
                Map.of("net/minecraft/Entity#getWorld ()Ljava/lang/Object;", "getLevel"),
                k -> k,
                v -> v);

        BridgeMethodSynthesizer synthesizer = new BridgeMethodSynthesizer(lookup);

        // Mod class extends Entity, declares getWorld()
        byte[] modClass = modClassExtending(
                "com/example/mod/MyEntity", "net/minecraft/Entity",
                "getWorld", "()Ljava/lang/Object;");

        Set<String> modOwnClasses = new HashSet<>();
        modOwnClasses.add("com/example/mod/MyEntity");

        byte[] result = synthesizer.synthesize(modClass, modOwnClasses);
        assertNotSame(modClass, result,
                "Synthesizer should have emitted at least one bridge");

        assertEquals(1, synthesizer.getBridgesSynthesized());
        assertEquals(1, synthesizer.getClassesModified());

        // Verify the bridge method is present on the modified class
        Set<String> methodNames = collectMethodNames(result);
        assertTrue(methodNames.contains("getWorld"),
                "Original method must be preserved");
        assertTrue(methodNames.contains("getLevel"),
                "Bridge method with the new name must be present");
    }

    @Test
    @DisplayName("Bridge is skipped when target name already exists on the class")
    void bridgeSkippedOnCollision() {
        Function<String, String> lookup = BridgeMethodSynthesizer.buildLookupFrom(
                Map.of("net/minecraft/Entity#getWorld ()Ljava/lang/Object;", "getLevel"),
                k -> k,
                v -> v);

        BridgeMethodSynthesizer synthesizer = new BridgeMethodSynthesizer(lookup);

        // Mod class has BOTH methods already, so it shouldn't synthesize (would duplicate)
        byte[] modClass = modClassWithTwoMethods(
                "com/example/mod/MyEntity", "net/minecraft/Entity",
                "getWorld", "()Ljava/lang/Object;",
                "getLevel", "()Ljava/lang/Object;");

        byte[] result = synthesizer.synthesize(modClass, Set.of("com/example/mod/MyEntity"));
        // Either same reference (no modification) or equal bytes, both fine.
        // But no bridge should have been recorded.
        assertEquals(0, synthesizer.getBridgesSynthesized(),
                "No bridge should fire when the target name already exists");
        assertEquals(1, synthesizer.getBridgesSkippedCollision(),
                "Collision counter should have incremented");
    }

    @Test
    @DisplayName("Non-mod classes are never touched")
    void nonModClassesUntouched() {
        Function<String, String> lookup = BridgeMethodSynthesizer.buildLookupFrom(
                Map.of("net/minecraft/Entity#getWorld ()Ljava/lang/Object;", "getLevel"),
                k -> k,
                v -> v);

        BridgeMethodSynthesizer synthesizer = new BridgeMethodSynthesizer(lookup);

        byte[] modClass = modClassExtending(
                "com/example/mod/MyEntity", "net/minecraft/Entity",
                "getWorld", "()Ljava/lang/Object;");

        // modOwnClasses does not contain the class, so it's treated as not-mod-owned
        byte[] result = synthesizer.synthesize(modClass, Set.of("com/example/other/Different"));
        assertSame(modClass, result,
                "Non-mod class must not be modified");
        assertEquals(0, synthesizer.getBridgesSynthesized());
    }

    @Test
    @DisplayName("Private and static methods are never bridged")
    void privateAndStaticMethodsSkipped() {
        Function<String, String> lookup = BridgeMethodSynthesizer.buildLookupFrom(
                Map.of("net/minecraft/Entity#getWorld ()Ljava/lang/Object;", "getLevel"),
                k -> k,
                v -> v);
        BridgeMethodSynthesizer synthesizer = new BridgeMethodSynthesizer(lookup);

        // Private methods never participate in virtual dispatch
        byte[] modClassPrivate = modClassWithVisibility(
                "com/example/mod/MyEntity", "net/minecraft/Entity",
                "getWorld", "()Ljava/lang/Object;",
                Opcodes.ACC_PRIVATE);
        synthesizer.synthesize(modClassPrivate, Set.of("com/example/mod/MyEntity"));
        assertEquals(0, synthesizer.getBridgesSynthesized(),
                "Private methods shouldn't trigger bridging");

        // Static methods are resolved statically, no override scenario
        synthesizer.resetMetrics();
        byte[] modClassStatic = modClassWithVisibility(
                "com/example/mod/MyEntity", "net/minecraft/Entity",
                "getWorld", "()Ljava/lang/Object;",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        synthesizer.synthesize(modClassStatic, Set.of("com/example/mod/MyEntity"));
        assertEquals(0, synthesizer.getBridgesSynthesized(),
                "Static methods shouldn't trigger bridging");
    }

    @Test
    @DisplayName("Methods without matching rename are left alone")
    void nonMatchingMethodLeftAlone() {
        // Rename table is empty, so no method should be bridged.
        // The explicit type witness `<String,String>` is needed because Map.of()
        // with no args would otherwise infer as Map<Object,Object>.
        Function<String, String> lookup = BridgeMethodSynthesizer.<String, String>buildLookupFrom(
                Map.<String, String>of(), k -> k, v -> v);
        BridgeMethodSynthesizer synthesizer = new BridgeMethodSynthesizer(lookup);

        byte[] modClass = modClassExtending(
                "com/example/mod/MyEntity", "net/minecraft/Entity",
                "getWorld", "()Ljava/lang/Object;");

        byte[] result = synthesizer.synthesize(modClass, Set.of("com/example/mod/MyEntity"));
        assertSame(modClass, result, "No rename = no modification");
    }

    // Test helpers

    /**
     * Produce a class that extends {@code superName} and declares one public
     * instance method with the given name/descriptor. Method body returns null
     * (for Object-returning descriptors).
     */
    private static byte[] modClassExtending(String className, String superName,
                                             String methodName, String methodDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null, superName, null);

        // Default constructor
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, methodDesc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] modClassWithVisibility(String className, String superName,
                                                  String methodName, String methodDesc,
                                                  int access) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null, superName, null);

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor mv = cw.visitMethod(access, methodName, methodDesc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class with BOTH method names already declared (collision case). */
    private static byte[] modClassWithTwoMethods(String className, String superName,
                                                  String nameA, String descA,
                                                  String nameB, String descB) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null, superName, null);

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        for (var pair : new String[][] { {nameA, descA}, {nameB, descB} }) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, pair[0], pair[1], null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Set<String> collectMethodNames(byte[] classBytes) {
        Set<String> names = new HashSet<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                names.add(name);
                return null;
            }
        }, 0);
        return names;
    }
}
