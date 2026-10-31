/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/** Keeps a NeoForge 1.21.1 loot modifier, built from a condition array, working on 26.2 and 26.3. */
public final class LegacyLootModifierBridge {

    static final String MODIFIER = "net/neoforged/neoforge/common/loot/LootModifier";
    /** 26.3 replaced the array with one optional condition, so its base is rebuilt in Java. */
    static final String BASE_26_3 = RebuiltApiClasses.ROOT + "neoforge/common/loot/LegacyLootModifier";
    /** 26.2 kept the array and added a priority, so its base only fills that in. */
    static final String BASE_26_2 = "com/retromod/generated/LegacyLootModifier262";
    static final String GETTER_26_2 = BASE_26_2 + "$Conditions";
    private static final String CONDITIONS = "[Lnet/minecraft/world/level/storage/loot/predicates/LootItemCondition;";
    private static final String CODEC_START =
            "(Lcom/mojang/serialization/codecs/RecordCodecBuilder$Instance;)Lcom/mojang/datafixers/Products$P1;";
    /** The priority NeoForge gives a modifier whose data names none. */
    private static final int DEFAULT_PRIORITY = 1000;

    private LegacyLootModifierBridge() {}

    public static void register(RetromodTransformer t) {
        String arrayInit = "(" + CONDITIONS + ")V";
        if (NeoForgeMovedMembers.moved(MODIFIER, "<init>", arrayInit, MODIFIER, "<init>", "(Ljava/util/Optional;I)V", "26.3")) {
            RebuiltApiClasses.registerTree(t, BASE_26_3);
            rebase(t, BASE_26_3);
        } else if (NeoForgeMovedMembers.moved(MODIFIER, "<init>", arrayInit, MODIFIER, "<init>", "(" + CONDITIONS + "I)V", "26.2")) {
            t.registerSyntheticClass(BASE_26_2, generateBase262());
            t.registerSyntheticClass(GETTER_26_2, generateGetter262());
            rebase(t, BASE_26_2);
        }
    }

    /** An old modifier extends the base, and a direct call to the old codec start reaches the base's. */
    private static void rebase(RetromodTransformer t, String base) {
        t.registerSuperclassRebase(MODIFIER, base);
        t.registerMethodRedirect(MODIFIER, "codecStart", CODEC_START, base, "codecStart", CODEC_START);
    }

    /** The 26.2 base: the old constructor with the default priority, and the one-field codec start. */
    static byte[] generateBase262() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, BASE_26_2, null, MODIFIER, null);
        cw.visitInnerClass(GETTER_26_2, BASE_26_2, "Conditions", ACC_STATIC | ACC_FINAL);

        MethodVisitor init = cw.visitMethod(ACC_PROTECTED, "<init>", "(" + CONDITIONS + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitIntInsn(SIPUSH, DEFAULT_PRIORITY);
        init.visitMethodInsn(INVOKESPECIAL, MODIFIER, "<init>", "(" + CONDITIONS + "I)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // instance.group(LOOT_CONDITIONS_CODEC.fieldOf("conditions").forGetter(modifier -> modifier.conditions))
        MethodVisitor start = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "codecStart", CODEC_START, null, null);
        start.visitCode();
        start.visitVarInsn(ALOAD, 0);
        start.visitFieldInsn(GETSTATIC, "net/neoforged/neoforge/common/loot/IGlobalLootModifier", "LOOT_CONDITIONS_CODEC",
                "Lcom/mojang/serialization/Codec;");
        start.visitLdcInsn("conditions");
        start.visitMethodInsn(INVOKEINTERFACE, "com/mojang/serialization/Codec", "fieldOf",
                "(Ljava/lang/String;)Lcom/mojang/serialization/MapCodec;", true);
        start.visitTypeInsn(NEW, GETTER_26_2);
        start.visitInsn(DUP);
        start.visitMethodInsn(INVOKESPECIAL, GETTER_26_2, "<init>", "()V", false);
        start.visitMethodInsn(INVOKEVIRTUAL, "com/mojang/serialization/MapCodec", "forGetter",
                "(Ljava/util/function/Function;)Lcom/mojang/serialization/codecs/RecordCodecBuilder;", false);
        start.visitMethodInsn(INVOKEVIRTUAL, "com/mojang/serialization/codecs/RecordCodecBuilder$Instance", "group",
                "(Lcom/mojang/datafixers/kinds/App;)Lcom/mojang/datafixers/Products$P1;", false);
        start.visitInsn(ARETURN);
        start.visitMaxs(0, 0);
        start.visitEnd();

        // The getter reads the protected array through this subclass, which may access it.
        MethodVisitor read = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "conditionsOf", "(Ljava/lang/Object;)" + CONDITIONS, null, null);
        read.visitCode();
        read.visitVarInsn(ALOAD, 0);
        read.visitTypeInsn(CHECKCAST, BASE_26_2);
        read.visitFieldInsn(GETFIELD, BASE_26_2, "conditions", CONDITIONS);
        read.visitInsn(ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The codec getter for the 26.2 base, a function that hands a modifier's conditions back. */
    static byte[] generateGetter262() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_FINAL | ACC_SUPER, GETTER_26_2, null, "java/lang/Object", new String[]{"java/util/function/Function"});
        cw.visitInnerClass(GETTER_26_2, BASE_26_2, "Conditions", ACC_STATIC | ACC_FINAL);
        MethodVisitor init = cw.visitMethod(0, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor apply = cw.visitMethod(ACC_PUBLIC, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        apply.visitCode();
        apply.visitVarInsn(ALOAD, 1);
        apply.visitMethodInsn(INVOKESTATIC, BASE_26_2, "conditionsOf", "(Ljava/lang/Object;)" + CONDITIONS, false);
        apply.visitInsn(ARETURN);
        apply.visitMaxs(0, 0);
        apply.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
