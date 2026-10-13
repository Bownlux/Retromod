/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** MCreator's death and command templates from Box of Horrors (#326) and Poppy Playtime (#328). */
class LegacyEntityTemplateAdapterTest {

    private static final String MOB = "test/HorrorEntity";
    private static final String SOURCE = "Lnet/minecraft/commands/CommandSourceStack;";

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
    void deathTemplateSuppliesTheAttackerOn1_21() {
        LegacyEntityTemplateAdapter.register(transformer, null,
                host("net/minecraft/world/entity/LivingEntity", "dropExperience", "(Lnet/minecraft/world/entity/Entity;)V"));
        ClassNode out = transform(deathTemplate());

        assertEquals(List.of(LegacyEntityTemplateAdapter.DROP_BRIDGE + "()V"), calls(method(out, "tickDeath")));
        assertEquals(List.of("getLastHurtByMob()Lnet/minecraft/world/entity/LivingEntity;",
                "dropExperience(Lnet/minecraft/world/entity/Entity;)V"),
                calls(method(out, LegacyEntityTemplateAdapter.DROP_BRIDGE)));
    }

    @Test
    void deathTemplateAlsoSuppliesTheServerLevelOn26_1() {
        LegacyEntityTemplateAdapter.register(transformer, null, host("net/minecraft/world/entity/LivingEntity",
                "dropExperience", "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)V"));
        List<String> bridge = calls(method(transform(deathTemplate()), LegacyEntityTemplateAdapter.DROP_BRIDGE));

        assertEquals("dropExperience(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)V",
                bridge.get(bridge.size() - 1));
    }

    @Test
    void commandTemplateKeepsItsResultWhenTheHostReturnsNothing() {
        LegacyEntityTemplateAdapter.register(transformer,
                host("net/minecraft/commands/Commands", "performPrefixedCommand", "(" + SOURCE + "Ljava/lang/String;)V"), null);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/RunCommandProcedure", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "execute",
                "(Lnet/minecraft/commands/Commands;" + SOURCE + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn("summon zombie");
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/commands/Commands", "performPrefixedCommand",
                "(" + SOURCE + "Ljava/lang/String;)I", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/RunCommandProcedure")).accept(out, 0);
        MethodInsnNode call = (MethodInsnNode) java.util.Arrays.stream(method(out, "execute").instructions.toArray())
                .filter(insn -> insn instanceof MethodInsnNode).findFirst().orElseThrow();
        assertEquals(LegacyEntityTemplateAdapter.COMMANDS_HELPER, call.owner);
        assertTrue(call.desc.endsWith(")I"), "the template still receives a result to pop");
    }

    /** {@code tickDeath() { this.dropExperience(); }} as MCreator's GeckoLib template wrote it. */
    private static byte[] deathTemplate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOB, null, "net/minecraft/world/entity/monster/Monster", null);
        MethodVisitor mv = cw.visitMethod(ACC_PROTECTED, "tickDeath", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, MOB, "dropExperience", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private ClassNode transform(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, MOB)).accept(node, 0);
        return node;
    }

    private static ClassNode host(String name, String method, String desc) {
        ClassNode node = new ClassNode();
        node.name = name;
        node.methods.add(new MethodNode(ACC_PUBLIC, method, desc, null, null));
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        method.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call.name + call.desc);
        });
        return calls;
    }
}
