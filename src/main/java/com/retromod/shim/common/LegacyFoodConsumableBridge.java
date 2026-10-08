/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Carries 1.21.1 food builder settings into the {@code Consumable} component that replaced them in
 * 1.21.2.
 *
 * <p>Up to 1.21.1, {@code FoodProperties.Builder} held the eating time ({@code fast()}), the item
 * left behind ({@code usingConvertsTo}) and the status effects ({@code effect(instance,
 * probability)}). 1.21.2 kept only nutrition, saturation and {@code alwaysEdible} on the food and
 * moved the rest to {@code Item.Properties}: the effects and eating time live on a
 * {@code Consumable} passed to {@code food(food, consumable)}, and the leftover item is
 * {@code usingConvertsTo(Item)}. A 1.21.1 mod dies with {@code NoSuchMethodError} on the first
 * removed builder call. Dropping the call instead would make the food silently lose its effects.
 *
 * <p>The builder and the finished food are Minecraft objects with no room for extra state, so the
 * helper remembers the old settings beside them: per builder until {@code build()}, then per built
 * food until the mod hands it to {@code Item.Properties.food}. There the settings become an
 * {@code ApplyStatusEffectsConsumeEffect} per effect on a default food consumable, with the old
 * 0.8 second eating time for {@code fast()}. A food built without any removed call keeps the plain
 * {@code food(food)} path, so its defaults stay exactly the host's. The {@code build()} and
 * {@code food(food)} wrappers leave a {@code super} call in a mod's override alone, so the override
 * cannot recurse through them.
 *
 * <p>The same release made the eat and drink sounds {@code Holder}s.
 * {@link LegacyHolderConstantBridge} owns reads of those constants with their old
 * {@code SoundEvent} type.
 *
 * <p>The helper is generated because its descriptors name Minecraft types, and it is embedded
 * into each mod that uses it.
 */
public final class LegacyFoodConsumableBridge {

    private LegacyFoodConsumableBridge() {}

    public static final String INTERNAL =
            "com/retromod/shim/common/embedded/LegacyFoodConsumableBridge";

    static final String FOOD = "net/minecraft/world/food/FoodProperties";
    static final String BUILDER = "net/minecraft/world/food/FoodProperties$Builder";
    static final String EFFECT_INSTANCE = "net/minecraft/world/effect/MobEffectInstance";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String ITEM_PROPS = "net/minecraft/world/item/Item$Properties";
    static final String ITEM_LIKE = "net/minecraft/world/level/ItemLike";
    static final String CONSUMABLE = "net/minecraft/world/item/component/Consumable";
    static final String CONSUMABLE_BUILDER = "net/minecraft/world/item/component/Consumable$Builder";
    static final String CONSUMABLES = "net/minecraft/world/item/component/Consumables";
    static final String APPLY_EFFECTS =
            "net/minecraft/world/item/consume_effects/ApplyStatusEffectsConsumeEffect";
    static final String CONSUME_EFFECT = "net/minecraft/world/item/consume_effects/ConsumeEffect";

    static final String L_BUILDER = "L" + BUILDER + ";";
    static final String EFFECT_DESC = "(L" + EFFECT_INSTANCE + ";F)" + L_BUILDER;
    static final String EFFECT_HELPER_DESC = "(" + L_BUILDER + "L" + EFFECT_INSTANCE + ";F)" + L_BUILDER;
    static final String FAST_HELPER_DESC = "(" + L_BUILDER + ")" + L_BUILDER;
    static final String CONVERTS_DESC = "(L" + ITEM_LIKE + ";)" + L_BUILDER;
    static final String CONVERTS_HELPER_DESC = "(" + L_BUILDER + "L" + ITEM_LIKE + ";)" + L_BUILDER;
    static final String BUILD_HELPER_DESC = "(" + L_BUILDER + ")L" + FOOD + ";";
    static final String FOOD_DESC = "(L" + FOOD + ";)L" + ITEM_PROPS + ";";
    static final String FOOD_HELPER_DESC = "(L" + ITEM_PROPS + ";L" + FOOD + ";)L" + ITEM_PROPS + ";";

    /** {@code usingConvertsTo}'s intermediary name; it has no 26.x mapping because 1.21.2 removed it. */
    static final String CONVERTS_INTERMEDIARY = "method_60500";
    /** The eating time {@code fast()} set before 1.21.2, in seconds. */
    static final float FAST_EAT_SECONDS = 0.8F;

