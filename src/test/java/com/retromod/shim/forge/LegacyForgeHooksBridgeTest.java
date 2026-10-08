/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.api.forge.ForgeEventApiShim;
import com.retromod.shim.forge.embedded.LegacyEventCalls;
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

import static com.retromod.shim.forge.LegacyForgeHooksBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A Forge 1.20.1 mob's damage code against NeoForge's hooks and split damage events. */
class LegacyForgeHooksBridgeTest {

    private static final String LIVING = "Lnet/minecraft/world/entity/LivingEntity;";
    private static final String FORGE_EVENTS = "net/minecraftforge/event/entity/living/";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void changedDamageHooksGoThroughTheHelper() {
        List<String> calls = calls(transform(true), "actuallyHurt");

        assertTrue(calls.contains(HELPER + ".onLivingHurt" + AMOUNT_DESC), "onLivingHurt: " + calls);
        assertTrue(calls.contains(HELPER + ".onLivingDamage" + AMOUNT_DESC), "onLivingDamage: " + calls);
        assertTrue(calls.contains(HELPER + ".onLivingAttack" + ATTACK_DESC), "onLivingAttack: " + calls);
    }

    @Test
    void unchangedHooksMoveToCommonHooks() {
        List<String> calls = calls(transform(true), "actuallyHurt");

        assertTrue(calls.contains(COMMON_HOOKS + ".onLivingJump(" + LIVING + ")V"),
                "a hook with the same shape must keep its call on CommonHooks: " + calls);
        assertFalse(calls.stream().anyMatch(c -> c.startsWith(FORGE_HOOKS)), "no ForgeHooks call may survive");
    }

    @Test
    void theTierCheckIsBridgedOnlyWhereTheHostStillHasTier() {
        assertTrue(calls(transform(true), "actuallyHurt").contains(HELPER + ".isCorrectTierForDrops" + TIER_DESC));
        assertTrue(calls(transform(false), "actuallyHurt").contains(TIER_SORTING + ".isCorrectTierForDrops" + TIER_DESC),
                "without Tier on the host the call is left as it was");
    }

    @Test
    void theGeneratedHelperIsStructurallyValid() throws Exception {
        ClassNode helper = node(generateHelper());
        for (MethodNode method : helper.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
        }
    }

    @Test
    void forgeDamageEventsMapToNeoForgesSplitEvents() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        ForgeEventApiShim.registerDamageEvents(transformer, true);

        List<String> calls = calls(transformer.transformClass(damageListener(), "test/DamageListener.class"), "on");

        assertTrue(calls.contains("net/neoforged/neoforge/event/entity/living/LivingDamageEvent$Pre.setNewDamage(F)V"),
                "LivingDamageEvent.setAmount must set the new damage: " + calls);
        assertTrue(calls.contains("net/neoforged/neoforge/event/entity/living/LivingIncomingDamageEvent.setAmount(F)V"),
                "LivingHurtEvent must become the incoming damage event: " + calls);
    }

    @Test
    void aHostBeforeTheSplitKeepsTheSameNamedEvents() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        ForgeEventApiShim.registerDamageEvents(transformer, false);

        List<String> calls = calls(transformer.transformClass(damageListener(), "test/DamageListener.class"), "on");

        assertTrue(calls.contains("net/neoforged/neoforge/event/entity/living/LivingHurtEvent.setAmount(F)V"), calls.toString());
        assertTrue(calls.contains("net/neoforged/neoforge/event/entity/living/LivingDamageEvent.setAmount(F)V"), calls.toString());
    }

    /** NeoForge's damage Pre event: not cancellable, but its damage can be set. */
    public static final class DamagePre {
        float damage = 5;
        public void setNewDamage(float damage) { this.damage = damage; }
    }

    @Test
    void cancellingAnUncancellableDamageEventPreventsTheDamage() {
        DamagePre event = new DamagePre();

        LegacyEventCalls.setCanceled(event, true);

        assertEquals(0f, event.damage, "Forge's cancelled damage event dealt no damage");
    }

    private static byte[] transform(boolean tierCheck) {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, tierCheck);
        return transformer.transformClass(legacyMob(), "test/LegacyMob.class");
    }

    private static byte[] legacyMob() {
        String source = "Lnet/minecraft/world/damagesource/DamageSource;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/LegacyMob", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "actuallyHurt",
                "(" + LIVING + source + "FLnet/minecraft/world/item/Tier;"
                        + "Lnet/minecraft/world/level/block/state/BlockState;)V", null, null);
        mv.visitCode();
        for (String[] hook : new String[][]{
                {"onLivingAttack", ATTACK_DESC}, {"onLivingHurt", AMOUNT_DESC}, {"onLivingDamage", AMOUNT_DESC}}) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.FLOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, FORGE_HOOKS, hook[0], hook[1], false);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, FORGE_HOOKS, "onLivingJump", "(" + LIVING + ")V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TIER_SORTING, "isCorrectTierForDrops", TIER_DESC, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A listener that halves Forge's final damage and doubles its hurt amount. */
    private static byte[] damageListener() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/DamageListener", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "on",
                "(L" + FORGE_EVENTS + "LivingDamageEvent;L" + FORGE_EVENTS + "LivingHurtEvent;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE_EVENTS + "LivingDamageEvent", "getAmount", "()F", false);
        mv.visitLdcInsn(0.5f);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE_EVENTS + "LivingDamageEvent", "setAmount", "(F)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE_EVENTS + "LivingHurtEvent", "getAmount", "()F", false);
        mv.visitLdcInsn(2f);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE_EVENTS + "LivingHurtEvent", "setAmount", "(F)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes, String methodName) {
        List<String> calls = new ArrayList<>();
        MethodNode method = node(bytes).methods.stream().filter(m -> m.name.equals(methodName))
                .findFirst().orElseThrow();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
