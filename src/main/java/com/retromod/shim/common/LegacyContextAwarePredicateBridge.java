/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Keeps a 1.20.2 to 26.2 advancement trigger working on 26.3, which replaced ContextAwarePredicate with a holder of a loot condition. */
public final class LegacyContextAwarePredicateBridge {

    static final String OLD = "net/minecraft/advancements/predicates/ContextAwarePredicate";
    static final String HOLDER = "net/minecraft/core/Holder";
    static final String CONDITION = "net/minecraft/world/level/storage/loot/predicates/LootItemCondition";
    static final String HELPER = "com/retromod/generated/LegacyContextAwarePredicates";
    private static final String ENTITY_PREDICATE = "net/minecraft/advancements/predicates/entity/EntityPredicate";
    private static final String LOOT_CONTEXT = "net/minecraft/world/level/storage/loot/LootContext";
    private static final String VALIDATION_CONTEXT = "net/minecraft/world/level/storage/loot/ValidationContext";
    private static final String CODEC = "Lcom/mojang/serialization/Codec;";

    // A redirect is matched on the owner the class remapper hands it, which is the old name in an
    // early pass and the holder once the class redirect has run, so each is keyed on both.
    private static final List<String> OWNERS = List.of(OLD, HOLDER);

    private LegacyContextAwarePredicateBridge() {}

    public static void register(RetromodTransformer t) {
        if (hostReplacedPredicate()) registerBridge(t);
    }

    /** Read from the host when it can be; offline, the target version decides. */
    static boolean hostReplacedPredicate() {
        if (ClassResourceInspector.exists(CONDITION)) return !ClassResourceInspector.exists(OLD);
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
    }

    static void registerBridge(RetromodTransformer t) {
        t.registerSyntheticClass(HELPER, generate());
        String holder = "L" + HOLDER + ";";
        for (String owner : OWNERS) {
            t.registerMethodRedirect(owner, "matches", "(L" + LOOT_CONTEXT + ";)Z",
                    HELPER, "matches", "(" + holder + "L" + LOOT_CONTEXT + ";)Z", true);
            t.registerMethodRedirect(owner, "validate", "(L" + VALIDATION_CONTEXT + ";)V",
                    HELPER, "validate", "(" + holder + "L" + VALIDATION_CONTEXT + ";)V", true);
            t.registerMethodRedirect(owner, "create", "([L" + CONDITION + ";)L" + owner + ";",
                    HELPER, "create", "([L" + CONDITION + ";)" + holder);
            t.registerConstructorRedirect(owner, "(Ljava/util/List;)V", HELPER, "of", "(Ljava/util/List;)" + holder);
            // The predicate's own codec reads the same JSON as the loot condition codec.
            t.registerFieldRedirect(owner, "CODEC", CODEC, CONDITION, "CODEC", CODEC);
        }
        // A trigger reads its player predicate with this codec; 26.3 reads it as a loot condition.
        t.registerFieldRedirect(ENTITY_PREDICATE, "ADVANCEMENT_CODEC", CODEC, CONDITION, "CODEC", CODEC);
        // EntityPredicate.wrap and SimpleInstance.player keep their names and change only this type.
        t.registerClassRedirect(OLD, HOLDER);
    }

    /** The removed predicate's members, over a holder of one loot condition. */
    static byte[] generate() {
        String holder = "L" + HOLDER + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);

        // matches(predicate, context): the condition tests the context.
        MethodVisitor matches = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "matches", "(" + holder + "L" + LOOT_CONTEXT + ";)Z", null, null);
        matches.visitCode();
        matches.visitVarInsn(ALOAD, 0);
        matches.visitMethodInsn(INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true);
        matches.visitTypeInsn(CHECKCAST, CONDITION);
        matches.visitVarInsn(ALOAD, 1);
        matches.visitMethodInsn(INVOKEINTERFACE, CONDITION, "test", "(Ljava/lang/Object;)Z", true);
        matches.visitInsn(IRETURN);
        matches.visitMaxs(0, 0);
        matches.visitEnd();

        // validate(predicate, context): validated as the holder the host validates.
        MethodVisitor validate = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "validate", "(" + holder + "L" + VALIDATION_CONTEXT + ";)V", null, null);
        validate.visitCode();
        validate.visitVarInsn(ALOAD, 1);
        validate.visitVarInsn(ALOAD, 0);
        validate.visitMethodInsn(INVOKESTATIC, "net/minecraft/world/level/storage/loot/Validatable", "validateHolder",
                "(L" + VALIDATION_CONTEXT + ";" + holder + ")V", true);
        validate.visitInsn(RETURN);
        validate.visitMaxs(0, 0);
        validate.visitEnd();

        // create(conditions...): the same as the list form.
        MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_VARARGS, "create", "([L" + CONDITION + ";)" + holder, null, null);
        create.visitCode();
        create.visitVarInsn(ALOAD, 0);
        create.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false);
        create.visitMethodInsn(INVOKESTATIC, HELPER, "of", "(Ljava/util/List;)" + holder, false);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        // of(conditions): one condition as itself, any other count as all of them, as the old predicate tested.
        MethodVisitor of = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "of", "(Ljava/util/List;)" + holder, null, null);
        of.visitCode();
        Label several = new Label();
        of.visitVarInsn(ALOAD, 0);
        of.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        of.visitInsn(ICONST_1);
        of.visitJumpInsn(IF_ICMPNE, several);
        of.visitVarInsn(ALOAD, 0);
        of.visitInsn(ICONST_0);
        of.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        of.visitMethodInsn(INVOKESTATIC, HOLDER, "direct", "(Ljava/lang/Object;)" + holder, true);
        of.visitInsn(ARETURN);
        of.visitLabel(several);
        of.visitTypeInsn(NEW, "java/util/ArrayList");
        of.visitInsn(DUP);
        of.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        of.visitVarInsn(ASTORE, 1);
        of.visitVarInsn(ALOAD, 0);
        of.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        of.visitVarInsn(ASTORE, 2);
        Label loop = new Label();
        Label done = new Label();
        of.visitLabel(loop);
        of.visitVarInsn(ALOAD, 2);
        of.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        of.visitJumpInsn(IFEQ, done);
        of.visitVarInsn(ALOAD, 1);
        of.visitVarInsn(ALOAD, 2);
        of.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        of.visitMethodInsn(INVOKESTATIC, HOLDER, "direct", "(Ljava/lang/Object;)" + holder, true);
        of.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        of.visitInsn(POP);
        of.visitJumpInsn(GOTO, loop);
        of.visitLabel(done);
        of.visitVarInsn(ALOAD, 1);
        of.visitMethodInsn(INVOKESTATIC, "net/minecraft/core/HolderSet", "direct", "(Ljava/util/List;)Lnet/minecraft/core/HolderSet$Direct;", true);
        of.visitMethodInsn(INVOKESTATIC, "net/minecraft/world/level/storage/loot/predicates/AllOfCondition", "allOf",
                "(Lnet/minecraft/core/HolderSet;)Lnet/minecraft/world/level/storage/loot/predicates/AllOfCondition;", false);
        of.visitMethodInsn(INVOKESTATIC, HOLDER, "direct", "(Ljava/lang/Object;)" + holder, true);
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
