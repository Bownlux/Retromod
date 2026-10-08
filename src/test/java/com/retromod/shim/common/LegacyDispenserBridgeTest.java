/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static com.retromod.shim.common.LegacyDispenserBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 projectile dispense behavior, after the SRG remap, against the 1.21 dispenser API. */
class LegacyDispenserBridgeTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void aProjectileBehaviorExtendsTheGeneratedBaseAndReadsTheRecord() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, true);

        ClassNode behavior = node(transformer.transformClass(legacyBehavior(), "test/DartBehavior.class"));

        assertEquals(PROJECTILE_BASE, behavior.superName, "the removed base must be supplied");
        MethodNode execute = behavior.methods.stream().filter(m -> m.name.equals("execute")).findFirst().orElseThrow();
        assertTrue(execute.desc.contains("L" + SOURCE + ";"), "the record must replace the interface: " + execute.desc);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : execute.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(SOURCE)) {
                calls.add(call.name);
                assertEquals(Opcodes.INVOKEVIRTUAL, call.getOpcode(), "a record accessor is a virtual call");
                assertFalse(call.itf, "the record is not an interface");
            }
        }
        assertEquals(List.of("level", "pos"), calls, "the get-prefixed getters must reach the accessors");
    }

    @Test
    void theGeneratedBaseIsStructurallyValid() throws Exception {
        ClassNode base = node(generateProjectileBase(DIRECTION_PROPERTY));

        assertNotEquals(0, base.access & Opcodes.ACC_ABSTRACT, "getProjectile stays the subclass's job");
        for (MethodNode method : base.methods) {
            if ((method.access & Opcodes.ACC_ABSTRACT) == 0) {
                new Analyzer<>(new BasicVerifier()).analyze(base.name, method);
            }
        }
        assertTrue(base.methods.stream().anyMatch(m -> m.name.equals("getProjectile")
                && m.desc.equals(GET_PROJECTILE_DESC)), "subclasses override getProjectile by this shape");
    }

    @Test
    void theBaseReadsFacingWithTheHostsPropertyType() {
        for (String facing : List.of(DIRECTION_PROPERTY, ENUM_PROPERTY)) {
            ClassNode base = node(generateProjectileBase(facing));
            MethodNode execute = base.methods.stream().filter(m -> m.name.equals("execute")).findFirst().orElseThrow();
            boolean readsFacing = java.util.Arrays.stream(execute.instructions.toArray())
                    .anyMatch(insn -> insn instanceof org.objectweb.asm.tree.FieldInsnNode field
                            && field.name.equals("FACING") && field.desc.equals(facing));
            assertTrue(readsFacing, "1.21.2 retyped FACING, so the read must use the host's type " + facing);
        }
    }

    @Test
    void withoutTheRemovalOnlyTheRecordIsBridged() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, false);

        ClassNode behavior = node(transformer.transformClass(legacyBehavior(), "test/DartBehavior.class"));

        assertEquals(OLD_PROJECTILE_BASE, behavior.superName, "a host that keeps the base keeps it");
    }

    /** A dispense behavior that reads the level and position from the old interface. */
    private static byte[] legacyBehavior() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/DartBehavior", null, OLD_PROJECTILE_BASE, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "execute",
                "(L" + OLD_SOURCE + ";Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD_SOURCE, "getLevel",
                "()Lnet/minecraft/server/level/ServerLevel;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD_SOURCE, "getPos", "()Lnet/minecraft/core/BlockPos;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
