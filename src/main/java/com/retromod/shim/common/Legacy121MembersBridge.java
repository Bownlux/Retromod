/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** The pet follow goal, the curse checks and the navigation liquid check 1.21 hosts lack, for Mojang-named mods built for 1.20.4 or older. */
public final class Legacy121MembersBridge {

    static final String HELPER = "com/retromod/generated/Legacy121Members";
    static final String FOLLOW = "net/minecraft/world/entity/ai/goal/FollowOwnerGoal";
    static final String OLD_FOLLOW = "(Lnet/minecraft/world/entity/TamableAnimal;DFFZ)V";
    static final String NEW_FOLLOW = "(Lnet/minecraft/world/entity/TamableAnimal;DFF)V";
    static final String ENCHANTMENT_HELPER = "net/minecraft/world/item/enchantment/EnchantmentHelper";
    static final String EFFECTS = "net/minecraft/world/item/enchantment/EnchantmentEffectComponents";
    static final String COMPONENT_TYPE = "Lnet/minecraft/core/component/DataComponentType;";
    static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String CURSE = "(" + STACK + ")Z";
    /** Each old curse check and the enchantment effect that answers it now. */
    static final String[][] CURSES = {{"hasVanishingCurse", "PREVENT_EQUIPMENT_DROP"}, {"hasBindingCurse", "PREVENT_ARMOR_CHANGE"}};
    static final String NAVIGATION = "net/minecraft/world/entity/ai/navigation/PathNavigation";
    static final String NAVIGATIONS = RebuiltApiClasses.ROOT + "minecraft/world/entity/ai/navigation/LegacyPathNavigations";

    private Legacy121MembersBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        boolean follow = changed(host.apply(FOLLOW), OLD_FOLLOW, NEW_FOLLOW, "1.21");
        // 26.x answers the curse checks with its own rebuilt item calls.
        boolean curses = RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") < 0
                && changed(host.apply(ENCHANTMENT_HELPER), "hasVanishingCurse" + CURSE, "has(" + STACK + COMPONENT_TYPE + ")Z", "1.21");
        // A mod's own navigation asked itself whether its mob was in a liquid; the entity answers that now.
        ClassNode entity = host.apply(ENTITY);
        boolean entityAnswers = entity == null || entity.methods.stream().anyMatch(m -> "isInLiquid()Z".equals(m.name + m.desc));
        if (lacks(host.apply(NAVIGATION), "isInLiquid()Z", "1.21") && entityAnswers) {
            RebuiltApiClasses.registerTree(t, NAVIGATIONS);
            String helperDesc = "(L" + NAVIGATION + ";)Z";
            t.registerMethodRedirect(NAVIGATION, "isInLiquid", "()Z", NAVIGATIONS, "isInLiquid", helperDesc, true);
            t.registerInheritedMethodRedirect(NAVIGATION, "isInLiquid", "()Z", NAVIGATIONS, "isInLiquid", helperDesc);
        }
        if (!follow && !curses) return;
        t.registerSyntheticClass(HELPER, generate(follow, curses));
        if (follow) {
            // A tame mob's goal list builds the goal. The flag for pets that fly is gone, and they follow like the rest.
            t.registerConstructorRedirect(FOLLOW, OLD_FOLLOW, HELPER, "followOwnerGoal", OLD_FOLLOW.replace(")V", ")L" + FOLLOW + ";"));
            t.registerSuperConstructorAdapter(FOLLOW, OLD_FOLLOW, NEW_FOLLOW, mv -> mv.visitInsn(POP));
        }
        if (curses) {
            for (String[] curse : CURSES) t.registerMethodRedirect(ENCHANTMENT_HELPER, curse[0], CURSE, HELPER, curse[0], CURSE);
        }
    }

    /** Whether the host class lacks a method, name with descriptor; offline, from {@code since}. */
    private static boolean lacks(ClassNode owner, String method, String since) {
        if (owner == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0;
        return owner.methods.stream().noneMatch(m -> method.equals(m.name + m.desc));
    }

    /** Whether the host lacks {@code old} and has {@code current}, names with descriptors; offline, from {@code since}. */
    private static boolean changed(ClassNode owner, String old, String current, String since) {
        if (owner == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0;
        String oldKey = old.startsWith("(") ? "<init>" + old : old;
        String currentKey = current.startsWith("(") ? "<init>" + current : current;
        boolean hasOld = false;
        boolean hasCurrent = false;
        for (MethodNode method : owner.methods) {
            hasOld |= oldKey.equals(method.name + method.desc);
            hasCurrent |= currentKey.equals(method.name + method.desc);
        }
        return !hasOld && hasCurrent;
    }

    /** The goal factory and the two curse checks, each only where it is registered. */
    static byte[] generate(boolean follow, boolean curses) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        if (follow) {
            MethodVisitor goal = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "followOwnerGoal",
                    OLD_FOLLOW.replace(")V", ")L" + FOLLOW + ";"), null, null);
            goal.visitCode();
            goal.visitTypeInsn(NEW, FOLLOW);
            goal.visitInsn(DUP);
            goal.visitVarInsn(ALOAD, 0);
            goal.visitVarInsn(DLOAD, 1);
            goal.visitVarInsn(FLOAD, 3);
            goal.visitVarInsn(FLOAD, 4);
            goal.visitMethodInsn(INVOKESPECIAL, FOLLOW, "<init>", NEW_FOLLOW, false);
            goal.visitInsn(ARETURN);
            goal.visitMaxs(0, 0);
            goal.visitEnd();
        }
        if (curses) {
            for (String[] curse : CURSES) {
                MethodVisitor check = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, curse[0], CURSE, null, null);
                check.visitCode();
                check.visitVarInsn(ALOAD, 0);
                check.visitFieldInsn(GETSTATIC, EFFECTS, curse[1], COMPONENT_TYPE);
                check.visitMethodInsn(INVOKESTATIC, ENCHANTMENT_HELPER, "has", "(" + STACK + COMPONENT_TYPE + ")Z", false);
                check.visitInsn(IRETURN);
                check.visitMaxs(0, 0);
                check.visitEnd();
            }
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
