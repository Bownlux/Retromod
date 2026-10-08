/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/**
 * #311: Isle of Berk dragons on Forge 1.20.1 ignored their rider and stopped the server when they
 * jumped, looked for food, or exploded, on entity members that 1.19 and 1.20 changed.
 */
class LegacyEntityApi120AdapterTest {

    private static final String DRAGON = "com/example/Dragon";
    private static final String BLAST = "com/example/FireBlast";
    private static final String TAMABLE = "net/minecraft/world/entity/TamableAnimal";
    private static final String ENTITY = LegacyEntityApi120Adapter.ENTITY;
    private static final String BLOCK_POS = "Lnet/minecraft/core/BlockPos;";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void setUp() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        transformer.clearRedirectsForTesting();
    }

    @Test
    void passengerOverrideIsNarrowedSoItOverridesTheHostMethod() {
        byte[] dragon = classOf(DRAGON, TAMABLE, "getControllingPassenger",
                LegacyEntityApi120Adapter.OLD_PASSENGER, ACC_PUBLIC, mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitInsn(ARETURN);
                });

        byte[] repaired = LegacyEntityApi120Adapter.apply(dragon, transformer, Map.<String, byte[]>of()::get);

        MethodNode override = method(repaired, "getControllingPassenger");
        assertEquals(LegacyEntityApi120Adapter.NEW_PASSENGER, override.desc,
                "1.20 returns LivingEntity; the old Entity return no longer overrides it");
        assertTrue(calls(override).contains(LegacyEntityApi120Adapter.SYNTH + ".asLiving"),
                "a passenger that is not living is returned as no controller");
        verify(repaired);
    }

    @Test
    void passengerOverrideAlreadyNarrowedKeepsItsBridgeApart() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, DRAGON, null, TAMABLE, null);
        MethodVisitor narrowed = cw.visitMethod(ACC_PUBLIC, "getControllingPassenger",
                LegacyEntityApi120Adapter.NEW_PASSENGER, null, null);
        narrowed.visitCode();
        narrowed.visitInsn(ACONST_NULL);
        narrowed.visitInsn(ARETURN);
        narrowed.visitMaxs(0, 0);
        narrowed.visitEnd();
        // javac's bridge for the covariant LivingEntity override, as a 1.18.2 compiler emitted it
        MethodVisitor bridge = cw.visitMethod(ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC, "getControllingPassenger",
                LegacyEntityApi120Adapter.OLD_PASSENGER, null, null);
        bridge.visitCode();
        bridge.visitVarInsn(ALOAD, 0);
        bridge.visitMethodInsn(INVOKEVIRTUAL, DRAGON, "getControllingPassenger",
                LegacyEntityApi120Adapter.NEW_PASSENGER, false);
        bridge.visitInsn(ARETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        cw.visitEnd();

        byte[] repaired = LegacyEntityApi120Adapter.apply(cw.toByteArray(), transformer,
                Map.<String, byte[]>of()::get);

        ClassNode node = new ClassNode();
        new ClassReader(repaired).accept(node, 0);
        assertEquals(2, node.methods.stream().map(m -> m.name + m.desc).distinct().count(),
                "narrowing the bridge too would declare the LivingEntity method twice");
        verify(repaired);
    }

    @Test
    void entityCallsFollowTheNewShapes() {
        byte[] dragon = classOf(DRAGON, TAMABLE, "ride", "()V", ACC_PUBLIC, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, DRAGON, "getControllingPassenger",
                    LegacyEntityApi120Adapter.OLD_PASSENGER, false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, DRAGON, "getJumpBoostPower", "()D", false);
            mv.visitInsn(POP2);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, DRAGON, "eyeBlockPosition", "()" + BLOCK_POS, false);
            mv.visitMethodInsn(INVOKEVIRTUAL, DRAGON, "gameEvent",
                    LegacyEntityApi120Adapter.OLD_GAME_EVENT, false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(FCONST_0);
            mv.visitFieldInsn(PUTFIELD, DRAGON, "flyingSpeed", "F");
            mv.visitInsn(RETURN);
        });

        byte[] repaired = LegacyEntityApi120Adapter.apply(dragon, transformer, Map.<String, byte[]>of()::get);

        MethodNode ride = method(repaired, "ride");
        assertEquals(List.of(DRAGON + ".getControllingPassenger" + LegacyEntityApi120Adapter.NEW_PASSENGER,
                DRAGON + ".getJumpBoostPower()F",
                LegacyEntityApi120Adapter.SYNTH + ".eyeBlockPosition(L" + ENTITY + ";)" + BLOCK_POS,
                LegacyEntityApi120Adapter.SYNTH + ".gameEvent(L" + ENTITY + ";"
                        + LegacyEntityApi120Adapter.OLD_GAME_EVENT.substring(1),
                DRAGON + ".retromod$setFlyingSpeed(F)V"),
                callsWithDesc(ride));
        assertTrue(opcodes(ride).contains(F2D),
                "the float jump power is widened back to the double the mod expects");
        assertFalse(opcodes(ride).contains(PUTFIELD), "the removed flyingSpeed field is not written");
        MethodNode getter = method(repaired, "getFlyingSpeed");
        assertEquals("()F", getter.desc, "the host reads the mob's speed through its getter");
        verify(repaired);
    }

    @Test
    void anotherMobsFlyingSpeedWriteIsDropped() {
        byte[] rider = classOf(DRAGON, TAMABLE, "steer", "(L" + TAMABLE + ";)V", ACC_PUBLIC, mv -> {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitInsn(FCONST_0);
            mv.visitFieldInsn(PUTFIELD, TAMABLE, "flyingSpeed", "F");
            mv.visitInsn(RETURN);
        });

        byte[] repaired = LegacyEntityApi120Adapter.apply(rider, transformer, Map.<String, byte[]>of()::get);

        assertTrue(opcodes(method(repaired, "steer")).contains(POP2),
                "there is no field to write on a mob the class does not own");
        verify(repaired);
    }

    @Test
    void flyingSpeedFieldTheModDeclaresStaysAField() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, DRAGON, null, TAMABLE, null);
        cw.visitField(ACC_PRIVATE, "flyingSpeed", "F", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "glide", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(FCONST_1);
        mv.visitFieldInsn(PUTFIELD, DRAGON, "flyingSpeed", "F");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] dragon = cw.toByteArray();

        assertSame(dragon, LegacyEntityApi120Adapter.apply(dragon, transformer, Map.<String, byte[]>of()::get),
                "the mod's own field is not the removed Minecraft one, so no getter override is added");
    }

    @Test
    void doubleJumpPowerOverrideGetsTheFloatMethod() {
        byte[] dragon = classOf(DRAGON, TAMABLE, "getJumpBoostPower", "()D", ACC_PUBLIC, mv -> {
            mv.visitInsn(DCONST_1);
            mv.visitInsn(DRETURN);
        });

        byte[] repaired = LegacyEntityApi120Adapter.apply(dragon, transformer, Map.<String, byte[]>of()::get);

        ClassNode node = new ClassNode();
        new ClassReader(repaired).accept(node, 0);
        MethodNode bridge = node.methods.stream()
                .filter(m -> m.name.equals("getJumpBoostPower") && m.desc.equals("()F")).findFirst().orElseThrow();
        assertEquals(List.of(DRAGON + ".getJumpBoostPower()D"), callsWithDesc(bridge),
                "the host's float method returns the mod's double override");
        verify(repaired);
    }

    @Test
    void membersTheModDeclaresItselfAreLeftAlone() {
        byte[] dragon = classOf(DRAGON, TAMABLE, "eyeBlockPosition", "()" + BLOCK_POS, ACC_PUBLIC, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, DRAGON, "eyeBlockPosition", "()" + BLOCK_POS, false);
            mv.visitInsn(ARETURN);
        });
        assertSame(dragon, LegacyEntityApi120Adapter.apply(dragon, transformer, Map.<String, byte[]>of()::get));

        byte[] notAnEntity = classOf("com/example/Helper", "java/lang/Object", "getControllingPassenger",
                LegacyEntityApi120Adapter.OLD_PASSENGER, ACC_PUBLIC, mv -> {
                    mv.visitInsn(ACONST_NULL);
                    mv.visitInsn(ARETURN);
                });
        assertSame(notAnEntity, LegacyEntityApi120Adapter.apply(notAnEntity, transformer,
                Map.<String, byte[]>of()::get));
    }

    @Test
    void explosionSubclassReadsTheNewMembers() {
        byte[] blast = classOf(BLAST, LegacyEntityApi120Adapter.EXPLOSION, "finish", "()V", ACC_PUBLIC, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, BLAST, "toBlow", LegacyEntityApi120Adapter.OLD_TO_BLOW);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, BLAST, "getSourceMob",
                    "()Lnet/minecraft/world/entity/LivingEntity;", false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, BLAST, "random", LegacyRandomSourceAdapter.RANDOM);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });

        byte[] repaired = LegacyEntityApi120Adapter.apply(blast, transformer, Map.<String, byte[]>of()::get);
        repaired = LegacyRandomSourceAdapter.apply(repaired, transformer, Map.<String, byte[]>of()::get);

        MethodNode finish = method(repaired, "finish");
        List<String> fields = new ArrayList<>();
        for (AbstractInsnNode insn : finish.instructions) {
            if (insn instanceof FieldInsnNode field) fields.add(field.name + ":" + field.desc);
        }
        assertEquals(List.of("toBlow:" + LegacyEntityApi120Adapter.NEW_TO_BLOW,
                "random:" + LegacyRandomSourceAdapter.RANDOM_SOURCE), fields);
        assertTrue(calls(finish).contains(BLAST + ".getIndirectSourceEntity"),
                "1.20 renamed Explosion.getSourceMob, saw " + calls(finish));
        verify(repaired);
    }

    @Test
    void oldRegistryAndTagConstantsFollowTheirMoves() {
        new Forge_1_19_2_to_1_19_3().registerRedirects(transformer);
        new Forge_1_19_4_to_1_20().registerRedirects(transformer);
        byte[] mod = classOf("com/example/Particles", "java/lang/Object", "probe", "()V",
                ACC_PUBLIC | ACC_STATIC, mv -> {
                    mv.visitFieldInsn(GETSTATIC, "net/minecraft/core/Registry", "PARTICLE_TYPE",
                            "Lnet/minecraft/core/Registry;");
                    mv.visitInsn(POP);
                    mv.visitFieldInsn(GETSTATIC, "net/minecraft/tags/BlockTags", "REPLACEABLE_PLANTS",
                            "Lnet/minecraft/tags/TagKey;");
                    mv.visitInsn(POP);
                    mv.visitInsn(RETURN);
                });

        MethodNode probe = method(transformer.transformClass(mod, "com/example/Particles"), "probe");
        List<String> reads = new ArrayList<>();
        for (AbstractInsnNode insn : probe.instructions) {
            if (insn instanceof FieldInsnNode field) reads.add(field.owner + "." + field.name);
        }
        assertEquals(List.of("net/minecraft/core/registries/BuiltInRegistries.PARTICLE_TYPE",
                "net/minecraft/tags/BlockTags.REPLACEABLE_BY_TREES"), reads);
        assertTrue(LegacyEntityApi120Adapter.isRegistered(transformer));
    }

    @Test
    void generatedHelpersAreValid() {
        verify(LegacyEntityApi120Adapter.generate());
    }

    private static byte[] classOf(String name, String superName, String methodName, String desc,
            int access, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        MethodVisitor mv = cw.visitMethod(access, methodName, desc, null, null);
        mv.visitCode();
        body.accept(mv);
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

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        return calls;
    }

    private static List<String> callsWithDesc(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static List<Integer> opcodes(MethodNode method) {
        List<Integer> opcodes = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) opcodes.add(insn.getOpcode());
        return opcodes;
    }

    private static void verify(byte[] classBytes) {
        new ClassReader(classBytes).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }
}
