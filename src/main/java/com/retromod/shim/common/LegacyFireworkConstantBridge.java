/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/** Keeps the firework recipe constants that 26.1 moved into recipe data readable by older mods. */
public final class LegacyFireworkConstantBridge {

    static final String CALLS = "com/retromod/generated/LegacyFireworkConstants";
    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    private static final String STAR = CRAFTING + "FireworkStarRecipe";
    private static final String ROCKET = CRAFTING + "FireworkRocketRecipe";
    private static final String INGREDIENT = "L" + CRAFTING + "Ingredient;";
    private static final String ITEMS = "net/minecraft/world/item/Items";
    private static final String ITEM_LIKE = "net/minecraft/world/level/ItemLike";
    private static final String SHAPE = "net/minecraft/world/item/component/FireworkExplosion$Shape";

    /** Each removed ingredient constant, its owner, and the item it accepted. */
    private static final String[][] INGREDIENTS = {
        {ROCKET, "GUNPOWDER_INGREDIENT", "GUNPOWDER"},
        {STAR, "GUNPOWDER_INGREDIENT", "GUNPOWDER"},
        {STAR, "TRAIL_INGREDIENT", "DIAMOND"},
        {STAR, "TWINKLE_INGREDIENT", "GLOWSTONE_DUST"},
    };

    /** The star shape each shape item gave, which was SHAPE_BY_ITEM. */
    static final Map<String, String> SHAPES = Map.ofEntries(
            Map.entry("FIRE_CHARGE", "LARGE_BALL"), Map.entry("FEATHER", "BURST"), Map.entry("GOLD_NUGGET", "STAR"),
            Map.entry("SKELETON_SKULL", "CREEPER"), Map.entry("WITHER_SKELETON_SKULL", "CREEPER"),
            Map.entry("CREEPER_HEAD", "CREEPER"), Map.entry("PLAYER_HEAD", "CREEPER"), Map.entry("DRAGON_HEAD", "CREEPER"),
            Map.entry("ZOMBIE_HEAD", "CREEPER"), Map.entry("PIGLIN_HEAD", "CREEPER"));

    private LegacyFireworkConstantBridge() {}

    /** Registers on a host whose firework star recipe has no static shape table. */
    public static void register(RetromodTransformer t) {
        ClassNode star = ClassResourceInspector.read(STAR);
        if (star == null || star.fields.stream().anyMatch(field -> field.name.equals("SHAPE_BY_ITEM"))) return;
        t.registerSyntheticClass(CALLS, generate());
        for (String[] constant : INGREDIENTS) {
            t.registerExactFieldRedirect(constant[0], constant[1], INGREDIENT, CALLS, getter(constant), "()" + INGREDIENT);
        }
        t.registerExactFieldRedirect(STAR, "SHAPE_BY_ITEM", "Ljava/util/Map;", CALLS, "shapeByItem", "()Ljava/util/Map;");
    }

    private static String getter(String[] constant) {
        return constant[0].substring(constant[0].lastIndexOf('/') + 1) + "$" + constant[1];
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        for (String[] constant : INGREDIENTS) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, getter(constant), "()" + INGREDIENT, null, null);
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, ITEMS, constant[2], "Lnet/minecraft/world/item/Item;");
            mv.visitMethodInsn(INVOKESTATIC, CRAFTING + "Ingredient", "of", "(L" + ITEM_LIKE + ";)" + INGREDIENT, false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        // A new HashMap on each read, so a mod that changes it changes only its own copy.
        MethodVisitor shapes = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "shapeByItem", "()Ljava/util/Map;", null, null);
        shapes.visitCode();
        shapes.visitTypeInsn(NEW, "java/util/HashMap");
        shapes.visitInsn(DUP);
        shapes.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        for (Map.Entry<String, String> entry : List.copyOf(SHAPES.entrySet())) {
            shapes.visitInsn(DUP);
            shapes.visitFieldInsn(GETSTATIC, ITEMS, entry.getKey(), "Lnet/minecraft/world/item/Item;");
            shapes.visitFieldInsn(GETSTATIC, SHAPE, entry.getValue(), "L" + SHAPE + ";");
            shapes.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
            shapes.visitInsn(POP);
        }
        shapes.visitInsn(ARETURN);
        shapes.visitMaxs(0, 0);
        shapes.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
