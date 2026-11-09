/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.model;

import com.retromod.legacyapi.minecraft.client.model.ChunkRenderTypeSet;
import com.retromod.legacyapi.minecraft.client.model.LegacyChunkLayers;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyBakedModelCalls;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemModelHandle;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
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
import net.minecraft.util.TriState;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.extensions.IBlockGetterExtension;
import net.neoforged.neoforge.model.data.ModelData;

/** NeoForge 1.21.1's model data members of BakedModel, called on what an old BakedModel became on NeoForge 26.x: Minecraft's block model. */
public final class LegacyModelDataCalls {

    private static final Direction[] SIDES = Direction.values();
    // Minecraft 26.x's quad flag for a block drawn in the translucent layer.
    private static final int FLAG_TRANSLUCENT = 1;
    // An old model that threw while Minecraft drew it draws nothing from then on, so a chunk never fails on it twice.
    private static final Set<Object> BROKEN = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static final Member QUADS = new Member("getQuads", BlockState.class, Direction.class, RandomSource.class, ModelData.class,
            RenderType.class);
    private static final Member OLD_QUADS = new Member("getQuads", BlockState.class, Direction.class, RandomSource.class);
    private static final Member LAYERS = new Member("getRenderTypes", BlockState.class, RandomSource.class, ModelData.class);
    private static final Member PARTICLE = new Member("getParticleIcon", ModelData.class);
    private static final Member DATA = new Member("getModelData", BlockAndTintGetter.class, BlockPos.class, BlockState.class, ModelData.class);
    private static final Member OCCLUSION = new Member("useAmbientOcclusion", BlockState.class, ModelData.class, RenderType.class);
    // Forge 1.20.1's two occlusion questions, which a Forge mod running on NeoForge still asks and answers.
    private static final Member STATE_OCCLUSION = new Member("useAmbientOcclusion", BlockState.class);
    private static final Member LAYER_OCCLUSION = new Member("useAmbientOcclusion", BlockState.class, RenderType.class);

    private LegacyModelDataCalls() {}

    // Each member answers from the model's own method when its class has one, else as NeoForge 1.21.1's default did.
    // Minecraft's models never had these members, so they answer from their quads.

    /** The quads for one side and render type; a model with only the old three-argument getQuads ignores the data and type. */
    @SuppressWarnings("unchecked")
    public static List<BakedQuad> getQuads(BlockStateModel model, BlockState state, Direction side, RandomSource random, ModelData data,
            RenderType renderType) {
        MethodHandle own = QUADS.of(model);
        if (own != null) {
            return (List<BakedQuad>) invoke(own, model, state, side, random, data, renderType);
        }
        return superGetQuads(model, state, side, random, data, renderType);
    }

    // Each super variant is the default an old model's override reaches through super, so it never runs that override again.

    public static List<BakedQuad> superGetQuads(BlockStateModel model, BlockState state, Direction side, RandomSource random, ModelData data,
            RenderType renderType) {
        if (isOldModel(model)) return LegacyBakedModelCalls.getQuads(model, state, side, random);
        return hostQuads(model, side, random, renderType == null ? null : ChunkRenderTypeSet.layerOf(renderType));
    }

    /** The chunk layers the model draws in for this state. */
    public static ChunkRenderTypeSet getRenderTypes(BlockStateModel model, BlockState state, RandomSource random, ModelData data) {
        MethodHandle own = LAYERS.of(model);
        if (own != null) return adopt(invoke(own, model, state, random, data, null, null));
        return superGetRenderTypes(model, state, random, data);
    }

    public static ChunkRenderTypeSet superGetRenderTypes(BlockStateModel model, BlockState state, RandomSource random, ModelData data) {
        if (isOldModel(model)) return LegacyChunkLayers.getRenderLayers(state);
        // Minecraft 26.x stamps each quad with its layer, which is the layer table Forge and NeoForge kept per block.
        return ChunkRenderTypeSet.ofBits(layerBits(model, random));
    }

