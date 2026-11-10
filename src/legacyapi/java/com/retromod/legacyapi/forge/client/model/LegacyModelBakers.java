/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import java.io.Reader;
import java.io.StringReader;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.cuboid.CuboidModelElement;
import net.minecraft.client.resources.model.cuboid.UnbakedCuboidGeometry;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;

/** Forge 1.20.1's ModelBaker, ModelBakery, UnbakedModel and JSON BlockModel calls, over Minecraft 26.2's model baking. */
public final class LegacyModelBakers {

    private static final ModelDebugName TEXTURE_GETTER = () -> "retromod:legacy_texture_getter";

    private LegacyModelBakers() {}

    // ---- ModelBaker and ModelBakery ----

    /** {@code baker.getModel(id)}: the unbaked model {@code id}, which bakes as the model file Minecraft loads for it. */
    public static UnbakedModel getModel(ModelBaker baker, Identifier id) {
        return LegacyBlockModel.inheriting(modelId(id));
    }

    /** {@code bakery.getModel(id)}, which old code also calls after the bake. Minecraft 26.2 keeps no public lookup there. */
    public static UnbakedModel getModel(ModelBakery bakery, Identifier id) {
        return LegacyBlockModel.inheriting(modelId(id));
    }

    // An old model location's variant never reaches the host: an inventory location named the item model folder.
    private static Identifier modelId(Identifier location) {
        Identifier id = LegacyModelLocations.id(location);
        return LegacyModelLocations.INVENTORY.equals(LegacyModelLocations.getVariant(location)) ? id.withPrefix("item/") : id;
    }

    /** {@code baker.bake(id, state)}: the model file baked with its parents. */
    public static BakedModel bake(ModelBaker baker, Identifier id, ModelState state) {
        return bake(getModel(baker, id), baker, null, state, id);
    }

    /** Forge 1.20.1's {@code baker.bake(id, state, sprites)}; Minecraft 26.2 takes every texture from its own atlases. */
    public static BakedModel bake(ModelBaker baker, Identifier id, ModelState state, Function<?, ?> sprites) {
        return bake(baker, id, state);
    }

    /** Forge 1.20.1's {@code baker.getModelTextureGetter()}. */
    public static Function<SpriteId, TextureAtlasSprite> getModelTextureGetter(ModelBaker baker) {
        return sprites(baker, TEXTURE_GETTER);
    }

    /** A texture getter over the baker's atlases, which gives the missing texture for one they do not hold. */
    static Function<SpriteId, TextureAtlasSprite> sprites(ModelBaker baker, ModelDebugName name) {
        return sprite -> sprite == null ? null : baker.materials().get(new Material(sprite.texture()), name).sprite();
    }

    // ---- UnbakedModel ----

    /** {@code model.bake(baker, sprites, state, location)}: the model baked with its parents resolved. */
    public static BakedModel bake(UnbakedModel model, ModelBaker baker, Function<?, ?> sprites, ModelState state,
            Identifier location) {
        String name = location == null ? String.valueOf(model) : location.toString();
        return new LegacyStaticModel(LegacyNestedModels.bake(model, baker, state, name));
    }

    /** Minecraft 1.21's {@code model.bake(baker, sprites, state)}. */
    public static BakedModel bake(UnbakedModel model, ModelBaker baker, Function<?, ?> sprites, ModelState state) {
        return bake(model, baker, sprites, state, null);
    }

    /** Forge 1.21's {@code baker.bakeUncached(model, state)}. */
    public static BakedModel bakeUncached(ModelBaker baker, UnbakedModel model, ModelState state) {
        return bake(model, baker, null, state, null);
    }

    /** {@code model.resolveParents(getter)}: Retromod reads the parents when the model bakes. */
    public static void resolveParents(UnbakedModel model, Function<?, ?> getter) {}

    /** {@code model.getDependencies()}: the parent the model names. */
    public static Collection<Identifier> getDependencies(UnbakedModel model) {
        Identifier parent = model == null ? null : model.parent();
        return parent == null ? List.of() : List.of(parent);
    }

    // ---- the JSON BlockModel, called on a name Minecraft 26.1 gave to another type ----

    /** {@code blockModel.getElements()}: the model's elements, or its nearest parent's. */
    public static List<CuboidModelElement> getElements(Object model) {
        UnbakedModel current = unbaked(model);
        for (int depth = 0; current != null && depth < 32; depth++) {
            UnbakedGeometry geometry = current.geometry();
            if (geometry instanceof UnbakedCuboidGeometry cuboid && !cuboid.elements().isEmpty()) return cuboid.elements();
            Identifier parent = current.parent();
            current = parent == null || parent.getPath().startsWith("builtin/") ? null : LegacyNestedModels.read(parent);
        }
        return List.of();
    }

    public static void resolveParents(Object model, Function<?, ?> getter) {}

    public static BakedModel bake(Object model, ModelBaker baker, Function<?, ?> sprites, ModelState state, Identifier location) {
        return bake(unbaked(model), baker, sprites, state, location);
    }

    public static BakedModel bake(Object model, ModelBaker baker, Function<?, ?> sprites, ModelState state) {
        return bake(unbaked(model), baker, sprites, state, null);
    }

    public static BakedModel bake(Object model, ModelBaker baker, Object owner, Function<?, ?> sprites, ModelState state,
            boolean guiLight3d) {
        return bake(unbaked(model), baker, sprites, state, null);
    }

    /** Minecraft 1.20.1's {@code bake(baker, owner, sprites, state, location, guiLight3d)}, as Forge's composite children called it. */
    public static BakedModel bake(Object model, ModelBaker baker, Object owner, Function<?, ?> sprites, ModelState state,
            Identifier location, boolean guiLight3d) {
        return bake(unbaked(model), baker, sprites, state, location);
    }

    /** {@code blockModel.getMaterial(name)}: the slot's texture through the model's parents, or the missing texture. */
    public static SpriteId getMaterial(Object model, String name) {
        String modelName = String.valueOf(model);
        return new LegacyGeometryContext(null, LegacyNestedModels.textures(unbaked(model), modelName), modelName).getMaterial(name);
    }

    /** {@code blockModel.getRootModel()}: the last parent Retromod can read. */
    public static BlockModel getRootModel(Object model) {
        UnbakedModel current = unbaked(model);
        for (int depth = 0; depth < 32; depth++) {
            Identifier parent = current.parent();
            UnbakedModel next = parent == null || parent.getPath().startsWith("builtin/") ? null : LegacyNestedModels.read(parent);
            if (next == null) break;
            current = next;
        }
        return LegacyBlockModel.of(current);
    }

    /** {@code BlockModel.fromStream(reader)}: the model Minecraft 26.2 parses from the same JSON. */
    public static BlockModel fromStream(Reader reader) {
        return LegacyBlockModel.of(CuboidModel.fromStream(reader));
    }

    /** {@code BlockModel.fromString(json)}. */
    public static BlockModel fromString(String json) {
        return fromStream(new StringReader(json));
    }

    private static UnbakedModel unbaked(Object model) {
        return model instanceof UnbakedModel unbaked ? unbaked : LegacyBlockModel.inheriting(null);
    }
}
