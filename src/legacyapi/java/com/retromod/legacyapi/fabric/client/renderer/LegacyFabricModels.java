/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Runs an old model's emit methods and old members inside 26.x's model drawing, and the old FabricBakedModel calls on any model. */
public final class LegacyFabricModels {

    private static final Direction[] SIDES = Direction.values();

    // The old members, found once per model class on the class itself, so any mod's model answers.
    private static final Own GET_QUADS = new Own("getQuads",
            MethodType.methodType(List.class, BlockState.class, Direction.class, RandomSource.class));
    private static final Own PARTICLE = new Own("getParticleIcon", MethodType.methodType(TextureAtlasSprite.class));
    private static final Own AMBIENT = new Own("useAmbientOcclusion", MethodType.methodType(boolean.class));
    private static final Own VANILLA_ADAPTER = new Own("isVanillaAdapter", MethodType.methodType(boolean.class));
    private static final Own EMIT_BLOCK = new Own("emitBlockQuads", MethodType.methodType(void.class, BlockAndTintGetter.class,
            BlockState.class, BlockPos.class, Supplier.class, QuadEmitter.class));
    private static final Own EMIT_ITEM = new Own("emitItemQuads", MethodType.methodType(void.class, ItemStack.class, Supplier.class,
            QuadEmitter.class));

    // Model classes whose emit methods failed once draw their plain quads from then on; ones whose quads failed draw nothing.
    private static final Set<Class<?>> EMIT_FAILED = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> QUADS_FAILED = ConcurrentHashMap.newKeySet();

    private LegacyFabricModels() {}

    // ---- The host draws an old model ------------------------------------------------------------------------------

    /** The block draw of an old model: its emitBlockQuads, or its quads when it adapts a vanilla model. False if its code failed. */
    static boolean emitBlock(LegacyFabricBakedModel model, QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos,
            BlockState state, RandomSource random, Predicate<Direction> cullTest) {
        Class<?> type = model.getClass();
        // A Minecraft model an old mixin gave this interface keeps the host's draw.
        if (EMIT_FAILED.contains(type) || type.getName().startsWith("net.minecraft.")) return false;
        LegacyRenderContexts.enterBlock(emitter, level, pos, state, random, cullTest);
        try {
            Supplier<RandomSource> supplier = LegacyRenderContexts.randomSupplier(random);
            if (model.isVanillaAdapter()) emitVanillaBlock(model, emitter, state, supplier);
            else model.emitBlockQuads(level, state, pos, supplier, emitter);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            emitFailed(type, failure);
            return false;
        } finally {
            LegacyRenderContexts.exit();
        }
    }

    /** The parts of an old model, built from its quads; a model whose quads fail has none. */
    static void collectParts(Object model, RandomSource random, List<BlockStateModelPart> parts) {
        MethodHandle quads = GET_QUADS.of(model);
        if (quads == null || QUADS_FAILED.contains(model.getClass())) return;
        try {
            long seed = random == null ? 42L : random.nextLong();
            RandomSource sideRandom = random == null ? RandomSource.create(seed) : random;
            @SuppressWarnings("unchecked")
            List<BakedQuad>[] sides = new List[SIDES.length];
            for (Direction side : SIDES) {
                sideRandom.setSeed(seed);
                sides[side.ordinal()] = quads(quads, model, null, side, sideRandom);
            }
            sideRandom.setSeed(seed);
            List<BakedQuad> unculled = quads(quads, model, null, null, sideRandom);
            parts.add(new LegacyQuadsPart(sides, unculled, ambientOcclusion(model), particleMaterial(model)));
        } catch (RuntimeException | LinkageError failure) {
            quadsFailed(model.getClass(), failure);
        }
    }

    /** Translucent and animated: what a model reports when its quads cannot be read, which the host accepts at some cost. */
    static final int ALL_FLAGS = 3;

    // Each old model's flags, worked out once: the host asks for them every frame for moving blocks and block displays.
    private static final Map<Object, Integer> FLAGS = Collections.synchronizedMap(new WeakHashMap<>());

    /** The material flags of an old model: those of its quads, or all of them when it gives no quads to read. */
    static int materialFlags(Object model) {
        Integer known = FLAGS.get(model);
        if (known != null) return known;
        List<BlockStateModelPart> parts = new ArrayList<>();
        collectParts(model, null, parts);
        int flags = 0;
        boolean any = false;
        for (BlockStateModelPart part : parts) {
            if (part instanceof LegacyQuadsPart quads && quads.empty()) continue;
            flags |= part.materialFlags();
            any = true;
        }
        int result = any ? flags : ALL_FLAGS;
        FLAGS.put(model, result);
        return result;
    }

