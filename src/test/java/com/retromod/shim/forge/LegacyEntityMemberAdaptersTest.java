/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Isle of Berk dragons stopped the server on their first tick on Forge 1.20.1 (#311). */
class LegacyEntityMemberAdaptersTest {

    private static final String DRAGON = "com/example/Dragon";
    private static final String GOAL = "com/example/FollowGoal";
    private static final String TAMABLE = "net/minecraft/world/entity/TamableAnimal";
    private static final List<LegacyEntityPrivateFieldAdapter.Accessor> SRG_LEVEL = List.of(
            new LegacyEntityPrivateFieldAdapter.Accessor("level",
                    LegacyEntityPrivateFieldAdapter.LEVEL_DESC, "f_19853_", "m_9236_", "m_284535_"));

    @Test
    void randomFieldReadIsRetypedAndWrapped() {
        byte[] reader = readerOf(DRAGON, "random", LegacyRandomSourceAdapter.RANDOM);
        byte[] repaired = LegacyRandomSourceAdapter.apply(reader,
                RetromodTransformer.getInstance(), Map.of(DRAGON, dragon())::get);

        List<String> insns = instructions(repaired);
        assertTrue(insns.contains("GET " + DRAGON + ".random:" + LegacyRandomSourceAdapter.RANDOM_SOURCE),
                "the read must name the 1.19 field type, saw " + insns);
        assertTrue(insns.contains("CALL " + LegacyRandomSourceAdapter.VIEW + ".wrap"),
                "the mod still expects a java.util.Random, saw " + insns);
    }

    @Test
    void modsOwnRandomFieldIsLeftAlone() {
        byte[] withOwnField = readerOf(GOAL, "random", LegacyRandomSourceAdapter.RANDOM);
        byte[] goal = classWithField(GOAL, "java/lang/Object", "random",
                LegacyRandomSourceAdapter.RANDOM);
        assertSame(withOwnField, LegacyRandomSourceAdapter.apply(withOwnField,
                RetromodTransformer.getInstance(), Map.of(GOAL, goal)::get));
    }

    @Test
    void entityLevelReadUsesTheAccessor() {
        byte[] reader = readerOf(DRAGON, "f_19853_", LegacyEntityPrivateFieldAdapter.LEVEL_DESC);
        byte[] repaired = LegacyEntityPrivateFieldAdapter.apply(reader, SRG_LEVEL,
                Map.of(DRAGON, dragon())::get);

        assertTrue(instructions(repaired).contains("CALL " + DRAGON + ".m_9236_"),
                "1.20 made Entity.level private; level() is the way in");
    }

    @Test
    void maxUpStepWriteUsesTheSetter() {
        byte[] writer = writerOf(DRAGON, "f_19793_", "F");
        byte[] repaired = LegacyEntityPrivateFieldAdapter.apply(writer, List.of(
                new LegacyEntityPrivateFieldAdapter.Accessor("maxUpStep", "F", "f_19793_",
                        "m_274421_", "m_274367_")), Map.of(DRAGON, dragon())::get);

        assertTrue(instructions(repaired).contains("CALL " + DRAGON + ".m_274367_"),
                "1.20 made Entity.maxUpStep private; every dragon constructor sets it");
    }

    @Test
    void entityGetLevelCallUsesTheRenamedAccessor() {
        byte[] caller = callerOf(DRAGON, "getLevel", "()" + LegacyEntityPrivateFieldAdapter.LEVEL_DESC);
        byte[] repaired = LegacyEntityPrivateFieldAdapter.apply(caller, SRG_LEVEL,
                Map.of(DRAGON, dragon())::get);

        assertTrue(instructions(repaired).contains("CALL " + DRAGON + ".m_9236_"),
                "1.20 renamed Entity.getLevel to level; a dragon's goal crashed on its next tick");
    }

    @Test
    void modsOwnGetLevelMethodIsLeftAlone() {
        String holder = "com/example/LevelHolder";
        String desc = "()" + LegacyEntityPrivateFieldAdapter.LEVEL_DESC;
        byte[] caller = callerOf(holder, "getLevel", desc);
        byte[] declaring = classWithMethod(holder, TAMABLE, "getLevel", desc);
        assertSame(caller, LegacyEntityPrivateFieldAdapter.apply(caller, SRG_LEVEL,
                Map.of(holder, declaring)::get),
                "a getLevel() the mod declares itself still links and must keep its name");
    }

    @Test
    void goalWithItsOwnLevelFieldIsLeftAlone() {
        byte[] reader = readerOf(GOAL, "level", LegacyEntityPrivateFieldAdapter.LEVEL_DESC);
        byte[] goal = classWithField(GOAL, "net/minecraft/world/entity/ai/goal/Goal", "level",
                LegacyEntityPrivateFieldAdapter.LEVEL_DESC);
        assertSame(reader, LegacyEntityPrivateFieldAdapter.apply(reader, SRG_LEVEL,
                Map.of(GOAL, goal)::get),
                "a goal's own level field is not the entity's");
    }

    @Test
    void entityGetRandomCallIsRetypedAndWrapped() {
        byte[] caller = callerOf(DRAGON, "getRandom", "()" + LegacyRandomSourceAdapter.RANDOM);
        byte[] repaired = LegacyRandomSourceAdapter.apply(caller,
                RetromodTransformer.getInstance(), Map.of(DRAGON, dragon())::get);

        assertTrue(instructions(repaired).contains("CALL " + LegacyRandomSourceAdapter.VIEW + ".wrap"),
                "an inherited LivingEntity.getRandom() still needs the Random view");
    }

    @Test
    void modsOwnGetRandomMethodIsLeftAlone() {
        String util = "com/example/DiceUtil";
        byte[] caller = callerOf(util, "getRandom", "()" + LegacyRandomSourceAdapter.RANDOM);
        byte[] dice = classWithMethod(util, "java/lang/Object", "getRandom",
                "()" + LegacyRandomSourceAdapter.RANDOM);
        assertSame(caller, LegacyRandomSourceAdapter.apply(caller,
                RetromodTransformer.getInstance(), Map.of(util, dice)::get),
                "a mod's own getRandom() returning java.util.Random did not change in 1.19");
    }

    @Test
    void randomFieldFromALibraryBaseClassIsLeftAlone() {
        String libraryEntity = "com/example/LibraryBackedMob";
        byte[] reader = readerOf(libraryEntity, "random", LegacyRandomSourceAdapter.RANDOM);
        byte[] mob = classWithField(libraryEntity, "org/library/AnimatedMob", null, null);
        assertSame(reader, LegacyRandomSourceAdapter.apply(reader,
                RetromodTransformer.getInstance(), Map.of(libraryEntity, mob)::get),
                "the chain leaves the jar through a library class, so the field is not Minecraft's");
    }

    @Test
    void goalInheritingMinecraftLevelFieldIsLeftAlone() {
        String breedGoal = "com/example/HatchGoal";
        byte[] reader = readerOf(breedGoal, "level", LegacyEntityPrivateFieldAdapter.LEVEL_DESC);
        byte[] goal = classWithField(breedGoal, "net/minecraft/world/entity/ai/goal/BreedGoal",
                null, null);
        assertSame(reader, LegacyEntityPrivateFieldAdapter.apply(reader, List.of(
                new LegacyEntityPrivateFieldAdapter.Accessor("level",
                        LegacyEntityPrivateFieldAdapter.LEVEL_DESC, "level", "level", "setLevel")),
                Map.of(breedGoal, goal)::get),
                "on a Mojang-named host BreedGoal.level shares the entity field's name");
    }

    private static byte[] dragon() {
        return classWithField(DRAGON, TAMABLE, null, null);
    }

    private static byte[] classWithField(String name, String superName, String field, String desc) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        if (field != null) {
            writer.visitField(Opcodes.ACC_PROTECTED, field, desc, null, null).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithMethod(String name, String superName, String method,
            String desc) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method, desc, null, null)
                .visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code static Object call(Owner o) { return o.<method>(); }} in a separate caller class. */
    private static byte[] callerOf(String owner, String method, String desc) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Caller", null,
                "java/lang/Object", null);
        MethodVisitor call = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read",
                "(L" + owner + ";)Ljava/lang/Object;", null, null);
        call.visitCode();
        call.visitVarInsn(Opcodes.ALOAD, 0);
        call.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, method, desc, false);
        call.visitInsn(Opcodes.ARETURN);
        call.visitMaxs(0, 0);
        call.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code static void write(Owner o) { o.<field> = 1.0F; }} in a separate caller class. */
    private static byte[] writerOf(String owner, String field, String desc) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Writer", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "write",
                "(L" + owner + ";)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.FCONST_1);
        method.visitFieldInsn(Opcodes.PUTFIELD, owner, field, desc);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code static Object read(Owner o) { return o.<field>; }} in a separate caller class. */
    private static byte[] readerOf(String owner, String field, String desc) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Reader", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read",
                "(L" + owner + ";)Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, field, desc);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> instructions(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> seen = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof FieldInsnNode field) {
                seen.add("GET " + field.owner + "." + field.name + ":" + field.desc);
            } else if (insn instanceof MethodInsnNode call) {
                seen.add("CALL " + call.owner + "." + call.name);
            }
        }
        assertEquals(1, node.methods.size(), "the fixture has a single method");
        return seen;
    }
}
