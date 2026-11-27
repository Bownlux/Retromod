/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges {@code PotionUtils}, which 1.20.5 deleted when a potion item's potion and custom effects
 * became the {@code potion_contents} component.
 *
 * <p>A Mojang-named mod that tags a potion stack, such as a Forge 1.20.1 mod building a brewing
 * recipe output, fails with {@code NoClassDefFoundError} as soon as that code runs. The class is
 * redirected to a generated one that keeps the old static methods and reads or writes the
 * component. It covers {@code setPotion}, {@code setCustomEffects}, {@code getPotion},
 * {@code getMobEffects}, and {@code getCustomEffects}. Other old helpers, such as {@code getColor},
 * still fail when called.
 * {@code getPotion} returns null for a stack without a potion, where 1.20.1 returned the empty
 * potion that no longer exists.
 *
 * <p>The host decides whether this applies: {@code PotionUtils} must be gone and
 * {@code PotionContents} must have its 1.20.5 builders.
 */
public final class LegacyPotionUtilsBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String POTION_UTILS = "net/minecraft/world/item/alchemy/PotionUtils";
    static final String GENERATED = "com/retromod/generated/LegacyPotionUtils";
    static final String CONTENTS = "net/minecraft/world/item/alchemy/PotionContents";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String POTION = "net/minecraft/world/item/alchemy/Potion";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String EFFECT = "net/minecraft/world/effect/MobEffectInstance";
    private static final String COMPONENT_TYPE = "net/minecraft/core/component/DataComponentType";

    private static final String L_STACK = "L" + ITEM_STACK + ";";
    private static final String L_CONTENTS = "L" + CONTENTS + ";";

    private LegacyPotionUtilsBridge() {}

    public static void register(RetromodTransformer transformer) {
        if (ClassResourceInspector.exists(POTION_UTILS)) return;
        ClassNode contents = ClassResourceInspector.read(CONTENTS);
        if (contents == null
                || !hasMethod(contents, "withPotion", "(L" + HOLDER + ";)" + L_CONTENTS)
                || !hasMethod(contents, "withEffectAdded", "(L" + EFFECT + ";)" + L_CONTENTS)) {
            LOGGER.debug("PotionUtils support is not needed or the host PotionContents is unfamiliar");
            return;
        }
        registerRedirects(transformer);
    }

    static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(GENERATED, generate());
        transformer.registerClassRedirect(POTION_UTILS, GENERATED);
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, GENERATED, null,
                "java/lang/Object", null);

        // setPotion(stack, potion): contents(stack).withPotion(POTION.wrapAsHolder(potion))
        MethodVisitor setPotion = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setPotion",
                "(" + L_STACK + "L" + POTION + ";)" + L_STACK, null, null);
        setPotion.visitCode();
        setPotion.visitVarInsn(Opcodes.ALOAD, 0);
        loadContents(setPotion);
        setPotion.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries", "POTION",
                "L" + REGISTRY + ";");
        setPotion.visitVarInsn(Opcodes.ALOAD, 1);
        setPotion.visitMethodInsn(Opcodes.INVOKEINTERFACE, REGISTRY, "wrapAsHolder",
                "(Ljava/lang/Object;)L" + HOLDER + ";", true);
        setPotion.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTENTS, "withPotion",
                "(L" + HOLDER + ";)" + L_CONTENTS, false);
        storeContentsAndReturnStack(setPotion);

        // setCustomEffects(stack, effects): adds each effect, as the old method appended them
        MethodVisitor setEffects = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setCustomEffects",
                "(" + L_STACK + "Ljava/util/Collection;)" + L_STACK, null, null);
        setEffects.visitCode();
        setEffects.visitVarInsn(Opcodes.ALOAD, 0);
        loadContents(setEffects);
        setEffects.visitVarInsn(Opcodes.ASTORE, 2);
        setEffects.visitVarInsn(Opcodes.ALOAD, 1);
        setEffects.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator",
                "()Ljava/util/Iterator;", true);
        setEffects.visitVarInsn(Opcodes.ASTORE, 3);
        Label loop = new Label();
        Label done = new Label();
        setEffects.visitLabel(loop);
        setEffects.visitVarInsn(Opcodes.ALOAD, 3);
        setEffects.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        setEffects.visitJumpInsn(Opcodes.IFEQ, done);
        setEffects.visitVarInsn(Opcodes.ALOAD, 2);
        setEffects.visitVarInsn(Opcodes.ALOAD, 3);
        setEffects.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        setEffects.visitTypeInsn(Opcodes.CHECKCAST, EFFECT);
        setEffects.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTENTS, "withEffectAdded",
                "(L" + EFFECT + ";)" + L_CONTENTS, false);
        setEffects.visitVarInsn(Opcodes.ASTORE, 2);
        setEffects.visitJumpInsn(Opcodes.GOTO, loop);
        setEffects.visitLabel(done);
        setEffects.visitVarInsn(Opcodes.ALOAD, 2);
        storeContentsAndReturnStack(setEffects);

        // getPotion(stack): the potion, or null when the stack has none
        MethodVisitor getPotion = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getPotion",
                "(" + L_STACK + ")L" + POTION + ";", null, null);
        getPotion.visitCode();
        getPotion.visitVarInsn(Opcodes.ALOAD, 0);
        loadContents(getPotion);
        getPotion.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTENTS, "potion", "()Ljava/util/Optional;", false);
        getPotion.visitInsn(Opcodes.ACONST_NULL);
        getPotion.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElse",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        getPotion.visitInsn(Opcodes.DUP);
        Label none = new Label();
        getPotion.visitJumpInsn(Opcodes.IFNULL, none);
        getPotion.visitTypeInsn(Opcodes.CHECKCAST, HOLDER);
        getPotion.visitMethodInsn(Opcodes.INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true);
        getPotion.visitTypeInsn(Opcodes.CHECKCAST, POTION);
        getPotion.visitInsn(Opcodes.ARETURN);
        getPotion.visitLabel(none);
        getPotion.visitInsn(Opcodes.POP);
        getPotion.visitInsn(Opcodes.ACONST_NULL);
        getPotion.visitInsn(Opcodes.ARETURN);
        getPotion.visitMaxs(0, 0);
        getPotion.visitEnd();

        // getMobEffects(stack): the potion's effects, then the custom ones, in a new list as 1.20.1 returned
        MethodVisitor allEffects = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getMobEffects",
                "(" + L_STACK + ")Ljava/util/List;", null, null);
        allEffects.visitCode();
        allEffects.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        allEffects.visitInsn(Opcodes.DUP);
        allEffects.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        allEffects.visitVarInsn(Opcodes.ASTORE, 1);
        allEffects.visitVarInsn(Opcodes.ALOAD, 0);
        loadContents(allEffects);
        allEffects.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTENTS, "getAllEffects", "()Ljava/lang/Iterable;", false);
        allEffects.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true);
        allEffects.visitVarInsn(Opcodes.ASTORE, 2);
        Label nextEffect = new Label();
        Label allAdded = new Label();
        allEffects.visitLabel(nextEffect);
        allEffects.visitVarInsn(Opcodes.ALOAD, 2);
        allEffects.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        allEffects.visitJumpInsn(Opcodes.IFEQ, allAdded);
        allEffects.visitVarInsn(Opcodes.ALOAD, 1);
        allEffects.visitVarInsn(Opcodes.ALOAD, 2);
        allEffects.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        allEffects.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        allEffects.visitInsn(Opcodes.POP);
        allEffects.visitJumpInsn(Opcodes.GOTO, nextEffect);
        allEffects.visitLabel(allAdded);
        allEffects.visitVarInsn(Opcodes.ALOAD, 1);
        allEffects.visitInsn(Opcodes.ARETURN);
        allEffects.visitMaxs(0, 0);
        allEffects.visitEnd();

        // getCustomEffects(stack): a new list of only the custom effects
        MethodVisitor customEffects = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getCustomEffects",
                "(" + L_STACK + ")Ljava/util/List;", null, null);
        customEffects.visitCode();
        customEffects.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        customEffects.visitInsn(Opcodes.DUP);
        customEffects.visitVarInsn(Opcodes.ALOAD, 0);
        loadContents(customEffects);
        customEffects.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTENTS, "customEffects", "()Ljava/util/List;", false);
        customEffects.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(Ljava/util/Collection;)V", false);
        customEffects.visitInsn(Opcodes.ARETURN);
        customEffects.visitMaxs(0, 0);
        customEffects.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Stack: item stack. Leaves its potion contents, or the empty contents. */
    private static void loadContents(MethodVisitor mv) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/component/DataComponents", "POTION_CONTENTS",
                "L" + COMPONENT_TYPE + ";");
        mv.visitFieldInsn(Opcodes.GETSTATIC, CONTENTS, "EMPTY", L_CONTENTS);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getOrDefault",
                "(L" + COMPONENT_TYPE + ";Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, CONTENTS);
    }

    /** Stack: new contents. Writes them to the stack in local 0 and returns that stack. */
    private static void storeContentsAndReturnStack(MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/component/DataComponents", "POTION_CONTENTS",
                "L" + COMPONENT_TYPE + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "set",
                "(L" + COMPONENT_TYPE + ";Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }
}