    /** The particle texture of an old model, else the missing model's. */
    static Material.Baked particleMaterial(Object model) {
        MethodHandle own = PARTICLE.of(model);
        if (own != null && !QUADS_FAILED.contains(model.getClass())) {
            try {
                TextureAtlasSprite sprite = (TextureAtlasSprite) own.invokeExact(model);
                if (sprite != null) return new Material.Baked(sprite, false);
            } catch (RuntimeException | Error failure) {
                quadsFailed(model.getClass(), failure);
            } catch (Throwable unexpected) {
                quadsFailed(model.getClass(), unexpected);
            }
        }
        try {
            return Minecraft.getInstance().getModelManager().getBlockStateModelSet().missingModel().particleMaterial();
        } catch (RuntimeException | LinkageError notLoaded) {
            return null;
        }
    }

    // ---- Old calls on any model ------------------------------------------------------------------------------------

    /** {@code model.isVanillaAdapter()}: the model's own answer, true for Minecraft's and current mods' models. */
    public static boolean isVanillaAdapter(Object model) {
        if (model instanceof LegacyFabricBakedModel legacy) return legacy.isVanillaAdapter();
        MethodHandle own = VANILLA_ADAPTER.of(model);
        if (own == null) return true;
        try {
            return (boolean) own.invokeExact(model);
        } catch (Throwable failure) {
            return true;
        }
    }

    /** {@code model.emitBlockQuads(...)}: the model's own method, else its 26.x draw into the same emitter. */
    @SuppressWarnings("unchecked")
    public static void emitBlockQuads(Object model, BlockAndTintGetter level, BlockState state, BlockPos pos,
            Supplier<RandomSource> random, QuadEmitter context) {
        if (model instanceof LegacyFabricBakedModel legacy) {
            legacy.emitBlockQuads(level, state, pos, random, context);
            return;
        }
        MethodHandle own = EMIT_BLOCK.of(model);
        if (own != null) {
            invoke(own, model, level, state, pos, random, context);
            return;
        }
        Object[] frame = LegacyRenderContexts.frame(context);
        Predicate<Direction> cull = frame == null ? null : (Predicate<Direction>) frame[LegacyRenderContexts.CULL];
        emitBlockModel(model, context, level, pos, state, random == null ? null : random.get(), cull);
    }

    /** {@code model.emitItemQuads(...)}: the model's own method, else its quads as an item. */
    public static void emitItemQuads(Object model, ItemStack stack, Supplier<RandomSource> random, QuadEmitter context) {
        if (model instanceof LegacyFabricBakedModel legacy) {
            legacy.emitItemQuads(stack, random, context);
            return;
        }
        MethodHandle own = EMIT_ITEM.of(model);
        if (own != null) {
            invoke(own, model, stack, random, context);
            return;
        }
        emitPartsAsItem(model, context, random == null ? RandomSource.create(42L) : random.get());
    }

    // ---- Drawing helpers ----------------------------------------------------------------------------------------

    /** Old VanillaModelEncoder: each side's quads unless the side is culled, then the unculled ones. */
    static void emitVanillaBlock(Object model, QuadEmitter context, BlockState state, Supplier<RandomSource> random) {
        MethodHandle quads = GET_QUADS.of(model);
        if (quads == null) return;
        boolean occlusion = ambientOcclusion(model);
        for (int index = 0; index <= SIDES.length; index++) {
            Direction side = index < SIDES.length ? SIDES[index] : null;
            if (LegacyRenderContexts.isFaceCulled(context, side)) continue;
            emitQuads(context, quads(quads, model, state, side, random.get()), side, occlusion);
        }
    }

    /** Old VanillaModelEncoder for items: every quad, with no culling. */
    static void emitVanillaItem(Object model, QuadEmitter context, Supplier<RandomSource> random) {
        MethodHandle quads = GET_QUADS.of(model);
        if (quads == null) return;
        boolean occlusion = ambientOcclusion(model);
        for (int index = 0; index <= SIDES.length; index++) {
            Direction side = index < SIDES.length ? SIDES[index] : null;
            emitQuads(context, quads(quads, model, null, side, random.get()), side, occlusion);
        }
    }

    /** Draws any block model, old or new, at a block: through its 26.x emitQuads. */
    static void emitBlockModel(Object model, QuadEmitter context, BlockAndTintGetter level, BlockPos pos, BlockState state,
            RandomSource random, Predicate<Direction> cullTest) {
        if (!(model instanceof BlockStateModel)) return;
        Predicate<Direction> cull = cullTest != null ? cullTest : side -> false;
        ((FabricBlockStateModel) model).emitQuads(context, level, pos, state, random == null ? RandomSource.create(42L) : random, cull);
    }

