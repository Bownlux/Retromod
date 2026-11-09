/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.math.Transformation;
import com.retromod.legacyapi.forge.LegacySharedSlots;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.cuboid.CuboidModelElement;
import net.minecraft.client.resources.model.cuboid.ItemModelGenerator;
import net.minecraft.client.resources.model.cuboid.UnbakedCuboidGeometry;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.IGeometryLoader;
import net.minecraftforge.client.model.geometry.UnbakedGeometryHelper;

/** Retromod's own forge:composite, forge:item_layers, forge:elements and forge:separate_transforms, which Forge 26.2 removed. */
final class LegacyBuiltinLoaders {

    static final String NAMESPACE = "forge";

    private LegacyBuiltinLoaders() {}

    /** The slots' layout; a copy from a Retromod build with another layout keeps slots of its own. */
    static final int PROTOCOL = 2;
    /**
     * {@code Map<Object, Set<String>>}: each reload's event, weakly held, and the loader names a copy already registered
     * on it. Kept per name, so a newer copy still adds a name an older copy does not know.
     */
    static final String REGISTERED = "forge.geometry.builtinsRegistered";

    private static volatile Map<Object, Object> registered;

    /** Registers each loader the host does not already have, after Forge's own listener ran. */
    static void register(ModelEvent.RegisterGeometryLoaders event) {
        add(event, "composite", LegacyBuiltinLoaders::composite);
        // Forge 1.20.1's item layers were vanilla's generated item geometry, plus per-layer lighting and render types.
        add(event, "item_layers", (json, context) -> new ItemModelGenerator().geometry());
        add(event, "elements", LegacyBuiltinLoaders::elements);
        add(event, "separate_transforms", LegacyBuiltinLoaders::separateTransforms);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> registered() {
        Map<Object, Object> slot = registered;
        if (slot == null) {
            registered = slot = LegacySharedSlots.get(REGISTERED, PROTOCOL, Map.class,
                    () -> Collections.synchronizedMap(new WeakHashMap<>()));
        }
        return slot;
    }

    @SuppressWarnings("unchecked")
    private static void add(ModelEvent.RegisterGeometryLoaders event, String name, IGeometryLoader loader) {
        // Every copy's listener sees the same event; the first copy that knows a name registers it for all.
        Object names = registered().computeIfAbsent(event, any -> ConcurrentHashMap.newKeySet());
        if (!(names instanceof Set<?> set) || !((Set<Object>) set).add(name)) return;
        try {
            event.register(Identifier.fromNamespaceAndPath(NAMESPACE, name), loader);
        } catch (IllegalArgumentException taken) {
            // Forge itself already registered this name.
        }
    }

    /** forge:composite: each child model's quads, together, skipping the children the model hides. */
    static UnbakedGeometry composite(JsonObject json, JsonDeserializationContext context) {
        if (!json.has("children") || !json.get("children").isJsonObject()) {
            throw new JsonParseException("A composite model needs a \"children\" object");
        }
        Map<String, UnbakedModel> children = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> child : json.getAsJsonObject("children").entrySet()) {
            children.put(child.getKey(), context.deserialize(child.getValue(), CuboidModel.class));
        }
        return new Composite(children);
    }

    /** forge:elements: the model's elements, as vanilla bakes them. */
    static UnbakedGeometry elements(JsonObject json, JsonDeserializationContext context) {
        if (!json.has("elements") || !json.get("elements").isJsonArray()) {
            throw new JsonParseException("An elements model needs an \"elements\" list");
        }
        List<CuboidModelElement> elements = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("elements")) {
            elements.add(context.deserialize(element, CuboidModelElement.class));
        }
        return new UnbakedCuboidGeometry(elements);
    }

    /** forge:separate_transforms: the base model's quads; Retromod's item definitions pick the per-view models. */
    static UnbakedGeometry separateTransforms(JsonObject json, JsonDeserializationContext context) {
        if (!json.has("base") || !json.get("base").isJsonObject()) {
            throw new JsonParseException("A separate transforms model needs a \"base\" model");
        }
        UnbakedModel base = context.deserialize(json.get("base"), CuboidModel.class);
        Map<String, UnbakedModel> children = new LinkedHashMap<>();
        children.put("base", base);
        return new Composite(children);
    }

    /** Child models baked one after another into one set of quads, as Forge 1.20.1 drew them in a world. */
    private record Composite(Map<String, UnbakedModel> children) implements UnbakedGeometry {

        @Override
        public QuadCollection bake(TextureSlots slots, ModelBaker baker, ModelState state, ModelDebugName name) {
            return bake(slots, baker, state, name, null);
        }

        public QuadCollection bake(TextureSlots slots, ModelBaker baker, ModelState state, ModelDebugName name,
                IGeometryBakingContext context) {
            // The model's root transform moves every child, as Forge 1.20.1 folded it into the model state.
            Transformation root = context == null ? null : context.getRootTransform();
            ModelState childState = root == null || Transformation.IDENTITY.equals(root) ? state
                    : UnbakedGeometryHelper.composeRootTransformIntoModelState(state, root);
            String owner = name == null ? "composite" : name.debugName();
            QuadCollection.Builder quads = new QuadCollection.Builder();
            for (Map.Entry<String, UnbakedModel> child : children.entrySet()) {
                if (context != null && !context.isComponentVisible(child.getKey(), true)) continue;
                quads.addAll(LegacyNestedModels.bake(child.getValue(), baker, childState, owner + "#" + child.getKey()).quads());
            }
            return quads.build();
        }
    }
}
