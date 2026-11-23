/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.User;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;

/** Client members 1.20.1 mods call that later clients dropped or reshaped, each answered by what replaced it. */
public final class Legacy1201ClientMembers {

    private Legacy1201ClientMembers() {}

    /** {@code user.getGameProfile()}: the account's id and name, without the properties the session server adds. */
    public static GameProfile getGameProfile(User user) {
        return new GameProfile(user.getProfileId(), user.getName());
    }

    /** {@code minecraft.getProfiler()}: the profiler of the running thread, which replaced the client's own. */
    public static ProfilerFiller getProfiler(Minecraft minecraft) {
        return Profiler.get();
    }

    /** {@code minecraft.isConnectedToRealms()}: no client reports a Realms connection this way any more. */
    public static boolean isConnectedToRealms(Minecraft minecraft) {
        return false;
    }

    /** A read of {@code options.renderDebug}: whether the debug overlay shows, which the client's debug entries hold now. */
    public static boolean renderDebug(Options options) {
        return Minecraft.getInstance().debugEntries.isOverlayVisible();
    }

    /** A write of {@code options.renderDebug}: shows or hides the debug overlay. */
    public static void setRenderDebug(Options options, boolean visible) {
        Minecraft.getInstance().debugEntries.setOverlayVisible(visible);
    }

    /** {@code editBox.tick()}: the cursor blinks by time now, so there is nothing left to advance. */
    public static void tick(Object editBox) {
    }

    /** {@code sprite.getU(u)} from 1.20.1: {@code u} counted sixteenths of the sprite, now a fraction of it. */
    public static float getU(TextureAtlasSprite sprite, double u) {
        return sprite.getU((float) (u / 16.0));
    }

    /** {@code sprite.getV(v)} from 1.20.1, in sixteenths like {@link #getU}. */
    public static float getV(TextureAtlasSprite sprite, double v) {
        return sprite.getV((float) (v / 16.0));
    }

    /** {@code part.render(pose, consumer, light, overlay, red, green, blue, alpha)}: the color as one ARGB int. */
    public static void render(ModelPart part, PoseStack pose, VertexConsumer consumer, int light, int overlay,
            float red, float green, float blue, float alpha) {
        part.render(pose, consumer, light, overlay, argb(alpha, red, green, blue));
    }

    /** {@code screen.renderBackground(graphics)} from 1.20.1: the background, which no longer needs the mouse. */
    public static void renderBackground(Screen screen, GuiGraphicsExtractor graphics) {
        screen.extractBackground(graphics, 0, 0, 0.0F);
    }

    /**
     * {@code model.renderToBuffer(pose, consumer, light, overlay, red, green, blue, alpha)}: the old draw the model's class
     * declares runs; without one, the root is drawn with the color as one ARGB int.
     */
    public static void renderToBuffer(Model model, PoseStack pose, VertexConsumer consumer, int light, int overlay,
            float red, float green, float blue, float alpha) {
        // A super call from an old draw lands here too, so it continues above the class whose draw is running.
        IdentityHashMap<Object, Class<?>> active = ACTIVE_DRAWS.get();
        Class<?> running = active.get(model);
        Method own = oldDraw(running == null ? model.getClass() : running.getSuperclass());
        if (own == null) {
            model.renderToBuffer(pose, consumer, light, overlay, argb(alpha, red, green, blue));
            return;
        }
        active.put(model, own.getDeclaringClass());
        try {
            own.invoke(model, pose, consumer, light, overlay, red, green, blue, alpha);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not run the old draw of " + model.getClass().getName(), e);
        } finally {
            if (running == null) active.remove(model); else active.put(model, running);
        }
    }

    // The class of each model whose old draw this thread is running.
    private static final ThreadLocal<IdentityHashMap<Object, Class<?>>> ACTIVE_DRAWS = ThreadLocal.withInitial(IdentityHashMap::new);

    /** The old eight-argument draw declared by {@code type} or the nearest class above it, or null. */
    private static Method oldDraw(Class<?> type) {
        return type == null ? null : OLD_DRAWS.get(type);
    }

    // The old eight-argument draw a model class declares itself or inherits from a mod class, looked up once per class.
    private static final ClassValue<Method> OLD_DRAWS = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Method method = c.getDeclaredMethod("renderToBuffer", PoseStack.class, VertexConsumer.class, int.class, int.class,
                            float.class, float.class, float.class, float.class);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException next) {
                    // Keep looking in the parent.
                }
            }
            return null;
        }
    };

    private static int argb(float alpha, float red, float green, float blue) {
        return channel(alpha) << 24 | channel(red) << 16 | channel(green) << 8 | channel(blue);
    }

    private static int channel(float value) {
        return Math.round(Math.max(0.0F, Math.min(1.0F, value)) * 255.0F);
    }
}
