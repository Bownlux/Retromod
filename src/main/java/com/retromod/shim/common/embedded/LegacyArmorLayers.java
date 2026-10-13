/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * The old {@code new HumanoidArmorLayer(parent, innerModel, outerModel)} for a host whose layer takes
 * an armor model set and the equipment renderer. The outer model wears the helmet, chestplate, and
 * boots and the inner model the leggings, as the two old models did, and the renderer is the one
 * the game's entity renderers share. Reflective, because this class is embedded into each mod and
 * compiled without Minecraft.
 */
public final class LegacyArmorLayers {
    private LegacyArmorLayers() {}

    public static Object create(Object parent, Object innerModel, Object outerModel) {
        try {
            ClassLoader loader = parent.getClass().getClassLoader();
            Class<?> modelSet = Class.forName("net.minecraft.client.renderer.entity.ArmorModelSet", true, loader);
            Object models = modelSet.getConstructors()[0].newInstance(outerModel, outerModel, innerModel, outerModel);
            Object equipment = equipmentRenderer(loader);
            Class<?> layer = Class.forName("net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer", true, loader);
            for (Constructor<?> constructor : layer.getConstructors()) {
                if (constructor.getParameterCount() == 3) return constructor.newInstance(parent, models, equipment);
            }
            throw new NoSuchMethodException("HumanoidArmorLayer(parent, models, equipment)");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build an old armor layer", e);
        }
    }

    private static Object equipmentRenderer(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", true, loader);
        Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
        Object dispatcher = minecraftClass.getMethod("getEntityRenderDispatcher").invoke(minecraft);
        Object assets = null;
        for (Class<?> c = dispatcher.getClass(); c != null && assets == null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType().getName().endsWith(".EquipmentAssetManager")) {
                    field.setAccessible(true);
                    assets = field.get(dispatcher);
                    break;
                }
            }
        }
        if (assets == null) throw new NoSuchFieldException("EntityRenderDispatcher equipment assets");
        Object palettes = minecraftClass.getMethod("getPalettedTextureManager").invoke(minecraft);
        Class<?> renderer = Class.forName("net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer", true, loader);
        for (Constructor<?> constructor : renderer.getConstructors()) {
            if (constructor.getParameterCount() == 2) return constructor.newInstance(assets, palettes);
        }
        throw new NoSuchMethodException("EquipmentLayerRenderer(assets, palettes)");
    }
}
