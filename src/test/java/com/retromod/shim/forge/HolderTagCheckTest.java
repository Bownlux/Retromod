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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #394: Villager Recruits (Forge 1.20.1) checks a biome's tags with Forge's {@code Holder.containsTag}, which NeoForge
 * does not have, so every recruit failed to spawn with {@code NoSuchMethodError} on NeoForge 1.21.1.
 */
class HolderTagCheckTest {

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
    @DisplayName("holder.containsTag(tag) asks the holder's own is(tag)")
    void containsTagBecomesIs() {
        ForgeNeoForgeApiBridge.registerHolderTagCheck(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/RecruitSpawn", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "inTag",
                "(Lnet/minecraft/core/Holder;Lnet/minecraft/tags/TagKey;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, ForgeNeoForgeApiBridge.HOLDER, "containsTag", ForgeNeoForgeApiBridge.HOLDER_TAG_CHECK, true);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "mod/RecruitSpawn")).accept(out, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : out.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) {
                    calls.add((call.getOpcode() == INVOKEINTERFACE ? "interface " : "") + call.owner + "." + call.name + call.desc);
                }
            }
        }
        assertEquals(List.of("interface net/minecraft/core/Holder.is(Lnet/minecraft/tags/TagKey;)Z"), calls);
    }
}
