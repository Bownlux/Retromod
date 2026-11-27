/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** The renderer context and layer parts 1.21.x mods built from the old renderers, made from what the running game holds. */
public final class LegacyRendererContexts {

    private LegacyRendererContexts() {}

    /**
     * {@code new EntityRendererProvider.Context(dispatcher, itemRenderer, blockRenderer, itemInHand, resources, models, font)}:
     * the host's context. Each part comes from the old arguments when their type still fits, then from the game.
     */
    public static EntityRendererProvider.Context create(Object dispatcher, Object items, Object blocks, Object inHand,
            Object resources, Object models, Object font) {
        Constructor<?> constructor = widestConstructor(EntityRendererProvider.Context.class);
        Object[] given = {dispatcher, resources, models, font};
        Class<?>[] types = constructor.getParameterTypes();
        Object[] arguments = new Object[types.length];
        for (int i = 0; i < types.length; i++) arguments[i] = part(types[i], given);
        try {
            return (EntityRendererProvider.Context) constructor.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build an entity renderer context for an old mod; "
                    + "the renderer it was making is not available", e);
        }
    }

    /** The skin cache a custom head layer now takes where it used to take the item-in-hand renderer. */
    public static PlayerSkinRenderCache skinCache() {
        return Minecraft.getInstance().playerSkinRenderCache();
    }

    private static Constructor<?> widestConstructor(Class<?> type) {
        Constructor<?> widest = null;
        for (Constructor<?> constructor : type.getConstructors()) {
            if (widest == null || constructor.getParameterCount() > widest.getParameterCount()) widest = constructor;
        }
        if (widest == null) throw new IllegalStateException("The host's entity renderer context has no public constructor");
        return widest;
    }

    // An old argument of the right type first, then a getter on Minecraft, then the entity render dispatcher's own part.
    private static Object part(Class<?> type, Object[] given) {
        for (Object value : given) {
            if (type.isInstance(value)) return value;
        }
        Minecraft minecraft = Minecraft.getInstance();
        for (Method method : Minecraft.class.getMethods()) {
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers()) || method.getReturnType() != type) continue;
            try {
                return method.invoke(minecraft);
            } catch (ReflectiveOperationException e) {
                return missing(type, "Minecraft." + method.getName() + "() failed: " + e);
            }
        }
        Object dispatcher = minecraft.getEntityRenderDispatcher();
        for (Field field : dispatcher.getClass().getDeclaredFields()) {
            if (field.getType() != type || Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                return field.get(dispatcher);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return missing(type, "the entity render dispatcher's field could not be read: " + e);
            }
        }
        return missing(type, "neither the old arguments nor the game hold one");
    }

    // The context is built without the part, and the renderer that needs it may fail when it reaches for it.
    private static Object missing(Class<?> type, String reason) {
        LegacyLog.warnOnce("context-part:" + type.getName(), "An old mod built an entity renderer context, but Retromod "
                + "found no " + type.getName() + " for it (" + reason + "). The renderer it makes may not draw.");
        return null;
    }
}
