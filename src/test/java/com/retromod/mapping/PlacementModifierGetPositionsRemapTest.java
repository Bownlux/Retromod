/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for #114 (snapshot.9): the Fabric intermediary short-name {@code method_14452} is
 * AMBIGUOUS. Verified against Fabric Yarn 1.18.2-1.21.4 it names TWO methods, distinguished only by
 * descriptor: {@code PlacementModifier.getPositions(context, random, BlockPos) -> Stream} (Mojang
 * {@code getPositions}) and {@code getCount(random, BlockPos) -> int} (Mojang {@code count}). The
 * name-only 1.21.4 {@code intermediary-to-mojang.tsv} collapsed it to just {@code count}, so a mod's
 * {@code getPositions} override was renamed to {@code count}: it stopped overriding the abstract
 * method and threw {@code AbstractMethodError} during chunk generation on 26.x.
 *
 * <p>{@link IntermediaryToMojangMapper#applyTo} now registers the descriptor variants so the
 * transformer's ambiguous-name fallback resolves each site correctly. This test drives the REAL
 * {@code applyTo} (loading the bundled tsv) and asserts a class declaring {@code method_14452} at
 * both descriptors ends up with one {@code getPositions} and one {@code count}, never two counts.
 */
public class PlacementModifierGetPositionsRemapTest {

    /** 1.19+ intermediary descriptors: class_5444 = FeaturePlacementContext, class_5819 = RandomSource, class_2338 = BlockPos. */
    private static final String GET_POSITIONS_DESC =
            "(Lnet/minecraft/class_5444;Lnet/minecraft/class_5819;Lnet/minecraft/class_2338;)Ljava/util/stream/Stream;";
    private static final String GET_COUNT_DESC =
            "(Lnet/minecraft/class_5819;Lnet/minecraft/class_2338;)I";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("method_14452 getPositions override remaps to getPositions, getCount stays count (real applyTo)")
    void getPositionsOverrideNotRenamedToCount() {
        int classRedirects = IntermediaryToMojangMapper.applyTo(transformer);
        assertTrue(classRedirects > 0,
                "the intermediary mapper must load its bundled tsv on the test classpath");

        byte[] out = transformer.transformClass(customPlacementModifier(), "test/place/MyPlacement");
        assertNotNull(out);

        Map<String, String> defs = methodDefsByName(out);

        // The Stream-returning method_14452 override must become getPositions, not count.
        assertTrue(defs.containsKey("getPositions"),
                "the Stream-returning method_14452 override must remap to getPositions "
                        + "(the bug renamed it to count -> AbstractMethodError)");
        assertTrue(defs.get("getPositions").endsWith(")Ljava/util/stream/Stream;"),
                "getPositions must keep its Stream return");
        // The int-returning method_14452 must still become count.
        assertTrue(defs.containsKey("count"), "the int-returning method_14452 must remap to count");
        assertTrue(defs.get("count").endsWith(")I"), "count must keep its int return");
        // The intermediary short-name must not survive on either method.
        assertFalse(defs.containsKey("method_14452"),
                "no method should keep the raw intermediary short-name");
    }

    /** A synthetic PlacementModifier subclass declaring method_14452 twice (getPositions + getCount). */
    private static byte[] customPlacementModifier() {
        ClassWriter cw = new ClassWriter(0); // no COMPUTE_FRAMES: param types are off-classpath MC
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/place/MyPlacement", null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "method_14452", GET_POSITIONS_DESC, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 4);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "method_14452", GET_COUNT_DESC, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** name -> descriptor for each declared method. */
    private static Map<String, String> methodDefsByName(byte[] classBytes) {
        Map<String, String> out = new HashMap<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                out.put(n, d);
                return null;
            }
        }, 0);
        return out;
    }
}
