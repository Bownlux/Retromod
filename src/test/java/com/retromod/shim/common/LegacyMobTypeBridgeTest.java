/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.embedded.LegacyMobType;
import org.junit.jupiter.api.AfterEach;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.20.5 removed {@code MobType}. Old mods still compare {@code getMobType()} with its constants
 * and subclass it for their own types.
 */
class LegacyMobTypeBridgeTest {

    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void undeadCheckReadsTheStandInAndAsksTheStaticLookup() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyMobTypeBridge.register(transformer);

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(undeadCheck(), "com/example/Smite")).accept(node, 0);
        MethodNode check = node.methods.stream().filter(m -> m.name.equals("isUndead")).findFirst().orElseThrow();

        MethodInsnNode lookup = first(check, MethodInsnNode.class);
        assertEquals(Opcodes.INVOKESTATIC, lookup.getOpcode(), "LivingEntity.getMobType() no longer exists");
        assertEquals(LegacyMobTypeBridge.BRIDGE, lookup.owner);
        FieldInsnNode constant = first(check, FieldInsnNode.class);
        assertEquals(LegacyMobTypeBridge.BRIDGE, constant.owner, "constants come from the stand-in");
    }

    @Test
    void registersNothingBefore1205() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyMobTypeBridge.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(LegacyMobTypeBridge.OLD_TYPE));
    }

    @Test
    void lookupPrefersTheMobsOwnLegacyOverride() {
        assertSame(LegacyMobType.UNDEAD, LegacyMobType.of(new Zombieish()),
                "a mod mob that declared itself undead must still answer undead");
        assertSame(LegacyMobType.UNDEFINED, LegacyMobType.of(new Object()),
                "anything without an override or a readable type is undefined");
    }

    @Test
    void superCallsOnVanillaMobBasesUseTheStaticLookup() {
        RetromodTransformer transformer = registeredOn("1.21.1");
        String monster = "net/minecraft/world/entity/monster/Monster";
        byte[] mob = mobCalling(Opcodes.INVOKESPECIAL, monster, monster);

        MethodInsnNode call = first(method(transformer.transformClass(mob, MOD_MOB), "getMobType"),
                MethodInsnNode.class);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(),
                "Monster no longer declares getMobType, so super.getMobType() must not stay a direct call");
        assertEquals(LegacyMobTypeBridge.BRIDGE, call.owner);
        assertEquals("of", call.name);
    }

    @Test
    void callsOnTheModsOwnMobClassUseTheStaticLookup() {
        RetromodTransformer transformer = registeredOn("1.21.1");
        byte[] mob = mobCalling(Opcodes.INVOKEVIRTUAL, MOD_MOB, LIVING);
        transformer.setJarClassBytesProvider(name -> MOD_MOB.equals(name) ? mob : null);
        try {
            MethodInsnNode call = first(method(transformer.transformClass(mob, MOD_MOB), "getMobType"),
                    MethodInsnNode.class);
            assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(),
                    "the mod class inherits the removed method from LivingEntity");
            assertEquals(LegacyMobTypeBridge.BRIDGE, call.owner);
        } finally {
            transformer.setJarClassBytesProvider(null);
        }
    }

    @Test
    void superCallsThroughAModBaseWithoutTheMethodUseTheStaticLookup() {
        RetromodTransformer transformer = registeredOn("1.21.1");
        byte[] base = modBase(false);
        byte[] mob = mobCalling(Opcodes.INVOKESPECIAL, MOD_BASE, MOD_BASE);
        transformer.setJarClassBytesProvider(name -> MOD_BASE.equals(name) ? base
                : MOD_MOB.equals(name) ? mob : null);
        try {
            MethodInsnNode call = first(method(transformer.transformClass(mob, MOD_MOB), "getMobType"),
                    MethodInsnNode.class);
            assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(),
                    "the mod base never declared getMobType, so super.getMobType() reached the removed method");
            assertEquals(LegacyMobTypeBridge.BRIDGE, call.owner);
        } finally {
            transformer.setJarClassBytesProvider(null);
        }
    }

    @Test
    void superCallsToAModBaseOverrideStayDirect() {
        RetromodTransformer transformer = registeredOn("1.21.1");
        byte[] base = modBase(true);
        byte[] mob = mobCalling(Opcodes.INVOKESPECIAL, MOD_BASE, MOD_BASE);
        transformer.setJarClassBytesProvider(name -> MOD_BASE.equals(name) ? base
                : MOD_MOB.equals(name) ? mob : null);
        try {
            MethodInsnNode call = first(method(transformer.transformClass(mob, MOD_MOB), "getMobType"),
                    MethodInsnNode.class);
            assertEquals(Opcodes.INVOKESPECIAL, call.getOpcode(),
                    "the base's own override is what super.getMobType() runs, so it must stay");
            assertEquals(MOD_BASE, call.owner);
        } finally {
            transformer.setJarClassBytesProvider(null);
        }
    }

    @Test
    void anOverrideThatAsksItsSuperclassGetsTheVanillaAnswer() {
        assertSame(LegacyMobType.UNDEFINED, LegacyMobType.of(new DefersToSuper()),
                "super.getMobType() inside the override must not call the override again");
        assertSame(LegacyMobType.UNDEAD, LegacyMobType.of(new Zombieish()),
                "the guard must not hide an override that answers on its own");
    }

    /** {@code getMobType() { return super.getMobType(); }} after the super call is bridged. */
    static final class DefersToSuper {
        public LegacyMobType getMobType() {
            return LegacyMobType.of(this);
        }
    }

    private static final String MOD_MOB = "com/example/ModMob";
    private static final String MOD_BASE = "com/example/ModMonsterBase";

    /** A mod base mob on LivingEntity, with or without its own getMobType override. */
    private static byte[] modBase(boolean declaresGetMobType) {
        String mobType = "L" + LegacyMobTypeBridge.OLD_TYPE + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, MOD_BASE, null, LIVING, null);
        if (declaresGetMobType) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getMobType", "()" + mobType, null, null);
            mv.visitCode();
            mv.visitFieldInsn(Opcodes.GETSTATIC, LegacyMobTypeBridge.OLD_TYPE, "UNDEAD", mobType);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static RetromodTransformer registeredOn(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyMobTypeBridge.register(transformer);
        return transformer;
    }

    /** A mod mob extending {@code superName} whose {@code getMobType()} makes one call. */
    private static byte[] mobCalling(int opcode, String owner, String superName) {
        String mobType = "L" + LegacyMobTypeBridge.OLD_TYPE + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_MOB, null, superName, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getMobType", "()" + mobType, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(opcode, owner, "getMobType", "()" + mobType, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode method(byte[] classBytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    /** Shape of an old mod mob after the class rename. */
    static final class Zombieish {
        public LegacyMobType getMobType() {
            return LegacyMobType.UNDEAD;
        }
    }

    /** {@code boolean isUndead(LivingEntity e) { return e.getMobType() == MobType.UNDEAD; }} */
    private static byte[] undeadCheck() {
        String mobType = LegacyMobTypeBridge.OLD_TYPE;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Smite", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isUndead",
                "(L" + LIVING + ";)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "getMobType", "()L" + mobType + ";", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, mobType, "UNDEAD", "L" + mobType + ";");
        mv.visitInsn(Opcodes.POP2);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static <T extends AbstractInsnNode> T first(MethodNode method, Class<T> type) {
        for (AbstractInsnNode insn : method.instructions) {
            if (type.isInstance(insn)) return type.cast(insn);
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + method.name);
    }
}
