/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.retromod.legacyapi.minecraft.world.LegacyVanillaGaps;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Client members 1.16.5 to 1.21.1 mods call that 26.x removed or reshaped, each answered by what replaced it. */
public final class LegacyVanillaClientGaps {

    // Entities an old mod marked as never culled, which 26.x decides in the renderer instead.
    private static final Map<Entity, Boolean> NO_CULLING = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Field recipeBookField;

    private LegacyVanillaClientGaps() {}

    /** 1.16.5's static {@code Minecraft.fillReport(languages, version, options, report)}, for the running client. */
    public static void fillReport(LanguageManager languages, String version, Options options, CrashReport report) {
        Minecraft.fillReport(Minecraft.getInstance(), languages, version, options, report);
    }

    /** {@code gameMode.getPickRange()}: the player's block reach, which 1.20.5 made an attribute. */
    public static float getPickRange(MultiPlayerGameMode gameMode) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? 4.5F : (float) player.blockInteractionRange();
    }

    /** {@code renderer.getRenderOffset(entity, partialTick)}: asked of a render state built for the entity. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Vec3 getRenderOffset(EntityRenderer renderer, Entity entity, float partialTick) {
        return renderer.getRenderOffset(renderer.createRenderState(entity, partialTick));
    }

    /** Reads the old {@code Entity.noCulling} flag: set by the mod, or the entity's renderer never culls it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean noCulling(Object entity) {
        Entity value = (Entity) entity;
        if (NO_CULLING.containsKey(value)) return true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getEntityRenderDispatcher() == null) return false;
        try {
            EntityRenderer renderer = minecraft.getEntityRenderDispatcher().getRenderer(value);
            return renderer != null && !renderer.affectedByCulling(value);
        } catch (RuntimeException noRenderer) {
            // An entity type with no renderer yet is culled like any other.
            return false;
        }
    }

    /** A write of the old flag, remembered for later reads; the game's own culling is decided by the renderer. */
    public static void setNoCulling(Object entity, boolean value) {
        if (value) NO_CULLING.put((Entity) entity, Boolean.TRUE);
        else NO_CULLING.remove((Entity) entity);
    }

    /** {@code cube.compile(pose, consumer, light, overlay, r, g, b, a)}: the color packed as 1.21 takes it. */
    public static void compile(ModelPart.Cube cube, PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay,
            float red, float green, float blue, float alpha) {
        cube.compile(pose, consumer, light, overlay, argb(alpha, red, green, blue));
    }

    private static int argb(float alpha, float red, float green, float blue) {
        return channel(alpha) << 24 | channel(red) << 16 | channel(green) << 8 | channel(blue);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
    }

    /** {@code new VideoSettingsScreen(parent, options)}: the client the screen takes now is the running one. */
    public static VideoSettingsScreen videoSettings(Screen parent, Options options) {
        return new VideoSettingsScreen(parent, Minecraft.getInstance(), options);
    }

    /** {@code player.getFieldOfViewModifier()}: asked for the current camera and field of view effect setting. */
    public static float getFieldOfViewModifier(AbstractClientPlayer player) {
        Options options = Minecraft.getInstance().options;
        return player.getFieldOfViewModifier(options.getCameraType().isFirstPerson(), options.fovEffectScale().get().floatValue());
    }

    /** {@code pinger.pingServer(data, onPersistent, onResponse)}: through the network threads the client's own pings use. */
    public static void pingServer(ServerStatusPinger pinger, ServerData data, Runnable onPersistent, Runnable onResponse)
            throws java.net.UnknownHostException {
        boolean nativeTransport = Minecraft.getInstance().options.useNativeTransport();
        pinger.pingServer(data, onPersistent, onResponse, EventLoopGroupHolder.remote(nativeTransport));
    }

    /** {@code sprite.getName()}: the sprite's name, which 1.19.3 moved to its contents. */
    public static Identifier getName(TextureAtlasSprite sprite) {
        return sprite.contents().name();
    }

    /** {@code listener.getRecipeBookComponent()}: the recipe book of a recipe screen, read from its private field. */
    public static RecipeBookComponent getRecipeBookComponent(Object listener) {
        if (!(listener instanceof AbstractRecipeBookScreen<?> screen)) return null;
        try {
            Field field = recipeBookField;
            if (field == null) {
                field = AbstractRecipeBookScreen.class.getDeclaredField("recipeBookComponent");
                field.setAccessible(true);
                recipeBookField = field;
            }
            return (RecipeBookComponent) field.get(screen);
        } catch (ReflectiveOperationException | RuntimeException closed) {
            return null;
        }
    }

    /** {@code sprite.getWidth()}: the width its contents hold since 1.19.3. */
    public static int getWidth(TextureAtlasSprite sprite) {
        return sprite.contents().width();
    }

    /** {@code sprite.getHeight()}. */
    public static int getHeight(TextureAtlasSprite sprite) {
        return sprite.contents().height();
    }

    /** {@code languages.getSelected()} of 1.19.3 and older: the selected language's info, which 1.19.4 keys by its code. */
    public static LanguageInfo getSelected(LanguageManager languages) {
        return languages.getLanguage(languages.getSelected());
    }

    /** {@code info.getCode()}: the code the language manager keys this language by. */
    public static String getCode(LanguageInfo info) {
        for (var entry : Minecraft.getInstance().getLanguageManager().getLanguages().entrySet()) {
            if (entry.getValue().equals(info)) return entry.getKey();
        }
        return "en_us";
    }

    /** {@code level.disconnect()}: with the reason the game gives when a player quits. */
    public static void disconnect(ClientLevel level) {
        level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
    }

    /** {@code window.isFullscreen()}: the fullscreen setting, which 26.3 applies to the window itself. */
    public static boolean isFullscreen(Object window) {
        return Minecraft.getInstance().options.fullscreen().get();
    }

    /** {@code connection.send(packet)} in a class that builds attacks: an attack is sent as the packet 26.1 split off. */
    public static void send(Object connection, Object packet) {
        Packet<?> sent = (Packet<?>) LegacyVanillaGaps.attackOrSame(packet);
        // The connection comes first: a dedicated server has no client listener class to test against.
        if (connection instanceof Connection direct) {
            direct.send(sent);
        } else {
            ((ClientCommonPacketListenerImpl) connection).send(sent);
        }
    }

    /** {@code graphics.blitNineSliced(texture, x, y, width, height, sliceWidth, sliceHeight, sourceWidth, sourceHeight, u, v)}. */
    public static void blitNineSliced(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int width, int height,
            int sliceWidth, int sliceHeight, int sourceWidth, int sourceHeight, int u, int v) {
        blitNineSliced(graphics, texture, x, y, width, height, sliceWidth, sliceHeight, sliceWidth, sliceHeight, sourceWidth,
                sourceHeight, u, v);
    }

    /** The same draw with one slice size for every edge. */
    public static void blitNineSliced(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int width, int height,
            int slice, int sourceWidth, int sourceHeight, int u, int v) {
        blitNineSliced(graphics, texture, x, y, width, height, slice, slice, slice, slice, sourceWidth, sourceHeight, u, v);
    }

    /** 1.20.1's nine-slice draw from a 256 by 256 texture: fixed corners, repeated edges and middle (left, top, right, bottom). */
    public static void blitNineSliced(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int width, int height,
            int left, int top, int right, int bottom, int sourceWidth, int sourceHeight, int u, int v) {
        // A slice may not be wider than half the drawn or source area, as the old draw clamped it.
        left = Math.min(left, Math.min(width, sourceWidth) / 2);
        right = Math.min(right, Math.min(width, sourceWidth) / 2);
        top = Math.min(top, Math.min(height, sourceHeight) / 2);
        bottom = Math.min(bottom, Math.min(height, sourceHeight) / 2);
        int middleWidth = width - left - right;
        int middleHeight = height - top - bottom;
        int sourceMiddleWidth = sourceWidth - left - right;
        int sourceMiddleHeight = sourceHeight - top - bottom;
        // The four corners.
        part(graphics, texture, x, y, left, top, u, v, left, top);
        part(graphics, texture, x + width - right, y, right, top, u + sourceWidth - right, v, right, top);
        part(graphics, texture, x, y + height - bottom, left, bottom, u, v + sourceHeight - bottom, left, bottom);
        part(graphics, texture, x + width - right, y + height - bottom, right, bottom, u + sourceWidth - right,
                v + sourceHeight - bottom, right, bottom);
        // The edges and the middle repeat their source region.
        tile(graphics, texture, x + left, y, middleWidth, top, u + left, v, sourceMiddleWidth, top);
        tile(graphics, texture, x + left, y + height - bottom, middleWidth, bottom, u + left, v + sourceHeight - bottom,
                sourceMiddleWidth, bottom);
        tile(graphics, texture, x, y + top, left, middleHeight, u, v + top, left, sourceMiddleHeight);
        tile(graphics, texture, x + width - right, y + top, right, middleHeight, u + sourceWidth - right, v + top, right,
                sourceMiddleHeight);
        tile(graphics, texture, x + left, y + top, middleWidth, middleHeight, u + left, v + top, sourceMiddleWidth,
                sourceMiddleHeight);
    }

    // Repeats a source region over the area, cutting the last copy in each direction.
    private static void tile(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int width, int height, int u, int v,
            int sourceWidth, int sourceHeight) {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return;
        for (int dx = 0; dx < width; dx += sourceWidth) {
            for (int dy = 0; dy < height; dy += sourceHeight) {
                int w = Math.min(sourceWidth, width - dx);
                int h = Math.min(sourceHeight, height - dy);
                part(graphics, texture, x + dx, y + dy, w, h, u, v, w, h);
            }
        }
    }

    private static void part(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int width, int height, int u, int v,
            int sourceWidth, int sourceHeight) {
        if (width <= 0 || height <= 0) return;
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, sourceWidth, sourceHeight, 256, 256);
    }
}
