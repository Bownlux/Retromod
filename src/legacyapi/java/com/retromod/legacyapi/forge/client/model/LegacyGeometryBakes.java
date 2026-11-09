/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.data.ModelData;

/** Turns an old geometry's BakedModel into the quads Minecraft 26.2 keeps, and puts the old model back where it draws per block or stack. */
final class LegacyGeometryBakes {

    private static final Direction[] SIDES = Direction.values();
    // Forge 1.20.1 seeded every side's random with 42 when it read a model's quads ahead of time.
    private static final long SEED = 42L;

    // What this copy's old geometries baked in the current reload: the quads Minecraft keeps and the old model
    // they came from, by the quads object for blocks and by each quad for items. Each reload starts them over.
    private static final Map<QuadCollection, BakedModel> BY_QUADS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<BakedQuad, BakedModel> BY_QUAD = Collections.synchronizedMap(new IdentityHashMap<>());

    private LegacyGeometryBakes() {}

    /** Forgets the last reload's models, at the start of the next. */
    static void startReload() {
        BY_QUADS.clear();
        BY_QUAD.clear();
        LegacyNestedModels.clear();
    }

    /** The model's id from its debug name, or null for a name that is not one. */
    static Identifier location(String name) {
        return name == null ? null : Identifier.tryParse(name);
    }

    /** The old model's quads, read once as Forge 1.20.1 read them ahead of time, and noted when the model draws per block or stack. */
    static QuadCollection quads(BakedModel model, LegacyGeometryContext context, ModelBaker baker, ModelDebugName name) {
        if (model == null) return QuadCollection.EMPTY;
        // A render_type on the model drew all of it in one layer.
        ChunkSectionLayer hint = context.hintLayer();
        QuadCollection.Builder builder = new QuadCollection.Builder();
        RandomSource random = RandomSource.create(SEED);
        for (Direction side : SIDES) {
            for (BakedQuad quad : read(model, side, random)) builder.addCulledFace(side, stamp(quad, hint));
        }
        for (BakedQuad quad : read(model, null, random)) builder.addUnculledFace(stamp(quad, hint));
        QuadCollection built = builder.build();
        if (!drawsPerBlockOrStack(model)) return built;
        // Minecraft hands every empty model the same empty collection, so a model that draws only from a block's
        // state or data gets one quad of no size, which draws nothing and names it for the swap.
        if (built == QuadCollection.EMPTY) built = new QuadCollection.Builder().addUnculledFace(placeholder(baker, name)).build();
        BY_QUADS.put(built, model);
        for (BakedQuad quad : built.getAll()) BY_QUAD.put(quad, model);
        return built;
    }

    // Four corners at the block's center, on the atlas's missing texture, so nothing shows where the swap does not
    // happen, and an item model that measures its quads stays inside the block.
    private static BakedQuad placeholder(ModelBaker baker, ModelDebugName name) {
        int center = Float.floatToRawIntBits(0.5f);
        int[] vertices = new int[32];
        for (int vertex = 0; vertex < 4; vertex++) {
            for (int axis = 0; axis < 3; axis++) vertices[vertex * 8 + axis] = center;
            vertices[vertex * 8 + 3] = -1;
        }
        TextureAtlasSprite sprite = baker.materials().get(new Material(MissingTextureAtlasSprite.getLocation()), name).sprite();
        return LegacyQuadVertices.create(vertices, -1, Direction.UP, sprite, false);
    }

    // Quads read without a block state; a model that needs one answers per block once the swap puts it back.
    private static List<BakedQuad> read(BakedModel model, Direction side, RandomSource random) {
        random.setSeed(SEED);
        try {
            List<BakedQuad> quads = model.getQuads(null, side, random, ModelData.EMPTY, null);
            return quads == null ? List.of() : LegacyQuadVertices.sync(quads);
        } catch (RuntimeException | LinkageError needsAState) {
            return List.of();
        }
    }

    private static BakedQuad stamp(BakedQuad quad, ChunkSectionLayer layer) {
        return layer == null ? quad : LegacyQuadVertices.withLayer(quad, layer);
    }

