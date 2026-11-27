/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** NeoForge 1.21.1's effect cures reach Retromod's rebuilt cures on NeoForge hosts without them. */
class LegacyEffectCureBridgeTest {

    private static final String MOD_CLASS = "test/cures/MilkBottle";
    private static final String CURE = "Lnet/neoforged/neoforge/common/EffectCure;";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A milk bottle's cure, removal and hook calls reach the rebuilt cures on NeoForge 26.3")
    void milkBottleReachesTheRebuiltCures() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyEffectCureBridge.register(transformer);

        ClassNode out = transform();
        List<String> refs = refs(out);
        assertEquals(List.of("F " + LegacyEffectCureBridge.CURES + ".MILK",
                LegacyEffectCureBridge.CALLS + ".removeEffectsCuredBy(L" + LIVING + ";L" + LegacyEffectCureBridge.CURE + ";)Z",
                "F " + LegacyEffectCureBridge.CURES + ".HONEY",
                LegacyEffectCureBridge.CALLS + ".removeEffectsCuredBy(L" + LIVING + ";L" + LegacyEffectCureBridge.CURE + ";)Z",
                LegacyEffectCureBridge.CALLS + ".getCures(Lnet/minecraft/world/effect/MobEffectInstance;)Ljava/util/Set;",
                LegacyEffectCureBridge.CALLS + ".onEffectRemoved(L" + LIVING + ";Lnet/minecraft/world/effect/MobEffectInstance;L"
                        + LegacyEffectCureBridge.CURE + ";)Z"), refs);
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyEffectCureBridge.CURE), "the cure type travels with the helper");
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyEffectCureBridge.CURES));
    }

    @Test
    @DisplayName("NeoForge 1.21.1, which still has cures, keeps them")
    void olderHostKeepsItsCures() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyEffectCureBridge.register(transformer);
        transformer.registerMethodRedirect("test/cures/Old", "old", "()V", "test/cures/New", "now", "()V");

        assertTrue(refs(transform()).contains("F net/neoforged/neoforge/common/EffectCures.MILK"));
    }

    @Test
    @DisplayName("Cures are interned by name, and every effect has milk and the totem as default cures")
    void curesKeepTheirIdentity() throws Exception {
        Class<?> cure = Class.forName(LegacyEffectCureBridge.CURE.replace('/', '.'));
        Class<?> cures = Class.forName(LegacyEffectCureBridge.CURES.replace('/', '.'));
        Method get = cure.getMethod("get", String.class);
        Object milk = cures.getField("MILK").get(null);
        assertSame(milk, get.invoke(null, "milk"), "a mod that asks for milk by name gets the same cure");
        assertEquals("milk", cure.getMethod("name").invoke(milk));
        assertEquals(Set.of(milk, cures.getField("PROTECTED_BY_TOTEM").get(null)), cures.getField("DEFAULT_CURES").get(null));
        assertTrue(((java.util.Collection<?>) cure.getMethod("getAllCures").invoke(null)).contains(cures.getField("HONEY").get(null)));
    }

    // ---- fixtures ----

    /** A milk bottle and a honey drink as NeoForge 1.21.1 mods wrote them. */
    private ClassNode transform() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "drink",
                "(L" + LIVING + ";Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/effect/MobEffectInstance;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/neoforged/neoforge/common/EffectCures", "MILK", CURE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "removeEffectsCuredBy", "(" + CURE + ")Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/neoforged/neoforge/common/EffectCures", "HONEY", CURE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/entity/player/Player", "removeEffectsCuredBy", "(" + CURE + ")Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/effect/MobEffectInstance", "getCures", "()Ljava/util/Set;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/neoforged/neoforge/event/EventHooks", "onEffectRemoved",
                "(L" + LIVING + ";Lnet/minecraft/world/effect/MobEffectInstance;" + CURE + ")Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        byte[] out = transformer.transformClass(bytes, MOD_CLASS);
        ClassNode node = new ClassNode();
        new ClassReader(out != null ? out : bytes).accept(node, 0);
        return node;
    }

    private static List<String> refs(ClassNode node) {
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) refs.add("F " + field.owner + "." + field.name);
            }
        }
        return refs;
    }
}
