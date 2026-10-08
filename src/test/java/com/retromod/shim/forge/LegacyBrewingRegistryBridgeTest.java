/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Forge's static brewing registry, as Wither Storm fills it during setup, on a NeoForge host that
 * builds brewing recipes from {@code RegisterBrewingRecipesEvent}.
 */
class LegacyBrewingRegistryBridgeTest {

    private static final String NEO_RECIPE = "net/neoforged/neoforge/common/brewing/IBrewingRecipe";

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
    @DisplayName("addRecipe(IBrewingRecipe) becomes a recorded call after the recipe class moves")
    void addRecipeIsRecorded() {
        assertTrue(LegacyBrewingRegistryBridge.registerIfHostHasEvent(transformer,
                Set.of(LegacyBrewingRegistryBridge.EVENT)::contains));
        // ForgeNeoForgeApiBridge registers this move; the order between the two must not matter.
        transformer.registerClassRedirect(LegacyBrewingRegistryBridge.FORGE_RECIPE, NEO_RECIPE);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/ModItems", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerBrewingRecipes",
                "(L" + LegacyBrewingRegistryBridge.FORGE_RECIPE + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, LegacyBrewingRegistryBridge.FORGE_REGISTRY, "addRecipe",
                "(L" + LegacyBrewingRegistryBridge.FORGE_RECIPE + ";)Z", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/ModItems")).accept(out, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        out.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }));
        assertTrue(calls.stream().anyMatch(c -> c.owner.equals(LegacyBrewingRegistryBridge.HELPER)
                        && c.name.equals("addRecipe") && c.desc.equals("(Ljava/lang/Object;)Z")),
                "the static Forge registry is gone, so the recipe must be kept for NeoForge's event: "
                        + calls.stream().map(c -> c.owner + "." + c.name + c.desc).toList());
    }

    @Test
    @DisplayName("A host that still has Forge's registry, or no brewing event, is left alone")
    void hostGate() {
        assertFalse(LegacyBrewingRegistryBridge.registerIfHostHasEvent(transformer,
                Set.of(LegacyBrewingRegistryBridge.FORGE_REGISTRY, LegacyBrewingRegistryBridge.EVENT)::contains));
        assertFalse(LegacyBrewingRegistryBridge.registerIfHostHasEvent(transformer, name -> false));
        assertTrue(transformer.getSyntheticClasses().isEmpty());
    }
}
