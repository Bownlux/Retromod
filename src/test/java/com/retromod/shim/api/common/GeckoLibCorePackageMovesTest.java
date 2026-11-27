/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** GeckoLib 4.5 merged its core package into the main packages (#308). */
class GeckoLibCorePackageMovesTest {

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
    void mergedHostGetsTheCoreMoves() {
        Set<String> host = Set.of(GeckoLibCorePackageMoves.MERGED_CONTROLLER);
        assertTrue(GeckoLibCorePackageMoves.registerIfHostMerged(transformer, host::contains) > 30);
        assertEquals("software/bernie/geckolib/animation/AnimationController",
                transformer.getClassRedirects().get("software/bernie/geckolib/core/animation/AnimationController"));
        assertEquals("software/bernie/geckolib/animation/PlayState",
                transformer.getClassRedirects().get("software/bernie/geckolib/core/object/PlayState"));
        // Scorched Guns reads a controller's state to pick its gun animation.
        assertEquals("software/bernie/geckolib/animation/AnimationController$State",
                transformer.getClassRedirects().get("software/bernie/geckolib/core/animation/AnimationController$State"));
    }

    @Test
    void hostStillShippingCoreIsLeftAlone() {
        Set<String> host = Set.of(GeckoLibCorePackageMoves.MERGED_CONTROLLER, GeckoLibCorePackageMoves.CORE_CONTROLLER);
        assertEquals(0, GeckoLibCorePackageMoves.registerIfHostMerged(transformer, host::contains),
                "a 1.20.1 GeckoLib still has the core classes the mod names");
    }

    @Test
    void hostWithoutGeckoLib4IsLeftAlone() {
        assertEquals(0, GeckoLibCorePackageMoves.registerIfHostMerged(transformer, name -> false),
                "GeckoLib 5 hosts are handled by GeckoLib5ApiShim");
    }

    @Test
    void everyMoveLeavesTheCorePackage() {
        Map<String, String> moves = GeckoLibCorePackageMoves.classMoves();
        assertFalse(moves.isEmpty());
        moves.forEach((from, to) -> {
            assertTrue(from.startsWith("software/bernie/geckolib/core/"), from);
            assertFalse(to.contains("/core/"), from + " must move out of the core package, not to " + to);
        });
    }

    @Test
    void callsOnAnInterfaceThatBecameAClassTakeTheClassForm() {
        // GeckoLib 4.9.3 for 1.21.1: GeoBone and GeoModel are classes, GeoAnimatable is still an interface.
        Set<String> host = Set.of(GeckoLibCorePackageMoves.MERGED_CONTROLLER);
        Set<String> classes = Set.of("software/bernie/geckolib/cache/object/GeoBone", "software/bernie/geckolib/model/GeoModel",
                GeckoLibCorePackageMoves.MERGED_CONTROLLER);
        GeckoLibCorePackageMoves.registerIfHostMerged(transformer, host::contains, classes::contains);

        ClassNode model = new ClassNode();
        new ClassReader(transformer.transformClass(customAnimations(), "test/mod/TralaleroModel.class")).accept(model, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : model.methods.stream().filter(m -> m.name.equals("setCustomAnimations")).findFirst().orElseThrow().instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        assertEquals(3, calls.size());
        MethodInsnNode setRot = calls.get(0);
        assertEquals("software/bernie/geckolib/cache/object/GeoBone", setRot.owner);
        assertEquals(Opcodes.INVOKEVIRTUAL, setRot.getOpcode(), "Found class GeoBone, but interface was expected (#404)");
        assertFalse(setRot.itf);
        assertEquals(Opcodes.INVOKEVIRTUAL, calls.get(1).getOpcode(), "CoreGeoModel became the GeoModel class");
        assertEquals("software/bernie/geckolib/animatable/GeoAnimatable", calls.get(2).owner);
        assertEquals(Opcodes.INVOKEINTERFACE, calls.get(2).getOpcode(), "an interface that stayed one keeps its call");
        assertTrue(calls.get(2).itf);
    }

    /** A 1.20.1 GeckoLib model turning a bone, as MCreator writes setCustomAnimations. */
    private static byte[] customAnimations() {
        String bone = "software/bernie/geckolib/core/animatable/model/CoreGeoBone";
        String model = "software/bernie/geckolib/core/animatable/model/CoreGeoModel";
        String animatable = "software/bernie/geckolib/core/animatable/GeoAnimatable";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/TralaleroModel", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setCustomAnimations",
                "(L" + bone + ";L" + model + ";L" + animatable + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitLdcInsn(0.5F);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, bone, "setRotX", "(F)V", true);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitLdcInsn(0.0D);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, model, "applyMolangQueries", "(L" + animatable + ";D)V", true);
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, animatable, "getTick", "(Ljava/lang/Object;)D", true);
        m.visitInsn(Opcodes.POP2);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