    /** Draws any model as an item: an old model through its emitItemQuads, any other through its parts. */
    public static void emitItemModel(Object model, QuadEmitter context, ItemStack stack, ItemDisplayContext displayContext,
            RandomSource random) {
        RandomSource itemRandom = random == null ? RandomSource.create(42L) : random;
        if (!(model instanceof LegacyFabricBakedModel legacy)) {
            if (VANILLA_ADAPTER.of(model) != null && !isVanillaAdapter(model)) {
                emitItemQuads(model, stack, LegacyRenderContexts.randomSupplier(itemRandom), context);
            } else {
                emitPartsAsItem(model, context, itemRandom);
            }
            return;
        }
        if (EMIT_FAILED.contains(model.getClass())) {
            emitPartsAsItem(model, context, itemRandom);
            return;
        }
        LegacyRenderContexts.enterItem(context, stack, displayContext, itemRandom);
        try {
            Supplier<RandomSource> supplier = LegacyRenderContexts.randomSupplier(itemRandom);
            if (legacy.isVanillaAdapter()) emitVanillaItem(legacy, context, supplier);
            else legacy.emitItemQuads(stack, supplier, context);
        } catch (RuntimeException | LinkageError failure) {
            emitFailed(model.getClass(), failure);
        } finally {
            LegacyRenderContexts.exit();
        }
    }

    private static void emitPartsAsItem(Object model, QuadEmitter context, RandomSource random) {
        if (!(model instanceof BlockStateModel block)) return;
        List<BlockStateModelPart> parts = new ArrayList<>();
        block.collectParts(random, parts);
        for (BlockStateModelPart part : parts) {
            boolean occlusion = part.useAmbientOcclusion();
            for (int index = 0; index <= SIDES.length; index++) {
                Direction side = index < SIDES.length ? SIDES[index] : null;
                emitQuads(context, part.getQuads(side), side, occlusion);
            }
        }
    }

    private static void emitQuads(QuadEmitter context, List<BakedQuad> quads, Direction side, boolean occlusion) {
        if (quads == null) return;
        for (BakedQuad quad : quads) {
            context.fromBakedQuad(quad);
            context.cullFace(side);
            if (!occlusion) context.ambientOcclusion(TriState.FALSE);
            context.emit();
        }
    }

    private static boolean ambientOcclusion(Object model) {
        MethodHandle own = AMBIENT.of(model);
        if (own == null) return true;
        try {
            return (boolean) own.invokeExact(model);
        } catch (Throwable failure) {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BakedQuad> quads(MethodHandle quads, Object model, BlockState state, Direction side, RandomSource random) {
        try {
            return (List<BakedQuad>) quads.invokeExact(model, state, side, random);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    private static void invoke(MethodHandle own, Object model, Object... arguments) {
        Object[] all = new Object[arguments.length + 1];
        all[0] = model;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        try {
            own.invokeWithArguments(all);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    private static void emitFailed(Class<?> type, Throwable failure) {
        if (EMIT_FAILED.add(type)) {
            LegacyLog.warn("The old model " + type.getName() + " failed while drawing (" + failure + "). Retromod draws its"
                    + " plain quads from now on.");
        }
    }

    private static void quadsFailed(Class<?> type, Throwable failure) {
        if (QUADS_FAILED.add(type)) {
            LegacyLog.warn("The old model " + type.getName() + " failed to give its quads (" + failure + "). Retromod leaves"
                    + " it undrawn from now on.");
        }
    }

    /** One old member, found once per model class on the model's own class, typed to take the model as an Object. */
    private static final class Own extends ClassValue<Optional<MethodHandle>> {
        private final String name;
        private final MethodType type;

        Own(String name, MethodType type) {
            this.name = name;
            this.type = type;
        }

        MethodHandle of(Object model) {
            return model == null ? null : get(model.getClass()).orElse(null);
        }

        @Override
        protected Optional<MethodHandle> computeValue(Class<?> model) {
            // Minecraft's own models never declared the old members.
            if (model.getName().startsWith("net.minecraft.")) return Optional.empty();
            try {
                MethodHandle found = lookupFor(model).findVirtual(model, name, type);
                return Optional.of(found.asType(type.insertParameterTypes(0, Object.class)));
            } catch (NoSuchMethodException absent) {
                return Optional.empty();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError closed) {
                return Optional.empty();
            }
        }
    }

    private static MethodHandles.Lookup lookupFor(Class<?> model) {
        try {
            return MethodHandles.privateLookupIn(model, MethodHandles.lookup());
        } catch (IllegalAccessException | RuntimeException elsewhere) {
            return MethodHandles.publicLookup();
        }
    }
}
