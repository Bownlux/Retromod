/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import static com.retromod.shim.common.LegacyBreathingOverrideAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

/** An aquatic 1.20.1 mob that overrides {@code canBreatheUnderwater()}, final since 1.20.5. */
class LegacyBreathingOverrideAdapterTest {

    @Test
    void theOverrideBecomesPrivateAndAnswersTheDrowningHook() throws Exception {
        ClassNode mob = node(apply(mob(false), (child, ancestor) -> true));

        MethodNode renamed = method(mob, RENAMED);
        assertNotNull(renamed, "the override must be renamed off the final method");
        assertNotEquals(0, renamed.access & Opcodes.ACC_PRIVATE);
        assertNull(method(mob, NAME), "no override of the final method may remain");
        MethodNode hook = method(mob, DROWN);
        assertNotNull(hook, "the drowning hook must be added");
        new Analyzer<>(new BasicVerifier()).analyze(mob.name, hook);
    }

    @Test
    void aClassWithItsOwnDrowningHookKeepsIt() {
        ClassNode mob = node(apply(mob(true), (child, ancestor) -> true));

        assertEquals(1, mob.methods.stream().filter(m -> m.name.equals(DROWN)).count(),
                "the mod's own drowning answer must not be duplicated");
    }

    @Test
    void aClassThatIsNotAnEntityIsLeftAlone() {
        byte[] original = mob(false);

        assertSame(original, apply(original, (child, ancestor) -> false));
    }

    @Test
    void offlineANeoForgeTargetFrom1205OnGetsTheRepair() {
        assertTrue(LegacyBreathingOverrideAdapter.offlineNeedsRepair(true, "1.21.1"));
        assertTrue(LegacyBreathingOverrideAdapter.offlineNeedsRepair(true, "1.20.5"));
        assertFalse(LegacyBreathingOverrideAdapter.offlineNeedsRepair(true, "1.20.4"),
                "the method was not final before 1.20.5");
        assertFalse(LegacyBreathingOverrideAdapter.offlineNeedsRepair(false, "1.21.1"),
                "only NeoForge has the drowning hook");
    }

    private static byte[] mob(boolean ownHook) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Shark", null,
                "net/minecraft/world/entity/animal/WaterAnimal", null);
        MethodVisitor breathe = cw.visitMethod(Opcodes.ACC_PUBLIC, NAME, "()Z", null, null);
        breathe.visitCode();
        breathe.visitInsn(Opcodes.ICONST_1);
        breathe.visitInsn(Opcodes.IRETURN);
        breathe.visitMaxs(0, 0);
        breathe.visitEnd();
        if (ownHook) {
            MethodVisitor drown = cw.visitMethod(Opcodes.ACC_PUBLIC, DROWN, DROWN_DESC, null, null);
            drown.visitCode();
            drown.visitInsn(Opcodes.ICONST_0);
            drown.visitInsn(Opcodes.IRETURN);
            drown.visitMaxs(0, 0);
            drown.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
    }
}