    private static final String PENDING = "PENDING";
    private static final String BUILT = "BUILT";
    private static final String MAP_DESC = "Ljava/util/Map;";
    /** Slots of the per-builder settings array. */
    private static final int CONSUMABLE_SLOT = 0;
    private static final int CONVERTS_TO_SLOT = 1;

    /** Registers the helper and routes the removed builder calls through it. */
    public static void register(RetromodTransformer transformer) {
        if (!transformer.getSyntheticClasses().containsKey(INTERNAL)) {
            transformer.registerSyntheticClass(INTERNAL, generate());
        }
        transformer.registerMethodRedirect(BUILDER, "effect", EFFECT_DESC,
                INTERNAL, "effect", EFFECT_HELPER_DESC);
        transformer.registerMethodRedirect(BUILDER, "fast", "()" + L_BUILDER,
                INTERNAL, "fast", FAST_HELPER_DESC);
        for (String name : new String[]{"usingConvertsTo", CONVERTS_INTERMEDIARY}) {
            transformer.registerMethodRedirect(BUILDER, name, CONVERTS_DESC,
                    INTERNAL, "usingConvertsTo", CONVERTS_HELPER_DESC);
        }
        // Both methods still exist on the host, so the helpers wrap them.
        transformer.registerWrappingMethodRedirect(BUILDER, "build", "()L" + FOOD + ";",
                INTERNAL, "build", BUILD_HELPER_DESC);
        transformer.registerWrappingMethodRedirect(ITEM_PROPS, "food", FOOD_DESC,
                INTERNAL, "food", FOOD_HELPER_DESC);
    }

