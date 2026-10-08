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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the food API that 1.20.5 turned into a data component, for Mojang-named mods built for
 * 1.20.4 or older (Forge 1.20.1 mods after the SRG remap).
 *
 * <p>{@code FoodProperties} became a record with {@code nutrition()} and an absolute
 * {@code saturation()}, the builder renamed {@code saturationMod} and {@code alwaysEat}, and
 * {@code meat()} became the {@code minecraft:meat} item tag. {@code Item.getFoodProperties()} and
 * {@code Item.isEdible()} were removed because food now lives in the item's components. A mod
 * usually builds its food in a static initializer, so one stale call stops the whole mod with
 * {@code NoSuchMethodError}.
 *
 * <p>Renamed members are redirected to the host member. Members without a same-shape replacement
 * go through a generated helper: the old saturation modifier is recovered from the absolute value,
 * fast food is an eat time under the normal 1.6 seconds, and effects are returned as the old
 * {@code Pair} list. {@code meat()} keeps the builder unchanged and {@code isMeat()} answers
 * false, since meat is now a tag the item has to be added to.
 *
 * <p>Each member is bridged only when the host proves the old member is gone and the replacement
 * exists. Offline, where the host classes are not readable, the host version decides.
 */
public final class LegacyFoodPropertiesBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/LegacyFoodApi";

    static final String FOOD = "net/minecraft/world/food/FoodProperties";
    static final String BUILDER = FOOD + "$Builder";
    static final String POSSIBLE_EFFECT = FOOD + "$PossibleEffect";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String COMPONENT_MAP = "net/minecraft/core/component/DataComponentMap";
    static final String COMPONENT_TYPE = "net/minecraft/core/component/DataComponentType";
    static final String COMPONENTS = "net/minecraft/core/component/DataComponents";
    static final String PAIR = "com/mojang/datafixers/util/Pair";
    static final String EFFECT_INSTANCE = "net/minecraft/world/effect/MobEffectInstance";

    private static final String L_FOOD = "L" + FOOD + ";";
    private static final String L_BUILDER = "L" + BUILDER + ";";

    /** The first version whose host has the record-shaped food API. */
    static final String RECORD_FOOD_VERSION = "1.20.5";

    /** A removed member and where its calls go: a host member, or the generated helper. */
    record Rewrite(String owner, String oldName, String oldDesc, String newName, String newDesc,
                   boolean viaHelper) {
        String helperDesc() {
            return "(L" + owner + ";" + oldDesc.substring(1);
        }
    }

    static List<Rewrite> candidates() {
        return List.of(
            new Rewrite(BUILDER, "saturationMod", "(F)" + L_BUILDER,
                    "saturationModifier", "(F)" + L_BUILDER, false),
            new Rewrite(BUILDER, "alwaysEat", "()" + L_BUILDER, "alwaysEdible", "()" + L_BUILDER, false),
            new Rewrite(BUILDER, "meat", "()" + L_BUILDER, "meat", null, true),
            new Rewrite(FOOD, "getNutrition", "()I", "nutrition", "()I", false),
            new Rewrite(FOOD, "getSaturationModifier", "()F", "getSaturationModifier", null, true),
            new Rewrite(FOOD, "isMeat", "()Z", "isMeat", null, true),
            new Rewrite(FOOD, "isFastFood", "()Z", "isFastFood", null, true),
            new Rewrite(FOOD, "getEffects", "()Ljava/util/List;", "getEffects", null, true),
            new Rewrite(ITEM, "getFoodProperties", "()" + L_FOOD, "getFoodProperties", null, true),
            new Rewrite(ITEM, "isEdible", "()Z", "isEdible", null, true));
    }

    private LegacyFoodPropertiesBridge() {}

    /** Registers the members this host replaced. */
    public static void register(RetromodTransformer transformer) {
        if (!hostHasRecordFood()) {
            LOGGER.debug("Legacy food API bridge not needed: the host keeps the old FoodProperties");
            return;
        }
        List<Rewrite> rewrites = new ArrayList<>();
        for (Rewrite rewrite : candidates()) {
            if (replacedOnHost(rewrite)) rewrites.add(rewrite);
        }
        registerRedirects(transformer, rewrites);
        LOGGER.info("Bridged {} removed food members", rewrites.size());
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, List<Rewrite> rewrites) {
        if (rewrites.stream().anyMatch(Rewrite::viaHelper)) {
            transformer.registerSyntheticClass(HELPER, generateHelper(rewrites));
        }
        for (Rewrite rewrite : rewrites) {
            if (rewrite.viaHelper()) {
                // Item subclasses call these as their own members, so follow the proven hierarchy.
                transformer.registerInheritedMethodRedirect(rewrite.owner(), rewrite.oldName(),
                        rewrite.oldDesc(), HELPER, rewrite.newName(), rewrite.helperDesc());
            } else {
                transformer.registerMethodRedirect(rewrite.owner(), rewrite.oldName(), rewrite.oldDesc(),
                        rewrite.owner(), rewrite.newName(), rewrite.newDesc());
            }
        }
    }

    /** The host's own FoodProperties decides; offline, the host version does. */
    static boolean hostHasRecordFood() {
        ClassNode food = ClassResourceInspector.read(FOOD);
        if (food != null) return hasMethod(food, "nutrition", "()I") && !hasMethod(food, "getNutrition", "()I");
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, RECORD_FOOD_VERSION) >= 0;
    }

    private static boolean replacedOnHost(Rewrite rewrite) {
        ClassNode owner = ClassResourceInspector.read(rewrite.owner());
        if (owner == null) return true; // offline: hostHasRecordFood already checked the version
        if (hasMethod(owner, rewrite.oldName(), rewrite.oldDesc())) return false;
        return rewrite.viaHelper() || hasMethod(owner, rewrite.newName(), rewrite.newDesc());
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    static byte[] generateHelper(List<Rewrite> rewrites) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        for (Rewrite rewrite : rewrites) {
            if (!rewrite.viaHelper()) continue;
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    rewrite.newName(), rewrite.helperDesc(), null, null);
            mv.visitCode();
            switch (rewrite.oldName()) {
                case "meat" -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitInsn(Opcodes.ARETURN);
                }
                case "getSaturationModifier" -> emitSaturationModifier(mv);
                case "isMeat" -> {
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                }
                case "isFastFood" -> emitIsFastFood(mv);
                case "getEffects" -> emitEffects(mv);
                case "getFoodProperties" -> emitItemFood(mv, false);
                case "isEdible" -> emitItemFood(mv, true);
                default -> throw new IllegalArgumentException("No helper for " + rewrite.oldName());
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** saturation = nutrition * modifier * 2 since 1.20.5, so the modifier is read back from it. */
    private static void emitSaturationModifier(MethodVisitor mv) {
        Label none = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FOOD, "nutrition", "()I", false);
        mv.visitJumpInsn(Opcodes.IFEQ, none);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FOOD, "saturation", "()F", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FOOD, "nutrition", "()I", false);
        mv.visitInsn(Opcodes.I2F);
        mv.visitLdcInsn(2.0f);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitInsn(Opcodes.FDIV);
        mv.visitInsn(Opcodes.FRETURN);
        mv.visitLabel(none);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FRETURN);
    }

    /** Normal food takes 1.6 seconds and fast food 0.8, so anything quicker than normal was fast. */
    private static void emitIsFastFood(MethodVisitor mv) {
        Label normal = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FOOD, "eatSeconds", "()F", false);
        mv.visitLdcInsn(1.6f);
        mv.visitInsn(Opcodes.FCMPG);
        mv.visitJumpInsn(Opcodes.IFGE, normal);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(normal);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
    }

    /** The old {@code List<Pair<MobEffectInstance, Float>>} built from the record's effects. */
    private static void emitEffects(MethodVisitor mv) {
        Label loop = new Label();
        Label done = new Label();
        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FOOD, "effects", "()Ljava/util/List;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, done);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, POSSIBLE_EFFECT);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POSSIBLE_EFFECT, "effect", "()L" + EFFECT_INSTANCE + ";", false);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POSSIBLE_EFFECT, "probability", "()F", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PAIR, "of",
                "(Ljava/lang/Object;Ljava/lang/Object;)L" + PAIR + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(done);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
    }

    /** Food is the item's {@code minecraft:food} component since 1.20.5. */
    private static void emitItemFood(MethodVisitor mv, boolean presence) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM, "components", "()L" + COMPONENT_MAP + ";", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, COMPONENTS, "FOOD", "L" + COMPONENT_TYPE + ";");
        if (presence) {
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COMPONENT_MAP, "has", "(L" + COMPONENT_TYPE + ";)Z", true);
            mv.visitInsn(Opcodes.IRETURN);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COMPONENT_MAP, "get",
                    "(L" + COMPONENT_TYPE + ";)Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, FOOD);
            mv.visitInsn(Opcodes.ARETURN);
        }
    }
}
