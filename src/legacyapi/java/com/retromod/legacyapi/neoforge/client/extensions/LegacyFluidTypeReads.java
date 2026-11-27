/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.extensions;

import com.retromod.legacyapi.minecraft.client.renderer.block.LegacyFluidModels;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.fluid.FluidTintSource;
import net.neoforged.neoforge.fluids.FluidStack;

/** NeoForge 21.1's fluid texture and tint reads, which NeoForge 26.3 moved into fluid models, answered by the old override or the host model. */
public final class LegacyFluidTypeReads {

    private static final String STILL = "getStillTexture", FLOWING = "getFlowingTexture", OVERLAY = "getOverlayTexture";
    private static final String TINT = "getTintColor";
    private static final Class<?>[] NONE = {};
    private static final Class<?>[] AT = {FluidState.class, BlockAndTintGetter.class, BlockPos.class};
    private static final Class<?>[] STACK = {FluidStack.class};
    // Each extension's fluid, found once: the no-argument reads name no fluid.
    private static final Map<Object, Fluid> FLUIDS = new WeakHashMap<>();
    // What an override answers when it threw, so the read falls back as if it had none.
    private static final Object FAILED = new Object();
    // Each class's old read methods, looked up once: the host's chunk builder asks the tint for every fluid block.
    private static final ClassValue<Map<String, Optional<Method>>> METHODS = new ClassValue<>() {
        @Override
        protected Map<String, Optional<Method>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private LegacyFluidTypeReads() {}

    public static Identifier getStillTexture(IClientFluidTypeExtensions ext) {
        return texture(ext, STILL, LegacyFluidModels.STILL, null);
    }

    public static Identifier getFlowingTexture(IClientFluidTypeExtensions ext) {
        return texture(ext, FLOWING, LegacyFluidModels.FLOWING, null);
    }

    public static Identifier getOverlayTexture(IClientFluidTypeExtensions ext) {
        return texture(ext, OVERLAY, LegacyFluidModels.OVERLAY, null);
    }

    public static Identifier getStillTexture(IClientFluidTypeExtensions ext, FluidState state, BlockAndTintGetter level, BlockPos pos) {
        return textureAt(ext, STILL, LegacyFluidModels.STILL, state, level, pos);
    }

    public static Identifier getFlowingTexture(IClientFluidTypeExtensions ext, FluidState state, BlockAndTintGetter level, BlockPos pos) {
        return textureAt(ext, FLOWING, LegacyFluidModels.FLOWING, state, level, pos);
    }

    public static Identifier getOverlayTexture(IClientFluidTypeExtensions ext, FluidState state, BlockAndTintGetter level, BlockPos pos) {
        return textureAt(ext, OVERLAY, LegacyFluidModels.OVERLAY, state, level, pos);
    }

    public static Identifier getStillTexture(IClientFluidTypeExtensions ext, FluidStack stack) {
        return textureOf(ext, STILL, LegacyFluidModels.STILL, stack);
    }

    public static Identifier getFlowingTexture(IClientFluidTypeExtensions ext, FluidStack stack) {
        return textureOf(ext, FLOWING, LegacyFluidModels.FLOWING, stack);
    }

    public static Identifier getOverlayTexture(IClientFluidTypeExtensions ext, FluidStack stack) {
        return textureOf(ext, OVERLAY, LegacyFluidModels.OVERLAY, stack);
    }

    /** {@code getTintColor()}: the old override, else the fluid model's plain tint, else white as the old default. */
    public static int getTintColor(IClientFluidTypeExtensions ext) {
        Object own = own(ext, TINT, NONE);
        if (own instanceof Integer tint) return tint;
        Fluid fluid = fluidOf(ext);
        return fluid == null ? LegacyFluidModels.WHITE : LegacyFluidModels.tint(fluid.defaultFluidState(), null, null);
    }

    /** The positional tint: the old override, else the plain tint (which the old default answered too), else the model's tint there. */
    public static int getTintColor(IClientFluidTypeExtensions ext, FluidState state, BlockAndTintGetter level, BlockPos pos) {
        Object own = own(ext, TINT, AT, state, level, pos);
        if (own instanceof Integer tint) return tint;
        if (declares(ext, TINT, NONE)) return getTintColor(ext);
        return LegacyFluidModels.tint(state, level, pos);
    }

    public static int getTintColor(IClientFluidTypeExtensions ext, FluidStack stack) {
        Object own = own(ext, TINT, STACK, stack);
        if (own instanceof Integer tint) return tint;
        if (declares(ext, TINT, NONE) || stack == null) return getTintColor(ext);
        return LegacyFluidModels.tint(stack.getFluid().defaultFluidState(), null, null);
    }

    /** {@code renderOverlay(Minecraft, PoseStack)}: drawn by the host's own overlay hook now, so the old call does nothing. */
    public static void renderOverlay(IClientFluidTypeExtensions ext, Object minecraft, Object poseStack) {}

    /** {@code renderFluid(...)}: false, the old default, so the host draws the fluid from its model. */
    public static boolean renderFluid(IClientFluidTypeExtensions ext, FluidState state, BlockAndTintGetter level, BlockPos pos,
            Object consumer, BlockState block) {
        return false;
    }

    /** A fluid model for {@code fluid} built from its old extension's textures and tint, or null when it overrides no texture. */
    public static FluidModel.Unbaked oldModel(Fluid fluid, IClientFluidTypeExtensions ext) {
        if (!declares(ext, STILL, NONE) && !declares(ext, STILL, AT)) return null;
        FluidState state = fluid.defaultFluidState();
        Identifier still = getStillTexture(ext, state, null, null);
        Identifier flowing = getFlowingTexture(ext, state, null, null);
        if (still == null || flowing == null) return null;
        return LegacyFluidModels.unbaked(still, flowing, getOverlayTexture(ext, state, null, null), new OldTint(ext));
    }

    // ---- the reads ----

    private static Identifier texture(IClientFluidTypeExtensions ext, String name, int slot, FluidState state) {
        Object own = own(ext, name, NONE);
        if (own != FAILED && (own != null || declares(ext, name, NONE))) return (Identifier) own;
        Fluid fluid = state != null ? state.getType() : fluidOf(ext);
        return fluid == null ? null : LegacyFluidModels.texture(state != null ? state : fluid.defaultFluidState(), slot);
    }

    // The old positional and stack reads defaulted to the plain read, which the extension may override.
    private static Identifier textureAt(IClientFluidTypeExtensions ext, String name, int slot, FluidState state,
            BlockAndTintGetter level, BlockPos pos) {
        Object own = own(ext, name, AT, state, level, pos);
        if (own != FAILED && (own != null || declares(ext, name, AT))) return (Identifier) own;
        return texture(ext, name, slot, state);
    }

    private static Identifier textureOf(IClientFluidTypeExtensions ext, String name, int slot, FluidStack stack) {
        Object own = own(ext, name, STACK, stack);
        if (own != FAILED && (own != null || declares(ext, name, STACK))) return (Identifier) own;
        return texture(ext, name, slot, stack == null ? null : stack.getFluid().defaultFluidState());
    }

    /** What the extension's own override answers, null when it has none, or {@link #FAILED}; 26.3's interface declares none of these. */
    private static Object own(Object ext, String name, Class<?>[] params, Object... args) {
        Method method = method(ext, name, params);
        if (method == null) return null;
        try {
            return method.invoke(ext, args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("fluid read " + ext.getClass().getName() + name, "The old fluid read " + name + " of "
                    + ext.getClass().getName() + " failed, so Retromod answered it the way the old default did: " + failure);
            return FAILED;
        }
    }

    private static boolean declares(Object ext, String name, Class<?>[] params) {
        return method(ext, name, params) != null;
    }

    // The parameter lists differ in length, so the name and length identify a read.
    private static Method method(Object ext, String name, Class<?>[] params) {
        if (ext == null) return null;
        Class<?> type = ext.getClass();
        return METHODS.get(type).computeIfAbsent(name + "/" + params.length, key -> find(type, name, params)).orElse(null);
    }

    private static Optional<Method> find(Class<?> type, String name, Class<?>[] params) {
        try {
            Method method = type.getMethod(name, params);
            // A mod's extension is often an anonymous class, whose public methods still need access to be called.
            method.setAccessible(true);
            return Optional.of(method);
        } catch (NoSuchMethodException | RuntimeException | LinkageError absent) {
            return Optional.empty();
        }
    }

    // The fluid whose extension this is, so a read that names no fluid can still find its model.
    private static Fluid fluidOf(Object ext) {
        synchronized (FLUIDS) {
            if (FLUIDS.containsKey(ext)) return FLUIDS.get(ext);
        }
        Fluid found = null;
        try {
            for (Fluid fluid : BuiltInRegistries.FLUID) {
                if (IClientFluidTypeExtensions.of(fluid) == ext) {
                    found = fluid;
                    break;
                }
            }
        } catch (RuntimeException | LinkageError noRegistry) {
            found = null;
        }
        synchronized (FLUIDS) {
            FLUIDS.put(ext, found);
        }
        return found;
    }

    /** An old extension's tint as a fluid tint source: its positional override in the world, else its plain tint, else white. */
    private static final class OldTint implements FluidTintSource {
        private final IClientFluidTypeExtensions ext;

        OldTint(IClientFluidTypeExtensions ext) {
            this.ext = ext;
        }

        @Override
        public int color(FluidState state) {
            return colorAt(state, null, null);
        }

        @Override
        public int color(BlockState state) {
            return colorAt(state.getFluidState(), null, null);
        }

        @Override
        public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
            return colorAt(state.getFluidState(), level, pos);
        }

        // Never the host model's tint: this source is that tint, so asking it would ask itself.
        private int colorAt(FluidState state, BlockAndTintGetter level, BlockPos pos) {
            Object own = own(ext, TINT, AT, state, level, pos);
            if (!(own instanceof Integer)) own = own(ext, TINT, NONE);
            return own instanceof Integer tint ? tint : LegacyFluidModels.WHITE;
        }
    }
}