    public static TextureAtlasSprite getParticleIcon(BlockStateModel model, ModelData data) {
        MethodHandle own = PARTICLE.of(model);
        if (own != null) return (TextureAtlasSprite) invoke(own, model, data, null, null, null, null);
        return superGetParticleIcon(model, data);
    }

    public static TextureAtlasSprite superGetParticleIcon(BlockStateModel model, ModelData data) {
        return LegacyBakedModelCalls.getParticleIcon(model);
    }

    public static ModelData getModelData(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        MethodHandle own = DATA.of(model);
        if (own == null) return data;
        ModelData answered = (ModelData) invoke(own, model, level, pos, state, data, null);
        return answered == null ? data : answered;
    }

    public static ModelData superGetModelData(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        return data;
    }

    /** NeoForge 1.21.1's occlusion question: the model's own answer, else DEFAULT when it uses occlusion at all and FALSE when not. */
    public static TriState useAmbientOcclusion(BlockStateModel model, BlockState state, ModelData data, RenderType renderType) {
        MethodHandle own = OCCLUSION.of(model);
        if (own != null) {
            TriState answered = (TriState) invoke(own, model, state, data, renderType, null, null);
            if (answered != null) return answered;
        }
        return superUseAmbientOcclusion(model, state, data, renderType);
    }

    public static TriState superUseAmbientOcclusion(BlockStateModel model, BlockState state, ModelData data, RenderType renderType) {
        return LegacyBakedModelCalls.useAmbientOcclusion(model) ? TriState.DEFAULT : TriState.FALSE;
    }

    /** Forge 1.20.1's occlusion question for a state. */
    public static boolean useAmbientOcclusion(BlockStateModel model, BlockState state) {
        MethodHandle own = STATE_OCCLUSION.of(model);
        return own != null ? flag(own, model, state, null) : superUseAmbientOcclusion(model, state);
    }

    public static boolean superUseAmbientOcclusion(BlockStateModel model, BlockState state) {
        return LegacyBakedModelCalls.useAmbientOcclusion(model);
    }

    /** Forge 1.20.1's occlusion question for a state and render type. */
    public static boolean useAmbientOcclusion(BlockStateModel model, BlockState state, RenderType renderType) {
        MethodHandle own = LAYER_OCCLUSION.of(model);
        return own != null ? flag(own, model, state, renderType) : superUseAmbientOcclusion(model, state, renderType);
    }

    // Forge 1.20.1's default asked the model's own state question, which may be an override.
    public static boolean superUseAmbientOcclusion(BlockStateModel model, BlockState state, RenderType renderType) {
        return useAmbientOcclusion(model, state);
    }

    // ---- Minecraft 26.x's block model members, drawn through an old model's own members ----

    /** The parts Minecraft draws for an old model: one per chunk layer it names, from its quads for that layer and the block's data. */
    public static void collectParts(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
            List<BlockStateModelPart> parts) {
        if (BROKEN.contains(model)) return;
        List<BlockStateModelPart> collected = new ArrayList<>(2);
        try {
            ModelData data = dataFor(model, level, pos, state);
            // NeoForge 1.21.1 reseeded the random before each side's quads, so a model that picks by random picks once.
            long seed = random.nextLong();
            RandomSource sideRandom = RandomSource.create(seed);
            ChunkRenderTypeSet layers = getRenderTypes(model, state, reseeded(sideRandom, seed), data);
            if (layers == null) layers = ChunkRenderTypeSet.ofLayers(ChunkSectionLayer.SOLID);
            Material.Baked particle = particle(getParticleIcon(model, data));
            for (ChunkSectionLayer layer : layers.layers()) {
                RenderType token = ChunkRenderTypeSet.renderTypeOf(layer);
                BlockStateModelPart part = part(model, state, sideRandom, seed, data, token, particle);
                if (part != null) collected.add(part);
            }
        } catch (RuntimeException | LinkageError failure) {
            fail(model, failure, placed(level, pos, state));
            return;
        }
        parts.addAll(collected);
    }

    public static Material.Baked particleMaterial(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state) {
        if (BROKEN.contains(model)) return null;
        try {
            return particle(getParticleIcon(model, dataFor(model, level, pos, state)));
        } catch (RuntimeException | LinkageError failure) {
            fail(model, failure, placed(level, pos, state));
            return null;
        }
    }

