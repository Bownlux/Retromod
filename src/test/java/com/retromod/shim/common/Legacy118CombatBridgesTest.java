/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.Forge_1_19_2_to_1_19_3;
import com.retromod.shim.forge.Forge_1_19_3_to_1_19_4;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #311: a 1.18.2 Forge mob on Forge 1.20.1 died on its first tick or first hit. It built block
 * positions with the removed {@code BlockPos} constructors, compared incoming damage with removed
 * {@code DamageSource} constants, and exploded with the pre-1.19.3 block interaction.
 */
class Legacy118CombatBridgesTest {

    private static final String SOURCE = "net/minecraft/world/damagesource/DamageSource";
    private static final String L_SOURCE = "L" + SOURCE + ";";
    private static final String BLOCK_POS = "net/minecraft/core/BlockPos";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String INTERACTION = "net/minecraft/world/level/Explosion$BlockInteraction";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Forge_1_19_2_to_1_19_3().registerRedirects(transformer);
        new Forge_1_19_3_to_1_19_4().registerRedirects(transformer);
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("source == DamageSource.IN_FIRE becomes a fire damage type check")
    void identityComparisonBecomesTypeCheck() {
        byte[] mob = method("isInvulnerableTo", "(" + L_SOURCE + ")Z", mv -> {
            Label yes = new Label();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETSTATIC, SOURCE, "IN_FIRE", L_SOURCE);
            mv.visitJumpInsn(IF_ACMPEQ, yes);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            mv.visitLabel(yes);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
        });
        List<AbstractInsnNode> code = code(transformer.transformClass(mob, "test/Mob"));
        assertTrue(code.stream().noneMatch(i -> i instanceof FieldInsnNode f && f.owner.equals(SOURCE)),
                "no read of the removed constant is left");
        assertTrue(code.stream().anyMatch(i -> i instanceof MethodInsnNode m
                && m.owner.endsWith("LegacyDamageConstants") && m.name.equals("isInFire")),
                "the comparison asks for the fire damage type");
        assertTrue(code.stream().anyMatch(i -> i.getOpcode() == IFNE), "== becomes a true branch");
        assertTrue(code.stream().noneMatch(i -> i.getOpcode() == IF_ACMPEQ));
    }

    @Test
    @DisplayName("DamageSource.LAVA == source and source.equals(DamageSource.FALL) become type checks")
    void reversedAndEqualsComparisonsBecomeTypeChecks() {
        byte[] mob = method("isInvulnerableTo", "(" + L_SOURCE + ")Z", mv -> {
            Label yes = new Label();
            mv.visitFieldInsn(GETSTATIC, SOURCE, "LAVA", L_SOURCE);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitJumpInsn(IF_ACMPEQ, yes);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETSTATIC, SOURCE, "FALL", L_SOURCE);
            mv.visitMethodInsn(INVOKEVIRTUAL, SOURCE, "equals", "(Ljava/lang/Object;)Z", false);
            mv.visitInsn(IRETURN);
            mv.visitLabel(yes);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
        });
        List<AbstractInsnNode> code = code(transformer.transformClass(mob, "test/Mob"));
        assertTrue(code.stream().noneMatch(i -> i instanceof FieldInsnNode f && f.owner.equals(SOURCE)),
                "no read of the removed constants is left");
        assertEquals(List.of("LegacyDamageConstants.isLava", "LegacyDamageConstants.isFall"),
                calls(transformer.transformClass(mob, "test/Mob")),
                "both shapes check the damage type, which holds in every dimension");
        assertTrue(code.stream().noneMatch(i -> i.getOpcode() == IF_ACMPEQ));
    }

    @Test
    @DisplayName("OreFeatures.DEEPSLATE_ORE_REPLACEABLES builds the deepslate tag test")
    void oreTargetConstantsBecomeTagTests() {
        String ruleTest = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/RuleTest;";
        byte[] features = method("targets", "()Ljava/lang/Object;", mv -> {
            mv.visitFieldInsn(GETSTATIC, "net/minecraft/data/worldgen/features/OreFeatures",
                    "DEEPSLATE_ORE_REPLACEABLES", ruleTest);
            mv.visitInsn(ARETURN);
        });
        assertEquals(List.of("LegacyOreRuleTests.DEEPSLATE_ORE_REPLACEABLES"),
                calls(transformer.transformClass(features, "test/ModConfiguredFeatures")),
                "the removed constant no longer fails the feature class's static initializer");
        new org.objectweb.asm.ClassReader(LegacyOreRuleTests.generate()).accept(
                new org.objectweb.asm.util.CheckClassAdapter(new org.objectweb.asm.ClassWriter(0), false), 0);
    }

    @Test
    @DisplayName("hurt(DamageSource.FALL, ...) takes the server's fall source")
    void plainReadTakesTheServersSource() {
        byte[] mob = method("source", "()Ljava/lang/Object;", mv -> {
            mv.visitFieldInsn(GETSTATIC, SOURCE, "FALL", L_SOURCE);
            mv.visitInsn(ARETURN);
        });
        assertEquals(List.of("LegacyDamageConstants.fall"), calls(transformer.transformClass(mob, "test/Mob")));
    }

    @Test
    @DisplayName("DamageSource.mobAttack(mob) asks the mob's own damage sources")
    void factoryUsesTheEntitysSources() {
        String desc = "(Lnet/minecraft/world/entity/LivingEntity;)" + L_SOURCE;
        byte[] mob = method("attack", "(Lnet/minecraft/world/entity/LivingEntity;)Ljava/lang/Object;", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, SOURCE, "mobAttack", desc, false);
            mv.visitInsn(ARETURN);
        });
        assertEquals(List.of("LegacyDamageConstants.mobAttack"),
                calls(transformer.transformClass(mob, "test/Mob")));
        new ClassReader(LegacyDamageConstantBridge.generate())
                .accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    @Test
    @DisplayName("a client with no server takes damage sources from its own level, guarded for servers")
    void constantReadFallsBackToTheClientLevel() {
        ClassNode bridge = new ClassNode();
        new ClassReader(LegacyDamageConstantBridge.generate()).accept(bridge, 0);
        MethodNode sources = bridge.methods.stream().filter(m -> m.name.equals("sources")).findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(sources.instructions.spliterator(), false)
                        .anyMatch(i -> i instanceof MethodInsnNode m && m.name.equals("clientSources")),
                "a remote client has no server, so a null source would crash its mobs' hurt calls");

        MethodNode client = bridge.methods.stream().filter(m -> m.name.equals("clientSources")).findFirst().orElseThrow();
        assertEquals(1, client.tryCatchBlocks.size(), "a dedicated server lacks the client classes");
        assertEquals("java/lang/Throwable", client.tryCatchBlocks.get(0).type);
        assertTrue(java.util.stream.StreamSupport.stream(client.instructions.spliterator(), false)
                        .anyMatch(i -> i instanceof TypeInsnNode t && t.getOpcode() == CHECKCAST
                                && t.desc.equals("net/minecraft/world/level/Level")),
                "the client level is cast to Level so verification never loads ClientLevel");
    }

    @Test
    @DisplayName("new BlockPos(x, y, z) and new BlockPos(vec) become BlockPos.containing")
    void blockPosConstructorsBecomeContaining() {
        byte[] mob = method("pos", "(DDDLnet/minecraft/world/phys/Vec3;)Ljava/lang/Object;", mv -> {
            mv.visitTypeInsn(NEW, BLOCK_POS);
            mv.visitInsn(DUP);
            mv.visitVarInsn(DLOAD, 0);
            mv.visitVarInsn(DLOAD, 2);
            mv.visitVarInsn(DLOAD, 4);
            mv.visitMethodInsn(INVOKESPECIAL, BLOCK_POS, "<init>", "(DDD)V", false);
            mv.visitInsn(POP);
            mv.visitTypeInsn(NEW, BLOCK_POS);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 6);
            mv.visitMethodInsn(INVOKESPECIAL, BLOCK_POS, "<init>",
                    "(Lnet/minecraft/world/phys/Vec3;)V", false);
            mv.visitInsn(ARETURN);
        });
        byte[] out = transformer.transformClass(mob, "test/Mob");
        assertEquals(List.of("BlockPos.containing", "BlockPos.containing"), calls(out));
        assertTrue(code(out).stream().noneMatch(i -> i.getOpcode() == NEW), "no NEW of BlockPos is left");
    }

    @Test
    @DisplayName("level.explode(..., BlockInteraction.NONE) keeps blocks through the host's explode")
    void explodeConvertsTheBlockInteraction() {
        String legacy = "(Lnet/minecraft/world/entity/Entity;DDDFZL" + INTERACTION
                + ";)Lnet/minecraft/world/level/Explosion;";
        byte[] mob = method("boom", "(L" + LEVEL + ";)Ljava/lang/Object;", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(DCONST_0);
            mv.visitInsn(DCONST_0);
            mv.visitInsn(DCONST_0);
            mv.visitInsn(FCONST_1);
            mv.visitInsn(ICONST_0);
            mv.visitFieldInsn(GETSTATIC, INTERACTION, "NONE", "L" + INTERACTION + ";");
            mv.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "explode", legacy, false);
            mv.visitInsn(ARETURN);
        });
        List<AbstractInsnNode> code = code(transformer.transformClass(mob, "test/Mob"));
        assertTrue(code.stream().anyMatch(i -> i instanceof FieldInsnNode f
                && f.owner.equals(INTERACTION) && f.name.equals("KEEP")), "NONE is now KEEP");
        MethodInsnNode call = code.stream().filter(i -> i instanceof MethodInsnNode)
                .map(i -> (MethodInsnNode) i).findFirst().orElseThrow();
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertTrue(call.owner.endsWith("LegacyExplosions"), call.owner);
        assertTrue(call.desc.startsWith("(L" + LEVEL + ";"), "the level becomes the first argument");
        new ClassReader(LegacyExplosionBridge.generate())
                .accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    @Test
    @DisplayName("state.getMaterial().blocksMotion() and == Material.AIR become block state queries")
    void materialQueriesMoveOntoTheBlockState() {
        String state = "net/minecraft/world/level/block/state/BlockState";
        String material = "net/minecraft/world/level/material/Material";
        byte[] mob = method("solid", "(L" + state + ";)Z", mv -> {
            Label air = new Label();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, state, "getMaterial", "()L" + material + ";", false);
            mv.visitFieldInsn(GETSTATIC, material, "AIR", "L" + material + ";");
            mv.visitJumpInsn(IF_ACMPEQ, air);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, state, "getMaterial", "()L" + material + ";", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, material, "blocksMotion", "()Z", false);
            mv.visitInsn(IRETURN);
            mv.visitLabel(air);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
        });
        byte[] out = LegacyMaterialAdapter.apply(mob);
        assertEquals(List.of("BlockBehaviour$BlockStateBase.isAir", "BlockBehaviour$BlockStateBase.blocksMotion"),
                calls(out));
        assertTrue(code(out).stream().noneMatch(i -> i instanceof FieldInsnNode),
                "no read of a removed Material constant is left");
        new ClassReader(out).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    @Test
    @DisplayName("a comparison with another material is left alone rather than guessed")
    void otherMaterialComparisonIsUntouched() {
        String state = "net/minecraft/world/level/block/state/BlockState";
        String material = "net/minecraft/world/level/material/Material";
        byte[] mob = method("water", "(L" + state + ";)Ljava/lang/Object;", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, state, "getMaterial", "()L" + material + ";", false);
            mv.visitInsn(ARETURN);
        });
        assertSame(mob, LegacyMaterialAdapter.apply(mob));
    }

    @Test
    @DisplayName("an SRG-named 1.18.2 material query is repaired before the remap nulls Material.AIR")
    void srgMaterialQueryIsRepairedThroughTheTransform() {
        String state = "net/minecraft/world/level/block/state/BlockState";
        String material = "net/minecraft/world/level/material/Material";
        transformer.clearRedirectsForTesting();
        transformer.registerSrgNameMappings(java.util.Map.of("m_60767_", "getMaterial", "m_76333_", "isSolid"),
                java.util.Map.of("f_76296_", "AIR"));
        new com.retromod.shim.forge.Forge_1_19_4_to_1_20().registerRedirects(transformer);
        byte[] mob = method("solid", "(L" + state + ";)Z", mv -> {
            Label air = new Label();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, state, "m_60767_", "()L" + material + ";", false);
            mv.visitFieldInsn(GETSTATIC, material, "f_76296_", "L" + material + ";");
            mv.visitJumpInsn(IF_ACMPNE, air);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            mv.visitLabel(air);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, state, "m_60767_", "()L" + material + ";", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, material, "m_76333_", "()Z", false);
            mv.visitInsn(IRETURN);
        });
        byte[] out = transformer.transformClass(mob, "test/Mob");
        assertEquals(List.of("BlockBehaviour$BlockStateBase.isAir", "BlockBehaviour$BlockStateBase.isSolid"),
                calls(out));
        assertTrue(code(out).stream().noneMatch(i -> i.getOpcode() == ACONST_NULL),
                "Material.AIR is not left as a nulled constant");
        assertTrue(code(out).stream().anyMatch(i -> i.getOpcode() == IFEQ), "!= AIR becomes a not-air branch");
    }

    @Test
    @DisplayName("1.18.2 game events and the mob spawn packet follow their 1.19 names")
    void gameEventsAndMobPacketFollowTheRename() {
        transformer.clearRedirectsForTesting();
        LegacyMinecraft119Bridge.register(transformer);
        String gameEvent = "net/minecraft/world/level/gameevent/GameEvent";
        byte[] mob = method("event", "(Lnet/minecraft/network/protocol/game/ClientboundAddMobPacket;)Ljava/lang/Object;",
                mv -> {
                    mv.visitFieldInsn(GETSTATIC, gameEvent, "MOB_INTERACT", "L" + gameEvent + ";");
                    mv.visitInsn(ARETURN);
                });
        byte[] out = transformer.transformClass(mob, "test/Mob");
        FieldInsnNode read = (FieldInsnNode) code(out).stream()
                .filter(i -> i instanceof FieldInsnNode).findFirst().orElseThrow();
        assertEquals("ENTITY_INTERACT", read.name);
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        assertTrue(node.methods.get(0).desc.contains("ClientboundAddEntityPacket"),
                "a recreateFromPacket override now names the merged packet: " + node.methods.get(0).desc);
    }

    @Test
    @DisplayName("ProjectileUtil.getHitResult(entity, filter) is getHitResultOnMoveVector on 1.20")
    void projectileHitTestFollowsTheRename() {
        transformer.clearRedirectsForTesting();
        new com.retromod.shim.forge.Forge_1_19_4_to_1_20().registerRedirects(transformer);
        String util = "net/minecraft/world/entity/projectile/ProjectileUtil";
        byte[] mob = method("hit", "(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)Ljava/lang/Object;",
                mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitMethodInsn(INVOKESTATIC, util, "getHitResult", "(Lnet/minecraft/world/entity/Entity;"
                            + "Ljava/util/function/Predicate;)Lnet/minecraft/world/phys/HitResult;", false);
                    mv.visitInsn(ARETURN);
                });
        assertEquals(List.of("ProjectileUtil.getHitResultOnMoveVector"),
                calls(transformer.transformClass(mob, "test/Mob")));
    }

    private static byte[] method(String name, String desc, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Mob", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<AbstractInsnNode> code(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<AbstractInsnNode> code = new ArrayList<>();
        node.methods.get(0).instructions.forEach(code::add);
        return code;
    }

    /** Calls as simple owner name and method, so embedded owners compare equal. */
    private static List<String> calls(byte[] classBytes) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : code(classBytes)) {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            }
        }
        return calls;
    }
}
