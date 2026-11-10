/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.core.Direction;

/** Hands quads to an item layer and reads them back from a host item model, on Minecraft 26.2 and 26.3 alike. */
final class LegacyItemQuads {

    // Minecraft 26.2 fills a layer's list; 26.3 sets a solid and translucent split instead. The running host's
    // own shape is looked up once.
    private static volatile Method prepareQuadList;
    private static volatile Method setQuads;
    private static volatile Method split;
    private static volatile boolean shapeRead;
    // How each host item model class keeps its quads and properties, read from its fields once per class.
    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();

    private LegacyItemQuads() {}

    /** Gives the layer these quads to draw; false when the host's layer has no known way to take them. */
    static boolean give(ItemStackRenderState.LayerRenderState layer, List<BakedQuad> quads) {
        readShape(layer.getClass());
        try {
            if (prepareQuadList != null) {
                @SuppressWarnings("unchecked")
                List<BakedQuad> list = (List<BakedQuad>) prepareQuadList.invoke(layer);
                list.addAll(quads);
                return true;
            }
            if (setQuads != null) {
                setQuads.invoke(layer, split.invoke(null, quads));
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LegacyLog.warnOnce("legacy item quads give " + LegacyItemQuads.class.getName(), "Retromod could not hand an"
                    + " old item model's quads to Minecraft, so those items draw empty: " + failure);
        }
        return false;
    }

    /** The quads a host item model draws on a side, null for the unculled ones; empty when the model keeps none Retromod can read. */
    static List<BakedQuad> read(ItemModel model, Direction side) {
        Object quads = field(model, 0);
        if (quads instanceof QuadCollection collection) return collection.getQuads(side);
        if (side != null) return List.of();
        if (quads instanceof List<?> list) return castQuads(list);
        if (quads != null) {
            // 26.3's ItemQuads keeps every quad together, with no culling side.
            try {
                Object all = LegacyPublicMethods.method(quads.getClass(), "all").invoke(quads);
                if (all instanceof List<?> list) return castQuads(list);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return List.of();
            }
        }
        return List.of();
    }

    /** Whether Retromod can read the model's quads, as it can for plain cuboid and missing item models. */
    static boolean readable(ItemModel model) {
        return field(model, 0) != null;
    }

    /** The model's display properties, or null when it keeps none Retromod can read. */
    static ModelRenderProperties properties(ItemModel model) {
        return field(model, 1) instanceof ModelRenderProperties properties ? properties : null;
    }

    private static Object field(ItemModel model, int which) {
        if (model == null) return null;
        Field field = FIELDS.computeIfAbsent(model.getClass(), LegacyItemQuads::fields)[which];
        if (field == null) return null;
        try {
            return field.get(model);
        } catch (IllegalAccessException | RuntimeException unreadable) {
            return null;
        }
    }

    // Field 0 keeps the quads (a QuadCollection, an ItemQuads or a List), field 1 the display properties.
    private static Field[] fields(Class<?> type) {
        Field[] found = new Field[2];
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Class<?> kind = field.getType();
            boolean quads = kind == QuadCollection.class || kind.getSimpleName().equals("ItemQuads")
                    || (kind == List.class && field.getName().toLowerCase(java.util.Locale.ROOT).contains("quad"));
            int slot = quads ? 0 : kind == ModelRenderProperties.class ? 1 : -1;
            if (slot < 0 || found[slot] != null) continue;
            try {
                field.setAccessible(true);
                found[slot] = field;
            } catch (RuntimeException closed) {
                // A field Retromod cannot open stays unread, and the view draws nothing for it.
            }
        }
        return found;
    }

    private static void readShape(Class<?> layerClass) {
        if (!shapeRead) readShapeOnce(layerClass);
    }

    private static synchronized void readShapeOnce(Class<?> layerClass) {
        if (shapeRead) return;
        for (Method method : layerClass.getMethods()) {
            if (method.getName().equals("prepareQuadList") && method.getParameterCount() == 0) prepareQuadList = method;
        }
        for (Method method : layerClass.getMethods()) {
            if (!method.getName().equals("setQuads") || method.getParameterCount() != 1) continue;
            try {
                Method splitter = method.getParameterTypes()[0].getMethod("split", List.class);
                if (Modifier.isStatic(splitter.getModifiers())) {
                    split = splitter;
                    setQuads = method;
                }
            } catch (NoSuchMethodException noSplit) {
                // Not the shape Retromod knows.
            }
        }
        shapeRead = true;
    }

    private static List<BakedQuad> castQuads(List<?> list) {
        List<BakedQuad> quads = new ArrayList<>(list.size());
        for (Object quad : list) {
            if (quad instanceof BakedQuad baked) quads.add(baked);
        }
        return quads;
    }
}
