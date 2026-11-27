/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.render.fluid;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.legacyapi.minecraft.client.renderer.block.LegacyFluidModels;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;

/** Fabric API's old {@code FluidRenderHandlerRegistry}, for every handler: simple ones become fluid models, others draw through Retromod. */
public interface LegacyFluidHandlerRegistry {

    LegacyFluidHandlerRegistry INSTANCE = new Registry();

    /** The handler an old mod registered, else one that reads the fluid's model; null for no fluid. */
    FluidRenderHandler get(Fluid fluid);

    void register(Fluid fluid, FluidRenderHandler handler);

    default void register(Fluid still, Fluid flowing, FluidRenderHandler handler) {
        register(still, handler);
        register(flowing, handler);
    }

    void setBlockTransparency(Block block, boolean transparent);

    boolean isBlockTransparent(Block block);

    /** The one registry; its handlers are shared by every mod's copy, so any mod's get sees another mod's handler. */
    final class Registry implements LegacyFluidHandlerRegistry {
        private static final String HANDLERS = "fabric.fluid.handlers";

        @Override
        public FluidRenderHandler get(Fluid fluid) {
            if (fluid == null) return null;
            Object own = handlers().get(fluid);
            return own instanceof FluidRenderHandler handler ? handler : new LegacyFluidRenderHandlers.HostHandler();
        }

        @Override
        public void register(Fluid fluid, FluidRenderHandler handler) {
            if (fluid == null || handler == null) return;
            handlers().put(fluid, handler);
            try {
                FluidModel.Unbaked simple = LegacyFluidRenderHandlers.model(handler);
                if (simple != null) {
                    FluidRenderingRegistry.register(fluid, simple);
                } else {
                    // Water's textures stand in, so the host's geometry has sprites to swap for the handler's own.
                    FluidRenderingRegistry.register(fluid, LegacyFluidModels.unbaked(LegacySimpleFluidRenderHandler.WATER_STILL,
                            LegacySimpleFluidRenderHandler.WATER_FLOWING, LegacySimpleFluidRenderHandler.WATER_OVERLAY, tint(handler)),
                            new LegacyFluidRenderer(handler));
                }
            } catch (RuntimeException | LinkageError failure) {
                LegacyLog.warn("Retromod could not give an old mod's fluid its handler: " + failure);
            }
        }

        @Override
        public void setBlockTransparency(Block block, boolean transparent) {
            FluidRenderingRegistry.setBlockTransparency(block, transparent);
        }

        @Override
        public boolean isBlockTransparent(Block block) {
            return FluidRenderingRegistry.isBlockTransparent(block);
        }

        // The handler's color per position; chunk builders ask off the main thread, where a failure must not stop the chunk.
        private static BlockTintSource tint(FluidRenderHandler handler) {
            return new BlockTintSource() {
                @Override
                public int color(BlockState state) {
                    return colorAt(null, null, state);
                }

                @Override
                public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
                    return colorAt(level, pos, state);
                }

                private int colorAt(BlockAndTintGetter level, BlockPos pos, BlockState state) {
                    try {
                        Integer own = LegacyFluidRenders.ownColor(handler, level, pos, state.getFluidState());
                        return own != null ? own : LegacyFluidModels.WHITE;
                    } catch (RuntimeException | LinkageError failure) {
                        return LegacyFluidModels.WHITE;
                    }
                }
            };
        }

        @SuppressWarnings("unchecked")
        private static Map<Object, Object> handlers() {
            return LegacySharedSlots.get(HANDLERS, 1, Map.class, ConcurrentHashMap::new);
        }
    }
}
