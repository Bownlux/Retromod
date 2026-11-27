/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.render.fluid;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

/** Fabric API's {@code FluidRenderHandlerRegistry} of 1.20.1 and 1.21.1, which Fabric API for 26.1 replaced with fluid models. */
public interface LegacyFluidRenderHandlerRegistry {

    LegacyFluidRenderHandlerRegistry INSTANCE = new Registry();

    /** The handler an old mod registered, else one that reads the fluid's model; null for no fluid. */
    FluidRenderHandler get(Fluid fluid);

    void register(Fluid fluid, FluidRenderHandler handler);

    default void register(Fluid still, Fluid flowing, FluidRenderHandler handler) {
        register(still, handler);
        register(flowing, handler);
    }

    void setBlockTransparency(Block block, boolean transparent);

    boolean isBlockTransparent(Block block);

    /** The one registry; its handlers live in a map of its own, which the host does not read. */
    final class Registry implements LegacyFluidRenderHandlerRegistry {
        private final Map<Fluid, FluidRenderHandler> handlers = new ConcurrentHashMap<>();

        @Override
        public FluidRenderHandler get(Fluid fluid) {
            if (fluid == null) return null;
            FluidRenderHandler own = handlers.get(fluid);
            return own != null ? own : new LegacyFluidRenderHandlers.HostHandler();
        }

        @Override
        public void register(Fluid fluid, FluidRenderHandler handler) {
            if (fluid == null || handler == null) return;
            handlers.put(fluid, handler);
            FluidModel.Unbaked model = LegacyFluidRenderHandlers.model(handler);
            if (model == null) {
                LegacyLog.warnOnce("fluid handler " + handler.getClass().getName(), "The old fluid handler "
                        + handler.getClass().getName() + " names its textures only once they load, so Fabric API 26.x draws its"
                        + " fluids with the missing texture.");
                return;
            }
            try {
                FluidRenderingRegistry.register(fluid, model);
            } catch (RuntimeException failure) {
                LegacyLog.warn("Retromod could not give an old mod's fluid its model: " + failure);
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
    }
}