    /** Translucent when the model names the translucent layer for this state, so Minecraft sorts the block's quads. */
    public static int materialFlags(BlockStateModel model, BlockState state) {
        if (BROKEN.contains(model)) return 0;
        try {
            ChunkRenderTypeSet layers = getRenderTypes(model, state, RandomSource.create(42L), ModelData.EMPTY);
            return layers != null && layers.contains(ChunkSectionLayer.TRANSLUCENT) ? FLAG_TRANSLUCENT : 0;
        } catch (RuntimeException | LinkageError failure) {
            fail(model, failure, state != null);
            return 0;
        }
    }

    // A model drawn without a block in place, as the level-less members ask, gets empty data: old models such as Sophisticated
    // Backpacks' read the block entity at the position and throw without one.
    private static ModelData dataFor(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state) {
        if (level == null || pos == null) return ModelData.EMPTY;
        return getModelData(model, level, pos, state, dataAt(level, pos));
    }

    private static boolean placed(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return level != null && pos != null && state != null;
    }

    private static BlockStateModelPart part(BlockStateModel model, BlockState state, RandomSource random, long seed, ModelData data,
            RenderType token, Material.Baked particle) {
        QuadCollection.Builder quads = new QuadCollection.Builder();
        boolean any = false;
        for (Direction side : SIDES) {
            for (BakedQuad quad : nonNull(getQuads(model, state, side, reseeded(random, seed), data, token))) {
                quads.addCulledFace(side, quad);
                any = true;
            }
        }
        for (BakedQuad quad : nonNull(getQuads(model, state, null, reseeded(random, seed), data, token))) {
            quads.addUnculledFace(quad);
            any = true;
        }
        if (!any) return null;
        boolean occlusion = useAmbientOcclusion(model, state, data, token) != TriState.FALSE;
        return new SimpleModelWrapper(quads.build(), occlusion, particle);
    }

    // The model data NeoForge 26.x keeps for the block entity at this position, which NeoForge 1.21.1 handed the model.
    private static ModelData dataAt(BlockAndTintGetter level, BlockPos pos) {
        if (level == null || pos == null) return ModelData.EMPTY;
        try {
            ModelData data = ((IBlockGetterExtension) (Object) level).getModelData(pos);
            return data == null ? ModelData.EMPTY : data;
        } catch (RuntimeException | LinkageError unreadable) {
            return ModelData.EMPTY;
        }
    }

    // A model from Minecraft or a current mod draws through collectParts; an old one, Retromod's item models included,
    // answers the old getQuads.
    private static boolean isOldModel(BlockStateModel model) {
        return model instanceof LegacyItemModelHandle || OLD_QUADS.of(model) != null;
    }

