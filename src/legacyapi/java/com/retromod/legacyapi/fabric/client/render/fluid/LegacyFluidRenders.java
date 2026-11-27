/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.render.fluid;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.retromod.legacyapi.minecraft.client.renderer.block.LegacyFluidModels;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** The old {@code FluidRenderHandler} members on any handler: the handler's own, else the fluid's 26.x model. */
public final class LegacyFluidRenders {

    // The old members of each handler class, found once; a chunk thread never reflects per fluid block.
    private static final ClassValue<Own> OWN = new ClassValue<>() {
        @Override
        protected Own computeValue(Class<?> type) {
            return new Own(type);
        }
    };

    private LegacyFluidRenders() {}

    /** {@code handler.getFluidSprites(level, pos, state)}. */
    public static TextureAtlasSprite[] getFluidSprites(FluidRenderHandler handler, BlockAndTintGetter level, BlockPos pos,
            FluidState state) {
        TextureAtlasSprite[] own = sprites(handler, level, pos, state);
        return own != null ? own : LegacyFluidModels.sprites(state);
    }

    /** {@code handler.getFluidColor(level, pos, state)}: the handler's color, else the model's tint. */
    public static int getFluidColor(FluidRenderHandler handler, BlockAndTintGetter level, BlockPos pos, FluidState state) {
        Integer own = ownColor(handler, level, pos, state);
        return own != null ? own : LegacyFluidModels.tint(state, level, pos);
    }

    /** {@code handler.reloadTextures(atlas)}. */
    public static void reloadTextures(FluidRenderHandler handler, TextureAtlas atlas) {
        reload(handler, atlas);
    }

    /** {@code handler.renderFluid(pos, level, consumer, block, fluid)}: the handler's own draw, else the host's with its textures. */
    public static void renderFluid(FluidRenderHandler handler, BlockPos pos, BlockAndTintGetter level, VertexConsumer consumer,
            BlockState blockState, FluidState fluidState) {
        if (!drawOwn(handler, pos, level, consumer, blockState, fluidState)) {
            LegacyFluidRenderer.renderDefault(handler, pos, level, consumer, blockState, fluidState);
        }
    }

    /** The old default an override reaches through {@code super.renderFluid}, which never runs that override again. */
    public static void superRenderFluid(FluidRenderHandler handler, BlockPos pos, BlockAndTintGetter level, VertexConsumer consumer,
            BlockState blockState, FluidState fluidState) {
        LegacyFluidRenderer.renderDefault(handler, pos, level, consumer, blockState, fluidState);
    }

    /** Whether the handler declares its own old {@code renderFluid}. */
    static boolean hasOwnDraw(Object handler) {
        return handler != null && OWN.get(handler.getClass()).render != null;
    }

    /** Runs the handler's own old draw, passing {@code consumer} only whole quads and only once it finishes; false if it did not. */
    static boolean drawOwn(Object handler, BlockPos pos, BlockAndTintGetter level, VertexConsumer consumer, BlockState blockState,
            FluidState fluidState) {
        Own own = handler == null ? null : OWN.get(handler.getClass());
        if (own == null || own.render == null || own.failed || consumer == null) return false;
        LegacyFluidQuads quads = new LegacyFluidQuads(consumer, null, null);
        try {
            own.render.invoke(handler, pos, level, quads, blockState, fluidState);
        } catch (RuntimeException | LinkageError failure) {
            // A draw that stopped partway, even in the middle of a vertex, passes on nothing.
            quads.discard();
            own.fail("drew its fluid", failure);
            return false;
        } catch (Throwable other) {
            quads.discard();
            throw rethrow(other);
        }
        quads.commit();
        return true;
    }

    /** The handler's own old color, or null when it has no such method or it failed. */
    static Integer ownColor(Object handler, BlockAndTintGetter level, BlockPos pos, FluidState state) {
        Own own = handler == null ? null : OWN.get(handler.getClass());
        if (own == null || own.color == null || own.failed) return null;
        try {
            return (int) own.color.invoke(handler, level, pos, state);
        } catch (RuntimeException | LinkageError failure) {
            own.fail("gave its fluid color", failure);
            return null;
        } catch (Throwable other) {
            throw rethrow(other);
        }
    }

    /** The handler's own old sprites, or null when it has no such method, answers none, or failed. */
    static TextureAtlasSprite[] sprites(Object handler, BlockAndTintGetter level, BlockPos pos, FluidState state) {
        Own own = handler == null ? null : OWN.get(handler.getClass());
        if (own == null || own.sprites == null || own.failed) return null;
        try {
            TextureAtlasSprite[] sprites = (TextureAtlasSprite[]) own.sprites.invoke(handler, level, pos, state);
            return sprites != null && sprites.length > 0 && sprites[0] != null ? sprites : null;
        } catch (RuntimeException | LinkageError failure) {
            own.fail("gave its fluid sprites", failure);
            return null;
        } catch (Throwable other) {
            throw rethrow(other);
        }
    }

    static void reload(Object handler, TextureAtlas atlas) {
        Own own = handler == null ? null : OWN.get(handler.getClass());
        if (own == null || own.reload == null || own.failed) return;
        try {
            own.reload.invoke(handler, atlas);
        } catch (RuntimeException | LinkageError failure) {
            own.fail("reloaded its textures", failure);
        } catch (Throwable other) {
            throw rethrow(other);
        }
    }

    private static RuntimeException rethrow(Throwable other) {
        if (other instanceof Error error) throw error;
        return new IllegalStateException(other);
    }

    /** The old members one handler class declares, as handles that take the handler as an Object. */
    private static final class Own {
        final MethodHandle sprites;
        final MethodHandle color;
        final MethodHandle reload;
        final MethodHandle render;
        private final String type;
        private volatile boolean failed;

        Own(Class<?> type) {
            this.type = type.getName();
            sprites = find(type, "getFluidSprites", TextureAtlasSprite[].class, BlockAndTintGetter.class, BlockPos.class, FluidState.class);
            color = find(type, "getFluidColor", int.class, BlockAndTintGetter.class, BlockPos.class, FluidState.class);
            reload = find(type, "reloadTextures", void.class, TextureAtlas.class);
            render = find(type, "renderFluid", void.class, BlockPos.class, BlockAndTintGetter.class, VertexConsumer.class, BlockState.class,
                    FluidState.class);
        }

        // A method of the handler's own class; 26.x's interface declares none of these. It is opened, because old handlers
        // are often anonymous or package-private classes that Retromod's package cannot call into otherwise.
        private static MethodHandle find(Class<?> type, String name, Class<?> result, Class<?>... params) {
            try {
                Method method = type.getMethod(name, params);
                if (method.getDeclaringClass().isInterface()) return null;
                method.setAccessible(true);
                // A renderFluid that still returns its old boolean has the result dropped.
                return MethodHandles.lookup().unreflect(method)
                        .asType(MethodType.methodType(result, params).insertParameterTypes(0, Object.class));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError none) {
                return null;
            }
        }

        // One line per handler class; its old members are not called again, and the fluid model answers instead.
        void fail(String what, Throwable failure) {
            if (failed) return;
            failed = true;
            LegacyLog.warn("The old fluid handler " + type + " failed when it " + what + " (" + failure + "), so Retromod draws"
                    + " its fluid from the fluid's model from now on.");
        }
    }
}
