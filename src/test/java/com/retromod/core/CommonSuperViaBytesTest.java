/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A mod's own classes are not on the transform classpath, so ASM cannot resolve their hierarchy and
 * frame computation falls back to {@code Object}. When the merged value is then passed somewhere that
 * wants a specific type, the JVM rejects the method with a {@code VerifyError} (#180, AutoClicky:
 * two screens merged to {@code Object} and handed to {@code Minecraft.setScreen(Screen)}).
 *
 * <p>The shape used here mirrors that mod exactly: {@code Child extends Parent extends Screen}, where
 * {@code Screen} stands for a Minecraft class that is absent while transforming.
 */
class CommonSuperViaBytesTest {

    private static final String CHILD = "com/example/mod/Child";
    private static final String PARENT = "com/example/mod/Parent";
    private static final String SIBLING = "com/example/mod/Sibling";
    private static final String ABSENT_MC = "net/minecraft/client/gui/screens/Screen";

    /** A class whose only interesting property is its declared superclass. */
    private static byte[] classWithSuper(String name, String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Stands in for the mod jar being transformed: it holds the mod's classes and nothing else. */
    private static Function<String, byte[]> modJar() {
        Map<String, byte[]> jar = new HashMap<>();
        jar.put(CHILD, classWithSuper(CHILD, PARENT));
        jar.put(PARENT, classWithSuper(PARENT, ABSENT_MC));
        jar.put(SIBLING, classWithSuper(SIBLING, PARENT));
        return jar::get;
    }

    @Test
    @DisplayName("#180: a subclass merged with its own ancestor resolves to that ancestor")
    void resolvesAncestorWithoutLeavingTheJar() {
        // Both types are in the mod's jar and one extends the other, so the answer is reachable
        // without ever resolving the Minecraft class at the top of the chain.
        assertEquals(PARENT,
                RetromodTransformer.commonSuperViaBytes(CHILD, PARENT, modJar()),
                "merging a class with its own superclass must give that superclass");
        assertEquals(PARENT,
                RetromodTransformer.commonSuperViaBytes(PARENT, CHILD, modJar()),
                "the answer must not depend on argument order");
    }

    @Test
    @DisplayName("#180: two siblings resolve to their shared mod-owned parent")
    void resolvesSharedParent() {
        assertEquals(PARENT,
                RetromodTransformer.commonSuperViaBytes(CHILD, SIBLING, modJar()),
                "two mod classes under one mod-owned parent share that parent");
    }

    @Test
    @DisplayName("An identical pair is its own answer")
    void identityIsTrivial() {
        assertEquals(CHILD, RetromodTransformer.commonSuperViaBytes(CHILD, CHILD, modJar()));
    }

    @Test
    @DisplayName("Nothing is invented when the jar cannot supply the hierarchy")
    void unknownTypesYieldNoAnswer() {
        Function<String, byte[]> empty = n -> null;
        assertNull(RetromodTransformer.commonSuperViaBytes(CHILD, PARENT, empty),
                "with no bytes to read, the caller must fall back rather than guess");
    }

    @Test
    @DisplayName("The shared resolver prefers the jar and keeps the exception guess as a fallback")
    void sharedResolverPrefersTheJar() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        try {
            transformer.setJarClassBytesProvider(modJar());
            assertEquals(PARENT, RetromodTransformer.resolveCommonSuperClass(CHILD, SIBLING),
                    "with the jar available the real shared type must win");

            transformer.clearJarClassBytesProvider();
            // Without the jar there is nothing to read, so it falls back. Two unrelated,
            // non-exception names have no better answer than Object.
            assertEquals("java/lang/Object",
                    RetromodTransformer.resolveCommonSuperClass(CHILD, SIBLING));
            // The naming-based exception guess (#94) must survive the change.
            assertEquals("java/lang/Throwable", RetromodTransformer.resolveCommonSuperClass(
                    "com/example/mod/BadThingException", "com/example/mod/OtherException"));
        } finally {
            transformer.clearJarClassBytesProvider();
        }
    }

    @Test
    @DisplayName("A remapped mod hierarchy can continue through an off-classpath target index")
    void remappedHierarchyContinuesThroughIndexedTargetParents() {
        Map<String, byte[]> source = Map.of(
                CHILD, classWithSuper(CHILD, "net/minecraft/class_1668"));
        Map<String, String> remaps = Map.of(
                "net/minecraft/class_1668",
                        "net/minecraft/world/entity/projectile/AbstractHurtingProjectile");
        Map<String, String> targetParents = Map.of(
                "net/minecraft/world/entity/projectile/AbstractHurtingProjectile",
                        "net/minecraft/world/entity/Entity",
                "net/minecraft/world/entity/Entity",
                        "java/lang/Object");

        assertEquals("net/minecraft/world/entity/Entity",
                RetromodTransformer.commonSuperViaBytes(
                        CHILD,
                        "net/minecraft/world/entity/Entity",
                        source::get,
                        name -> remaps.getOrDefault(name, name),
                        targetParents::get));
    }
}
