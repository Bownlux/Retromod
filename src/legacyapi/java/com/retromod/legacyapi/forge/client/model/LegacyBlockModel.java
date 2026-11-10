/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Either;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.cuboid.CuboidModelElement;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.cuboid.UnbakedCuboidGeometry;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/** Minecraft 1.20.1's JSON BlockModel, over Minecraft 26.2's unbaked model, for old code that builds or reads one. */
public final class LegacyBlockModel implements UnbakedModel, BlockModel {

    // Minecraft 26.1 gave the old name to a block render model, so old code that casts to it reaches this class
    // through that interface. Nothing draws it as one.
    private final UnbakedModel model;

    private LegacyBlockModel(UnbakedModel model) {
        this.model = model;
    }

    /** {@code new BlockModel(parent, elements, textures, ambientOcclusion, guiLight, transforms, overrides)}. */
    public static BlockModel create(Identifier parent, List<CuboidModelElement> elements, Map<String, ?> textures,
            Boolean ambientOcclusion, UnbakedModel.GuiLight guiLight, ItemTransforms transforms, List<?> overrides) {
        // A model with no elements of its own takes its parent's, as Forge 1.20.1's did.
        UnbakedGeometry geometry = elements == null || elements.isEmpty() ? null : new UnbakedCuboidGeometry(elements);
        if (overrides != null && !overrides.isEmpty()) {
            LegacyLog.warnOnce("legacy block model overrides " + LegacyBlockModel.class.getName(), "An old mod built a"
                    + " model with item overrides; Minecraft 26.2 reads them from item definitions, so Retromod left them out.");
        }
        return new LegacyBlockModel(new CuboidModel(geometry, guiLight, ambientOcclusion, transforms,
                TextureSlots.parseTextureMap(textureJson(textures)), parent));
    }

    /** The model as old code saw it; an old model stays itself. */
    static LegacyBlockModel of(UnbakedModel model) {
        return model instanceof LegacyBlockModel legacy ? legacy : new LegacyBlockModel(model);
    }

    /** A model that only names {@code id} as its parent, so it draws as {@code id} does once its parents resolve. */
    static LegacyBlockModel inheriting(Identifier id) {
        return new LegacyBlockModel(new CuboidModel(null, null, null, null, TextureSlots.Data.EMPTY, id));
    }

    /** The host model this one reads. */
    UnbakedModel unwrap() {
        return model;
    }

    // Old texture maps hold a texture or the name of another slot, which the host's own parser reads back.
    private static JsonObject textureJson(Map<String, ?> textures) {
        JsonObject json = new JsonObject();
        if (textures == null) return json;
        for (Map.Entry<String, ?> entry : textures.entrySet()) {
            String value = textureValue(entry.getValue());
            if (value != null) json.addProperty(entry.getKey(), value);
        }
        return json;
    }

    private static String textureValue(Object value) {
        if (value instanceof Either<?, ?> either) {
            Object left = either.left().orElse(null);
            if (left != null) return textureValue(left);
            // A reference names another slot without its '#'.
            return either.right().map(slot -> "#" + slot).orElse(null);
        }
        if (value instanceof SpriteId sprite) return sprite.texture().toString();
        return value == null ? null : value.toString();
    }

    @Override
    public Boolean ambientOcclusion() {
        return model.ambientOcclusion();
    }

    @Override
    public GuiLight guiLight() {
        return model.guiLight();
    }

    @Override
    public ItemTransforms transforms() {
        return model.transforms();
    }

    @Override
    public TextureSlots.Data textureSlots() {
        return model.textureSlots();
    }

    @Override
    public UnbakedGeometry geometry() {
        return model.geometry();
    }

    @Override
    public Identifier parent() {
        return model.parent();
    }

    @Override
    public void update(BlockModelRenderState renderState, BlockState state, BlockDisplayContext context, long seed) {}

    @Override
    public String toString() {
        return "LegacyBlockModel[" + model + "]";
    }
}
