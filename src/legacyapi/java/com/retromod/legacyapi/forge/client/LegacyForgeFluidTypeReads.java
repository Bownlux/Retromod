/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client;

import java.lang.reflect.Method;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

/** Forge 1.20.1's positional fluid reads, which Forge 26.2 dropped: the old override, else the plain read Forge kept. */
public final class LegacyForgeFluidTypeReads {

    private static final Class<?>[] AT = {FluidState.class, BlockAndTintGetter.class, BlockPos.class};

    private LegacyForgeFluidTypeReads() {}

    public static Identifier getStillTexture(IClientFluidTypeExtensions ext, FluidState state, BlockAndTintGetter level, BlockPos pos) {
        Object own = own(ext, "getStillTexture", state, level, pos);
        return own instanceof Identifier id ? id : ext.getStillTexture();
    }

    public static Identifier getFlowingTexture(IClientFluidTypeExtensions ext, FluidState state, BlockAndTintGetter level, BlockPos pos) {
        Object own = own(ext, "getFlowingTexture", state, level, pos);
        return own instanceof Identifier id ? id : ext.getFlowingTexture();
    }

    public static Identifier getOverlayTexture(IClientFluidTypeExtensions ext, FluidState state, BlockAndTintGetter level, BlockPos pos) {
        Object own = own(ext, "getOverlayTexture", state, level, pos);
        return own instanceof Identifier id ? id : ext.getOverlayTexture();
    }

    public static int getTintColor(IClientFluidTypeExtensions ext, FluidState state, BlockAndTintGetter level, BlockPos pos) {
        Object own = own(ext, "getTintColor", state, level, pos);
        return own instanceof Integer tint ? tint : ext.getTintColor();
    }

    /** {@code renderOverlay(Minecraft, PoseStack)}: Forge 26.2 draws the overlay through its own three-argument hook, so the old call does nothing. */
    public static void renderOverlay(IClientFluidTypeExtensions ext, Object minecraft, Object poseStack) {}

    // The extension's own positional override, which Forge 26.2's interface no longer declares or calls.
    private static Object own(Object ext, String name, Object... args) {
        try {
            Method method = ext.getClass().getMethod(name, AT);
            method.setAccessible(true);
            return method.invoke(ext, args);
        } catch (ReflectiveOperationException | RuntimeException none) {
            return null;
        }
    }
}
