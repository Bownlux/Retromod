/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.legacyapi.minecraft.client.model.ChunkRenderTypeSet;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

/** Minecraft 26.2's block model for one state, drawn by an old mod's BakedModel. */
public final class LegacyBlockStateModel implements BlockStateModel {

    private static final Direction[] SIDES = Direction.values();
    private static final int FLAG_TRANSLUCENT = 1;
    private static final int FLAGS_UNKNOWN = -1;
    // The seed this thread's adapter in this copy is drawing with, which views read to reuse a model's parts.
    private static final ThreadLocal<long[]> SEED = new ThreadLocal<>();

    private final BlockState state;
    private final BakedModel model;
    // The host model this one replaced, drawn instead once the old model throws.
    private final BlockStateModel original;
    private volatile boolean broken;
    private volatile int flags = FLAGS_UNKNOWN;

    public LegacyBlockStateModel(BlockState state, BakedModel model, BlockStateModel original) {
        this.state = state;
        this.model = model;
        this.original = original;
    }

    /** The old mod's model. */
    public BakedModel legacy() {
        return model;
    }

    /** The host model this one replaced, or null. */
    public BlockStateModel original() {
        return original;
    }

    /** The seed the adapter on this thread draws with, or null outside an adapter's call. */
    static long[] currentSeed() {
        return SEED.get();
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        collectParts(random, parts, ModelData.EMPTY);
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts, ModelData data) {
        if (broken) {
            drawOriginal(random, parts, data);
            return;
        }
        // Forge 1.20.1 reseeded the random before each side's quads, so a model that picks by random picks once.
        long seed = random.nextLong();
        RandomSource sideRandom = RandomSource.create(seed);
        List<BlockStateModelPart> collected = new ArrayList<>();
        long[] outer = SEED.get();
        SEED.set(new long[]{seed});
        try {
            ChunkRenderTypeSet layers = model.getRenderTypes(state, reseeded(sideRandom, seed), data);
            if (layers == null) layers = ChunkRenderTypeSet.ofLayers(ChunkSectionLayer.SOLID);
            Material.Baked particle = particleMaterial(data);
            for (ChunkSectionLayer layer : layers.layers()) {
                BlockStateModelPart part = part(layer, sideRandom, seed, data, particle);
                if (part != null) collected.add(part);
            }
        } catch (RuntimeException | LinkageError failure) {
            fail("built chunks", failure);
            drawOriginal(random, parts, data);
            return;
        } finally {
            SEED.set(outer);
            // Parts kept for this call's sides must not answer a later call with the same seed and model data.
            if (outer == null) LegacyBlockStateModelView.forgetParts();
        }
        parts.addAll(collected);
    }

    // One part per chunk layer, each quad stamped with that layer, as Forge 1.20.1 asked a model once per layer.
    private BlockStateModelPart part(ChunkSectionLayer layer, RandomSource random, long seed, ModelData data,
            Material.Baked particle) {
        RenderType token = ChunkRenderTypeSet.renderTypeOf(layer);
        QuadCollection.Builder quads = new QuadCollection.Builder();
        boolean any = false;
        // withLayer syncs each quad with the int[] an old model may have edited, so the list is not synced first.
        for (Direction side : SIDES) {
            for (BakedQuad quad : quads(side, reseeded(random, seed), data, token)) {
                quads.addCulledFace(side, LegacyQuadVertices.withLayer(quad, layer));
                any = true;
            }
        }
        for (BakedQuad quad : quads(null, reseeded(random, seed), data, token)) {
            quads.addUnculledFace(LegacyQuadVertices.withLayer(quad, layer));
            any = true;
        }
        if (!any) return null;
        return new SimpleModelWrapper(quads.build(), model.useAmbientOcclusion(state, token), particle);
    }

    private List<BakedQuad> quads(Direction side, RandomSource random, ModelData data, RenderType token) {
        List<BakedQuad> quads = model.getQuads(state, side, random, data, token);
        return quads == null ? List.of() : quads;
    }

    private static RandomSource reseeded(RandomSource random, long seed) {
        random.setSeed(seed);
        return random;
    }

    private void drawOriginal(RandomSource random, List<BlockStateModelPart> parts, ModelData data) {
        if (original != null) LegacyBlockStateModelView.forge(original).collectParts(random, parts, data);
    }

    // Chunk workers call the old model in parallel; after its first throw the block keeps the model it replaced.
    private void fail(String doing, Throwable failure) {
        broken = true;
        LegacyLog.warnOnce("legacy block model " + model.getClass().getName(), "An old block model of "
                + model.getClass().getName() + " threw while Minecraft " + doing + ", so Retromod drew the block's own"
                + " model in its place from then on: " + failure);
    }

    @Override
    public Material.Baked particleMaterial() {
        return particleMaterial(ModelData.EMPTY);
    }

    @Override
    public Material.Baked particleMaterial(ModelData data) {
        if (!broken) {
            try {
                TextureAtlasSprite sprite = model.getParticleIcon(data);
                if (sprite != null) return new Material.Baked(sprite, false);
            } catch (RuntimeException | LinkageError failure) {
                fail("read its particle texture", failure);
            }
        }
        return original == null ? null : original.particleMaterial();
    }

    // Whether the block may draw translucent quads: the replaced model's flags, then the layers the old model names.
    @Override
    public int materialFlags() {
        int known = flags;
        if (known != FLAGS_UNKNOWN) return known;
        int computed = original == null ? 0 : original.materialFlags();
        if (LegacyBakedModels.defaultRenderTypes(state).contains(ChunkSectionLayer.TRANSLUCENT) || ownLayersTranslucent()) {
            computed |= FLAG_TRANSLUCENT;
        }
        flags = computed;
        return computed;
    }

    // A model that names its own layers is asked once, with no model data.
    private boolean ownLayersTranslucent() {
        try {
            ChunkRenderTypeSet own = model.getRenderTypes(state, RandomSource.create(42L), ModelData.EMPTY);
            return own != null && own.contains(ChunkSectionLayer.TRANSLUCENT);
        } catch (RuntimeException | LinkageError unanswered) {
            return false;
        }
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState blockState, ModelData data) {
        if (broken) return data;
        try {
            return model.getModelData(level, pos, blockState, data);
        } catch (RuntimeException | LinkageError failure) {
            fail("read its model data", failure);
            return data;
        }
    }

    @Override
    public String toString() {
        return "LegacyBlockStateModel[" + model + "]";
    }
}