    /** Whether the model answers differently per block state, model data, layer or stack than its quads read once. */
    static boolean drawsPerBlockOrStack(BakedModel model) {
        // Retromod's own models are fixed quads or views of Minecraft's.
        if (model instanceof LegacyStaticModel || model instanceof LegacyBlockStateModelView || model instanceof LegacyItemModelView) {
            return false;
        }
        if (model instanceof IDynamicBakedModel) return true;
        try {
            if (model.isCustomRenderer()) return true;
            ItemOverrides overrides = model.getOverrides();
            if (overrides != null && overrides != ItemOverrides.EMPTY) return true;
        } catch (RuntimeException | LinkageError unanswered) {
            return true;
        }
        return overrides(model, "getModelData") || overrides(model, "applyTransform") || overrides(model, "getRenderPasses")
                || overrides(model, "getRenderTypes") || overridesQuads(model);
    }

    // A method the model's own class declares, not one of the rebuilt interface's defaults; a class whose methods
    // cannot be read is taken to draw per block or stack, where the old model still answers for itself.
    private static boolean overrides(BakedModel model, String name) {
        return declares(model, name, -1);
    }

    private static boolean overridesQuads(BakedModel model) {
        return declares(model, "getQuads", 5);
    }

    private static boolean declares(BakedModel model, String name, int parameters) {
        try {
            for (Method method : model.getClass().getMethods()) {
                if (method.getName().equals(name) && (parameters < 0 || method.getParameterCount() == parameters)
                        && !method.getDeclaringClass().isInterface()) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException | LinkageError unreadable) {
            return true;
        }
    }

    /** At ModifyBakingResult, before old listeners read the maps: each block or item drawn from such a model draws through it again. */
    static void swap(ModelEvent.ModifyBakingResult event) {
        if (BY_QUADS.isEmpty()) return;
        ModelBakery.BakingResult result = event.getResults();
        int blocks = swapBlocks(result.blockStateModels());
        int items = swapItems(result.itemStackModels());
        LegacyLog.debug("Retromod put " + blocks + " block state and " + items + " item models of old geometry back in place.");
    }

    private static int swapBlocks(Map<BlockState, BlockStateModel> models) {
        int swapped = 0;
        for (Map.Entry<BlockState, BlockStateModel> entry : new ArrayList<>(models.entrySet())) {
            BakedModel old = oldModel(entry.getValue());
            if (old == null) continue;
            try {
                models.put(entry.getKey(), LegacyModelMaps.blockModel(old, entry.getKey(), entry.getValue()));
                swapped++;
            } catch (UnsupportedOperationException readOnly) {
                return swapped;
            }
        }
        return swapped;
    }

    // Only a single model drawn whole; a multipart or weighted state keeps the quads read ahead of time.
    private static BakedModel oldModel(BlockStateModel model) {
        if (!(model instanceof SingleVariant)) return null;
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(SEED), parts);
        if (parts.size() != 1 || !(parts.get(0) instanceof SimpleModelWrapper part)) return null;
        return BY_QUADS.get(part.quads());
    }

    private static int swapItems(Map<Identifier, ItemModel> models) {
        int swapped = 0;
        for (Map.Entry<Identifier, ItemModel> entry : new ArrayList<>(models.entrySet())) {
            if (!(entry.getValue() instanceof CuboidItemModelWrapper)) continue;
            BakedModel old = oldItemModel(entry.getValue());
            if (old == null) continue;
            try {
                models.put(entry.getKey(), LegacyModelMaps.itemModel(old, entry.getValue()));
                swapped++;
            } catch (UnsupportedOperationException readOnly) {
                return swapped;
            }
        }
        return swapped;
    }

    // Minecraft 26.3 splits an item's quads into new lists but keeps each quad, so one quad names the model.
    private static BakedModel oldItemModel(ItemModel model) {
        BakedModel found = firstKnown(LegacyItemQuads.read(model, null));
        for (int i = 0; found == null && i < SIDES.length; i++) found = firstKnown(LegacyItemQuads.read(model, SIDES[i]));
        return found;
    }

    private static BakedModel firstKnown(List<BakedQuad> quads) {
        return quads.isEmpty() ? null : BY_QUAD.get(quads.get(0));
    }
}
