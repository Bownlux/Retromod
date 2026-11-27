/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.render.fluid;

import com.retromod.legacyapi.minecraft.client.renderer.block.LegacyFluidModels;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** The old {@code FluidRenderHandler} reads that Fabric API 26.x dropped: a handler's own method, else the fluid's model. */
public final class LegacyFluidRenderHandlers {

    private static final Class<?>[] AT = {BlockAndTintGetter.class, BlockPos.class, FluidState.class};

    private LegacyFluidRenderHandlers() {}

    public static TextureAtlasSprite[] getFluidSprites(FluidRenderHandler handler, BlockAndTintGetter view, BlockPos pos, FluidState state) {
        Object own = own(handler, "getFluidSprites", AT, view, pos, state);
        return own instanceof TextureAtlasSprite[] sprites ? sprites : LegacyFluidModels.sprites(state);
    }

    /** The handler's color, else the model's tint, white for no tint, as the old default was. */
    public static int getFluidColor(FluidRenderHandler handler, BlockAndTintGetter view, BlockPos pos, FluidState state) {
        Object own = own(handler, "getFluidColor", AT, view, pos, state);
        return own instanceof Integer color ? color : LegacyFluidModels.tint(state, view, pos);
    }

    /** {@code reloadTextures(atlas)}: a handler's own reload, which a rebuilt handler does not need. */
    public static void reloadTextures(FluidRenderHandler handler, TextureAtlas atlas) {
        own(handler, "reloadTextures", new Class<?>[]{TextureAtlas.class}, atlas);
    }

    /** The old {@code renderFluid(pos, view, consumer, block, fluid)}: the host draws fluids from their models, so nothing is drawn here. */
    public static void renderFluid(FluidRenderHandler handler, BlockPos pos, BlockAndTintGetter view, Object consumer,
            BlockState block, FluidState fluid) {}

    /** A fluid model from a handler whose textures are known before the atlas loads, or null. */
    static FluidModel.Unbaked model(FluidRenderHandler handler) {
        if (!(handler instanceof LegacySimpleFluidRenderHandler simple)) return null;
        // The handler's color is asked per position, so a subclass that tints by biome keeps doing so.
        BlockTintSource tint = new BlockTintSource() {
            @Override
            public int color(BlockState state) {
                return colorAt(null, null, state);
            }

            @Override
            public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
                return colorAt(level, pos, state);
            }

            // Chunk builders ask this off the main thread, where a subclass that throws must not stop the chunk.
            private int colorAt(BlockAndTintGetter level, BlockPos pos, BlockState state) {
                try {
                    return simple.getFluidColor(level, pos, state.getFluidState());
                } catch (RuntimeException | LinkageError failure) {
                    return simple.tint;
                }
            }
        };
        return LegacyFluidModels.unbaked(simple.stillTexture, simple.flowingTexture, simple.overlayTexture, tint);
    }

    // The object's own public method; 26.x's interface declares none of these.
    private static Object own(Object target, String name, Class<?>[] params, Object... args) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(name, params);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException none) {
            return null;
        }
    }

    /** A handler for a fluid no old mod registered, whose reads fall through to the fluid's model. */
    static final class HostHandler implements FluidRenderHandler {}
}