    public static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, INTERNAL, null, "java/lang/Object", null);
        // Builders are short-lived, so they are weakly held. A built food is a record whose equals
        // compares values, so two foods with different effects can be equal; it is keyed by identity.
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, PENDING, MAP_DESC, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, BUILT, MAP_DESC, null, null).visitEnd();
        generateStaticInit(cw);
        generateSettings(cw);
        generateConsumable(cw);
        generateEffect(cw);
        generateFast(cw);
        generateUsingConvertsTo(cw);
        generateBuild(cw);
        generateFood(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void generateStaticInit(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        synchronizedMap(mv, "java/util/WeakHashMap");
        mv.visitFieldInsn(PUTSTATIC, INTERNAL, PENDING, MAP_DESC);
        synchronizedMap(mv, "java/util/IdentityHashMap");
        mv.visitFieldInsn(PUTSTATIC, INTERNAL, BUILT, MAP_DESC);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void synchronizedMap(MethodVisitor mv, String mapClass) {
        mv.visitTypeInsn(NEW, mapClass);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, mapClass, "<init>", "()V", false);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Collections", "synchronizedMap",
                "(Ljava/util/Map;)Ljava/util/Map;", false);
    }

    /** {@code static Object[] settings(Object builder)}: the builder's old settings, created on demand. */
    private static void generateSettings(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "settings",
                "(Ljava/lang/Object;)[Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, INTERNAL, PENDING, MAP_DESC);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_2);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "putIfAbsent",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, INTERNAL, PENDING, MAP_DESC);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/Object;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code static Consumable.Builder consumable(Object builder)}: a default food consumable on
     * first use.
     */
    private static void generateConsumable(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "consumable",
                "(Ljava/lang/Object;)L" + CONSUMABLE_BUILDER + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, INTERNAL, "settings",
                "(Ljava/lang/Object;)[Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitVarInsn(ALOAD, 1);
        pushInt(mv, CONSUMABLE_SLOT);
        mv.visitInsn(AALOAD);
        Label ready = new Label();
        mv.visitJumpInsn(IFNONNULL, ready);
        mv.visitVarInsn(ALOAD, 1);
        pushInt(mv, CONSUMABLE_SLOT);
        mv.visitMethodInsn(INVOKESTATIC, CONSUMABLES, "defaultFood",
                "()L" + CONSUMABLE_BUILDER + ";", false);
        mv.visitInsn(AASTORE);
        mv.visitLabel(ready);
        mv.visitVarInsn(ALOAD, 1);
        pushInt(mv, CONSUMABLE_SLOT);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, CONSUMABLE_BUILDER);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code effect(builder, instance, probability)}: one apply-effects consume effect per call. */
    private static void generateEffect(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "effect", EFFECT_HELPER_DESC,
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, INTERNAL, "consumable",
                "(Ljava/lang/Object;)L" + CONSUMABLE_BUILDER + ";", false);
        mv.visitTypeInsn(NEW, APPLY_EFFECTS);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(FLOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, APPLY_EFFECTS, "<init>",
                "(L" + EFFECT_INSTANCE + ";F)V", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, CONSUMABLE_BUILDER, "onConsume",
                "(L" + CONSUME_EFFECT + ";)L" + CONSUMABLE_BUILDER + ";", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code fast(builder)}: the old 0.8 second eating time. */
    private static void generateFast(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fast", FAST_HELPER_DESC,
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, INTERNAL, "consumable",
                "(Ljava/lang/Object;)L" + CONSUMABLE_BUILDER + ";", false);
        mv.visitLdcInsn(FAST_EAT_SECONDS);
        mv.visitMethodInsn(INVOKEVIRTUAL, CONSUMABLE_BUILDER, "consumeSeconds",
                "(F)L" + CONSUMABLE_BUILDER + ";", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code usingConvertsTo(builder, item)}: remembered until the food reaches the item. */
    private static void generateUsingConvertsTo(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "usingConvertsTo",
                CONVERTS_HELPER_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, INTERNAL, "settings",
                "(Ljava/lang/Object;)[Ljava/lang/Object;", false);
        pushInt(mv, CONVERTS_TO_SLOT);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(AASTORE);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code build(builder)}: builds the food and moves the builder's settings onto it. */
    private static void generateBuild(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "build", BUILD_HELPER_DESC,
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "build", "()L" + FOOD + ";", false);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitFieldInsn(GETSTATIC, INTERNAL, PENDING, MAP_DESC);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 2);
        Label done = new Label();
        mv.visitJumpInsn(IFNULL, done);
        mv.visitFieldInsn(GETSTATIC, INTERNAL, BUILT, MAP_DESC);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitLabel(done);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code food(props, food)}: a food with old settings gets them back as a consumable and a
     * leftover item; any other food takes the host's plain {@code food(food)}.
     */
    private static void generateFood(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "food", FOOD_HELPER_DESC,
                null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, INTERNAL, BUILT, MAP_DESC);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/Object;");
        mv.visitVarInsn(ASTORE, 2);

        Label hasSettings = new Label();
        mv.visitVarInsn(ALOAD, 2);
        mv.visitJumpInsn(IFNONNULL, hasSettings);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_PROPS, "food", FOOD_DESC, false);
        mv.visitInsn(ARETURN);

        mv.visitLabel(hasSettings);
        Label plainFood = new Label();
        Label foodSet = new Label();
        mv.visitVarInsn(ALOAD, 2);
        pushInt(mv, CONSUMABLE_SLOT);
        mv.visitInsn(AALOAD);
        mv.visitJumpInsn(IFNULL, plainFood);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        pushInt(mv, CONSUMABLE_SLOT);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, CONSUMABLE_BUILDER);
        mv.visitMethodInsn(INVOKEVIRTUAL, CONSUMABLE_BUILDER, "build",
                "()L" + CONSUMABLE + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_PROPS, "food",
                "(L" + FOOD + ";L" + CONSUMABLE + ";)L" + ITEM_PROPS + ";", false);
        mv.visitVarInsn(ASTORE, 0);
        mv.visitJumpInsn(GOTO, foodSet);
        mv.visitLabel(plainFood);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_PROPS, "food", FOOD_DESC, false);
        mv.visitVarInsn(ASTORE, 0);

        mv.visitLabel(foodSet);
        Label done = new Label();
        mv.visitVarInsn(ALOAD, 2);
        pushInt(mv, CONVERTS_TO_SLOT);
        mv.visitInsn(AALOAD);
        mv.visitJumpInsn(IFNULL, done);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        pushInt(mv, CONVERTS_TO_SLOT);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, ITEM_LIKE);
        mv.visitMethodInsn(INVOKEINTERFACE, ITEM_LIKE, "asItem", "()L" + ITEM + ";", true);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_PROPS, "usingConvertsTo",
                "(L" + ITEM + ";)L" + ITEM_PROPS + ";", false);
        mv.visitVarInsn(ASTORE, 0);
        mv.visitLabel(done);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void pushInt(MethodVisitor mv, int value) {
        mv.visitInsn(ICONST_0 + value);
    }
}
