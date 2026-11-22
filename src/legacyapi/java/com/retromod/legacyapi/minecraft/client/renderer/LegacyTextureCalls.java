/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.texture.AbstractTexture;

/** The AbstractTexture calls 1.21.5 and 1.21.11 removed: the GL texture id and the blur and mipmap filter. */
public final class LegacyTextureCalls {

    private LegacyTextureCalls() {}

    /** {@code setFilter(blur, mipmap)}: the texture takes the sampler with those filters and its old address modes. */
    public static void setFilter(AbstractTexture texture, boolean blur, boolean mipmap) {
        try {
            GpuSampler current = texture.getSampler();
            // The sampler is an interface on 26.3 and a class before, so its getters are reached by name.
            AddressMode u = current == null ? AddressMode.CLAMP_TO_EDGE : (AddressMode) call(current, "getAddressModeU");
            AddressMode v = current == null ? AddressMode.CLAMP_TO_EDGE : (AddressMode) call(current, "getAddressModeV");
            FilterMode filter = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
            GpuSampler sampler = RenderSystem.getSamplerCache().getSampler(u, v, filter, filter, mipmap);
            // The field is protected and has no setter, so it is written by reflection.
            Field field = AbstractTexture.class.getDeclaredField("sampler");
            field.setAccessible(true);
            field.set(texture, sampler);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LegacyLog.warnOnce("texture-set-filter", "An old mod changed a texture's filter, which Retromod could not apply on"
                    + " this Minecraft version, so the texture keeps its own filter: " + failure);
        }
    }

    /** {@code getId()}: the OpenGL name of the texture, or 0 when it has none, as on another graphics API. */
    public static int getId(AbstractTexture texture) {
        Object gpuTexture = texture.getTexture();
        if (gpuTexture == null) return 0;
        try {
            return (Integer) call(gpuTexture, "glId");
        } catch (ReflectiveOperationException | RuntimeException none) {
            return 0;
        }
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
