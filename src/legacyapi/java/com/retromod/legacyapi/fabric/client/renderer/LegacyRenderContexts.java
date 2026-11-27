/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadTransform;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** The old {@code RenderContext}, which is 26.x's quad emitter plus what Retromod records about the draw in progress. */
public final class LegacyRenderContexts {

    // One stack of draws per thread, shared by every mod's copy, so a model can hand its context to another mod's model.
    private static final String FRAMES = "fabric.renderer.frames";
    private static final int PROTOCOL = 1;
    static final int EMITTER = 0, LEVEL = 1, POS = 2, STATE = 3, RANDOM = 4, CULL = 5, STACK = 6, DISPLAY = 7, SEED = 8, PUSHES = 9;

    private LegacyRenderContexts() {}

    /** Records a block draw into {@code emitter} until {@link #exit()}. */
    public static void enterBlock(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
            Predicate<Direction> cullTest) {
        frames().push(new Object[]{emitter, level, pos, state, random, cullTest, null, null, seed(random), new int[1]});
    }

    /** Records an item draw into {@code emitter} until {@link #exit()}. */
    public static void enterItem(QuadEmitter emitter, ItemStack stack, ItemDisplayContext displayContext, RandomSource random) {
        frames().push(new Object[]{emitter, null, null, null, random, null, stack, displayContext, seed(random), new int[1]});
    }

    /** Ends the draw the last enter call started, taking off any transform the old model pushed and left, as when it failed. */
    public static void exit() {
        ArrayDeque<Object[]> frames = frames();
        if (frames.isEmpty()) return;
        Object[] frame = frames.pop();
        int[] pushes = (int[]) frame[PUSHES];
        try {
            for (; pushes[0] > 0; pushes[0]--) ((QuadEmitter) frame[EMITTER]).popTransform();
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("legacy render context transforms", "Retromod could not take an old model's leftover quad"
                    + " transform off the emitter: " + failure);
        }
    }

    /** {@code context.pushTransform(transform)}: the emitter's own, counted so the draw can take off what the model leaves. */
    public static void pushTransform(QuadEmitter context, QuadTransform transform) {
        context.pushTransform(transform);
        Object[] frame = ownFrame(context);
        if (frame != null) ((int[]) frame[PUSHES])[0]++;
    }

    /** {@code context.popTransform()}: never takes off more than the old model pushed in its own draw. */
    public static void popTransform(QuadEmitter context) {
        Object[] frame = ownFrame(context);
        if (frame != null) {
            int[] pushes = (int[]) frame[PUSHES];
            if (pushes[0] == 0) return;
            pushes[0]--;
        }
        context.popTransform();
    }

    /** {@code context.getEmitter()}: the context is the emitter. */
    public static QuadEmitter getEmitter(QuadEmitter context) {
        return context;
    }

    /** {@code context.hasTransform()}: true, which only costs an old model a skipped shortcut. */
    public static boolean hasTransform(QuadEmitter context) {
        return true;
    }

    /** {@code context.isFaceCulled(face)}: the host's cull test for the block being drawn. */
    @SuppressWarnings("unchecked")
    public static boolean isFaceCulled(QuadEmitter context, Direction face) {
        Object[] frame = frame(context);
        if (face == null || frame == null || frame[CULL] == null) return false;
        return ((Predicate<Direction>) frame[CULL]).test(face);
    }

    /** {@code context.itemTransformationMode()}: the display context of the item being drawn, NONE for a block. */
    public static ItemDisplayContext itemTransformationMode(QuadEmitter context) {
        Object[] frame = frame(context);
        return frame != null && frame[DISPLAY] != null ? (ItemDisplayContext) frame[DISPLAY] : ItemDisplayContext.NONE;
    }

    /** {@code context.meshConsumer()}: puts each quad of a mesh through the context's emitter. */
    public static Consumer<Mesh> meshConsumer(QuadEmitter context) {
        return mesh -> {
            if (mesh != null) mesh.outputTo(context);
        };
    }

    /** {@code context.fallbackConsumer()}. */
    public static Consumer<BlockStateModel> fallbackConsumer(QuadEmitter context) {
        return bakedModelConsumer(context);
    }

    /** {@code context.bakedModelConsumer()}: draws another model where the current one is drawn. */
    public static LegacyBakedModelConsumer bakedModelConsumer(QuadEmitter context) {
        return new ModelConsumer(context);
    }

    /** The random supplier old emit methods took: the draw's random, reseeded the same way on each call. */
    public static Supplier<RandomSource> randomSupplier(RandomSource random) {
        long seed = seed(random);
        return () -> {
            random.setSeed(seed);
            return random;
        };
    }

    // The draw recorded for exactly this emitter, or null.
    private static Object[] ownFrame(QuadEmitter emitter) {
        for (Object[] frame : frames()) {
            if (frame[EMITTER] == emitter) return frame;
        }
        return null;
    }

    /** The draw recorded for {@code emitter}, else the innermost one on this thread. */
    static Object[] frame(QuadEmitter emitter) {
        ArrayDeque<Object[]> frames = frames();
        for (Iterator<Object[]> it = frames.iterator(); it.hasNext(); ) {
            Object[] frame = it.next();
            if (frame[EMITTER] == emitter) return frame;
        }
        return frames.peek();
    }

    private static long seed(RandomSource random) {
        return random == null ? 42L : random.nextLong();
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<Object[]> frames() {
        ThreadLocal<ArrayDeque<Object[]>> local = LegacySharedSlots.get(FRAMES, PROTOCOL, ThreadLocal.class,
                () -> ThreadLocal.withInitial(ArrayDeque::new));
        return local.get();
    }

    /** Draws a model into the draw in progress, a block one at its position and an item one as an item. */
    private static final class ModelConsumer implements LegacyBakedModelConsumer {
        private final QuadEmitter context;

        ModelConsumer(QuadEmitter context) {
            this.context = context;
        }

        @Override
        public void accept(BlockStateModel model) {
            Object[] frame = frame(context);
            accept(model, frame == null ? null : (BlockState) frame[STATE]);
        }

        @SuppressWarnings("unchecked")
        @Override
        public void accept(BlockStateModel model, BlockState state) {
            Object[] frame = frame(context);
            if (model == null) return;
            if (frame == null) {
                LegacyLog.warnOnce("legacy model consumer outside a draw", "An old model drew another model outside a block or"
                        + " item draw, which Retromod cannot place, so that model is not drawn.");
                return;
            }
            RandomSource random = (RandomSource) frame[RANDOM];
            if (random != null) random.setSeed((Long) frame[SEED]);
            if (frame[STACK] != null) {
                LegacyFabricModels.emitItemModel(model, context, (ItemStack) frame[STACK], (ItemDisplayContext) frame[DISPLAY], random);
            } else {
                LegacyFabricModels.emitBlockModel(model, context, (BlockAndTintGetter) frame[LEVEL], (BlockPos) frame[POS],
                        state != null ? state : (BlockState) frame[STATE], random, (Predicate<Direction>) frame[CULL]);
            }
        }
    }
}
