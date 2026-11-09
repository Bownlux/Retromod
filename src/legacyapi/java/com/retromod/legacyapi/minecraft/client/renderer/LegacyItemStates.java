/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Reads what Minecraft 26.x resolves for one stack: light, particle, display transforms, quads and tints. */
final class LegacyItemStates {

    // Old mods ask on the render thread, between draws; one scratch state serves every question.
    private static final ItemStackRenderState SCRATCH = new ItemStackRenderState();
    private static final RandomSource RANDOM = RandomSource.create(42L);
    private static final int[] NO_TINTS = new int[0];
    // The contexts each slot of ItemTransforms holds, in its constructor's order.
    private static final ItemDisplayContext[] TRANSFORM_ORDER = {ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
        ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, ItemDisplayContext.FIRST_PERSON_LEFT_HAND, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
        ItemDisplayContext.HEAD, ItemDisplayContext.GUI, ItemDisplayContext.GROUND, ItemDisplayContext.FIXED, ItemDisplayContext.ON_SHELF};

    // The state keeps its layers private; their fields are found once, by type, and stay unread if the host closes them.
    // Each is read through a getter made then, so a frame reads them without reflection.
    private static final MethodType READ = MethodType.methodType(Object.class, Object.class);
    private static MethodHandle layers;
    private static MethodHandle layerCount;
    private static MethodHandle quads;
    private static MethodHandle tints;
    private static MethodHandle transform;
    private static MethodHandle allQuads;
    private static boolean fieldsRead;

    private LegacyItemStates() {}

    /** Light, 3D and particle answers for the stack as the GUI shows it, or as {@code context} shows it when one is given. */
    static synchronized Answers answers(ItemStack stack, Level level, LivingEntity entity, int seed, ItemDisplayContext context) {
        resolve(stack, level, entity, seed, context);
        boolean blockLight = !SCRATCH.isEmpty() && SCRATCH.usesBlockLight();
        RANDOM.setSeed(42L);
        Material.Baked particle = SCRATCH.isEmpty() ? null : SCRATCH.pickParticleMaterial(RANDOM);
        return new Answers(blockLight, particle);
    }

    /** The quads the stack's layers draw, all together; empty for a special model or a host Retromod cannot read. */
    static synchronized List<BakedQuad> quads(ItemStack stack, Level level, LivingEntity entity, int seed, ItemDisplayContext context) {
        resolve(stack, level, entity, seed, context);
        List<BakedQuad> found = new ArrayList<>();
        for (Object layer : layers()) found.addAll(quadsOf(layer));
        return found;
    }

    /** The tint color each tint index takes for the stack, from its first layer; empty when none is known. */
    static synchronized int[] tints(ItemStack stack, Level level, LivingEntity entity, int seed, ItemDisplayContext context) {
        resolve(stack, level, entity, seed, context);
        Object[] found = layers();
        if (found.length == 0 || tints == null) return NO_TINTS;
        if (!(read(tints, found[0]) instanceof it.unimi.dsi.fastutil.ints.IntList ints)) return NO_TINTS;
        int[] colors = new int[ints.size()];
        for (int i = 0; i < colors.length; i++) colors[i] = ints.getInt(i);
        return colors;
    }

    /** The display transform the stack's first layer applies in {@code context}, or null when it cannot be read. */
    static synchronized ItemTransform transform(ItemStack stack, Level level, LivingEntity entity, int seed, ItemDisplayContext context) {
        resolve(stack, level, entity, seed, context);
        Object[] found = layers();
        if (found.length == 0 || transform == null) return null;
        return read(transform, found[0]) instanceof ItemTransform read ? read : null;
    }

    /** Every display transform of the stack, as the old model's getTransforms() listed them. */
    static ItemTransforms transforms(ItemStack stack, Level level, LivingEntity entity, int seed) {
        ItemTransform[] each = new ItemTransform[TRANSFORM_ORDER.length];
        for (int i = 0; i < each.length; i++) {
            ItemTransform read = transform(stack, level, entity, seed, TRANSFORM_ORDER[i]);
            // A context the host cannot answer for has no transform of its own, as in an old model without one.
            each[i] = read != null ? read : ItemTransform.NO_TRANSFORM;
        }
        return new ItemTransforms(each[0], each[1], each[2], each[3], each[4], each[5], each[6], each[7], each[8]);
    }

    private static void resolve(ItemStack stack, Level level, LivingEntity entity, int seed, ItemDisplayContext context) {
        ItemDisplayContext shown = context != null ? context : ItemDisplayContext.GUI;
        Minecraft minecraft = Minecraft.getInstance();
        ItemModelResolver resolver = minecraft == null ? null : minecraft.getItemModelResolver();
        // Before the game has item models, as while mods construct, the stack resolves to nothing.
        if (resolver == null || stack == null) {
            SCRATCH.clear();
            return;
        }
        if (entity != null) {
            resolver.updateForLiving(SCRATCH, stack, shown, entity);
        } else {
            resolver.updateForTopItem(SCRATCH, stack, shown, level, null, seed);
        }
    }

    private static Object[] layers() {
        if (!fieldsRead) readFields();
        if (layers == null || layerCount == null) return new Object[0];
        Object[] all = read(layers, SCRATCH) instanceof Object[] array ? array : new Object[0];
        int count = read(layerCount, SCRATCH) instanceof Integer number ? number : 0;
        Object[] active = new Object[Math.min(count, all.length)];
        System.arraycopy(all, 0, active, 0, active.length);
        return active;
    }

    @SuppressWarnings("unchecked")
    private static List<BakedQuad> quadsOf(Object layer) {
        if (quads == null) return List.of();
        Object held = read(quads, layer);
        if (held instanceof List<?> list) return (List<BakedQuad>) list;
        // 26.3 keeps a layer's quads as ItemQuads, whose all() lists every one.
        if (held != null && read(allQuads, held) instanceof List<?> list) return (List<BakedQuad>) list;
        return List.of();
    }

    private static void readFields() {
        Class<?> layer = null;
        try {
            for (Field field : ItemStackRenderState.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType().isArray()) {
                    layers = open(field);
                    layer = field.getType().getComponentType();
                } else if (field.getType() == int.class && field.getName().toLowerCase(java.util.Locale.ROOT).contains("count")) {
                    layerCount = open(field);
                }
            }
            if (layer != null) {
                for (Field field : layer.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    Class<?> type = field.getType();
                    if (type == List.class && field.getName().contains("quad")) quads = open(field);
                    else if (type.getSimpleName().equals("ItemQuads")) {
                        quads = open(field);
                        allQuads = MethodHandles.publicLookup().unreflect(type.getMethod("all")).asType(READ);
                    } else if (type == ItemTransform.class) transform = open(field);
                    else if (type.getSimpleName().equals("IntList")) tints = open(field);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException closed) {
            LegacyLog.debug("Retromod could not read Minecraft's item layers, so old item models report no quads: " + closed);
        }
        fieldsRead = true;
    }

    private static MethodHandle open(Field field) throws IllegalAccessException {
        field.setAccessible(true);
        return MethodHandles.lookup().unreflectGetter(field).asType(READ);
    }

    // A getter's value, or null when the getter is missing or the read fails.
    private static Object read(MethodHandle getter, Object from) {
        if (getter == null || from == null) return null;
        try {
            return getter.invokeExact(from);
        } catch (RuntimeException unreadable) {
            return null;
        } catch (Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            return null;
        }
    }

    /** What an old model reported about itself, read once. */
    record Answers(boolean blockLight, Material.Baked particle) {}
}
