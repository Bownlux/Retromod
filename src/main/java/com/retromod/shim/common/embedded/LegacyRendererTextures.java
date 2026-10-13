/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Method;

/**
 * The texture of an old living entity renderer for 26.1's {@code getTextureLocation(state)}. The
 * old method took the entity, which 26.1 no longer hands a renderer; mods that pick one texture,
 * as MCreator's do, answer without reading it, so the old method is asked with none. A renderer
 * whose texture depends on the entity gets the missing texture. Reflective, because this class is
 * embedded into each mod and compiled without Minecraft.
 */
public final class LegacyRendererTextures {
    private LegacyRendererTextures() {}

    public static Object texture(Object renderer) {
        for (Class<?> c = renderer.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().startsWith("net.minecraft.")) break;
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals("getTextureLocation") && method.getParameterCount() == 1
                        && !method.getParameterTypes()[0].getName().contains("RenderState") && !method.isBridge()) {
                    try {
                        method.setAccessible(true);
                        Object texture = method.invoke(renderer, (Object) null);
                        if (texture != null) return texture;
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        // the texture depends on the entity
                    }
                }
            }
        }
        try {
            return Class.forName("net.minecraft.client.renderer.texture.MissingTextureAtlasSprite", true,
                    renderer.getClass().getClassLoader()).getMethod("getLocation").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not find a texture for " + renderer.getClass().getName(), e);
        }
    }
}