    private static List<BakedQuad> hostQuads(BlockStateModel model, Direction side, RandomSource random, ChunkSectionLayer layer) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(random == null ? RandomSource.create(42L) : random, parts);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(side)) {
                if (layer == null || layerOf(quad) == layer) quads.add(quad);
            }
        }
        return quads;
    }

    private static int layerBits(BlockStateModel model, RandomSource random) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(random == null ? RandomSource.create(42L) : random, parts);
        int bits = 0;
        for (BlockStateModelPart part : parts) {
            for (Direction side : SIDES) bits |= layerBits(part.getQuads(side));
            bits |= layerBits(part.getQuads(null));
        }
        return bits;
    }

    private static int layerBits(List<BakedQuad> quads) {
        int bits = 0;
        for (BakedQuad quad : quads) {
            ChunkSectionLayer layer = layerOf(quad);
            if (layer != null) bits |= ChunkRenderTypeSet.ofLayers(layer).bits();
        }
        return bits;
    }

    private static ChunkSectionLayer layerOf(BakedQuad quad) {
        BakedQuad.MaterialInfo info = quad == null ? null : quad.materialInfo();
        return info == null ? null : info.layer();
    }

    /** This copy's set for a set another jar's copy made, read through the layer bits every copy keeps. */
    static ChunkRenderTypeSet adopt(Object set) {
        if (set == null) return null;
        if (set instanceof ChunkRenderTypeSet own) return own;
        try {
            return ChunkRenderTypeSet.ofBits((int) set.getClass().getMethod("bits").invoke(set));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            return ChunkRenderTypeSet.ofLayers(ChunkSectionLayer.SOLID);
        }
    }

    private static Material.Baked particle(TextureAtlasSprite sprite) {
        return sprite == null ? null : new Material.Baked(sprite, false);
    }

    private static List<BakedQuad> nonNull(List<BakedQuad> quads) {
        return quads == null ? List.of() : quads;
    }

    private static RandomSource reseeded(RandomSource random, long seed) {
        random.setSeed(seed);
        return random;
    }

    // Only a throw for a block in place turns the model off; one asked without a block drew nothing this once.
    private static void fail(BlockStateModel model, Throwable failure, boolean placed) {
        if (placed) BROKEN.add(model);
        LegacyLog.warnOnce("legacy model data " + model.getClass().getName(), "The old model " + model.getClass().getName()
                + " threw while Minecraft drew it, so Retromod draws nothing in its place: " + failure);
    }

    // Each lookup is typed to take the model and up to five arguments as Objects, so one call shape serves every member.

    private static Object invoke(MethodHandle own, Object model, Object a, Object b, Object c, Object d, Object e) {
        try {
            return (Object) own.invokeExact(model, a, b, c, d, e);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    private static boolean flag(MethodHandle own, Object model, Object a, Object b) {
        Object answered = invoke(own, model, a, b, null, null, null);
        return answered instanceof Boolean value && value;
    }

    /** One old member, found once per model class by its name and parameters, whatever copy's types its result has. */
    private static final class Member extends ClassValue<Optional<MethodHandle>> {
        private static final int ARGUMENTS = 5;
        private final String name;
        private final Class<?>[] parameters;

        Member(String name, Class<?>... parameters) {
            this.name = name;
            this.parameters = parameters;
        }

        MethodHandle of(Object model) {
            return model == null ? null : get(model.getClass()).orElse(null);
        }

        @Override
        protected Optional<MethodHandle> computeValue(Class<?> model) {
            // Minecraft's own models never declared the old members.
            if (model.getName().startsWith("net.minecraft.")) return Optional.empty();
            try {
                Method method = model.getMethod(name, parameters);
                if (method.getDeclaringClass().getName().startsWith("net.minecraft.")) return Optional.empty();
                return Optional.of(spread(find(model, method)));
            } catch (NoSuchMethodException absent) {
                return Optional.empty();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError closed) {
                LegacyLog.warnOnce("legacy model data member " + model.getName(), "Retromod could not call the model data members of "
                        + model.getName() + ", so it answers them as NeoForge 1.21.1 did by default: " + closed);
                return Optional.empty();
            }
        }

        // Through the mod's own jar copy first; another jar's model class is reached through the public type declaring it.
        private MethodHandle find(Class<?> model, Method method) throws ReflectiveOperationException {
            try {
                return lookupFor(model).findVirtual(model, name, MethodType.methodType(method.getReturnType(), parameters));
            } catch (IllegalAccessException hidden) {
                return MethodHandles.publicLookup().unreflect(method);
            }
        }

        // Takes (Object model, Object... five arguments) and returns an Object; unused trailing arguments are dropped.
        private MethodHandle spread(MethodHandle found) {
            MethodHandle general = found.asType(found.type().generic());
            if (parameters.length < ARGUMENTS) {
                Class<?>[] unused = new Class<?>[ARGUMENTS - parameters.length];
                java.util.Arrays.fill(unused, Object.class);
                general = MethodHandles.dropArguments(general, parameters.length + 1, unused);
            }
            return general;
        }
    }

    // The mod's own jar copy reaches its non-public classes; any other class is reached through its public members.
    private static MethodHandles.Lookup lookupFor(Class<?> model) {
        try {
            return MethodHandles.privateLookupIn(model, MethodHandles.lookup());
        } catch (IllegalAccessException | RuntimeException elsewhere) {
            return MethodHandles.publicLookup();
        }
    }
}
