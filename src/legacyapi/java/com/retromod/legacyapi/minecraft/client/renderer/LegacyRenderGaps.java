/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Single render calls 1.21.x removed, each rebuilt on what replaced it. */
public final class LegacyRenderGaps {

    // Wood types old mods add sign textures for; 26.2 draws a sign's board from its block model, so nothing reads them.
    private static final Map<Object, Object> SIGNS = new ConcurrentHashMap<>();
    private static final Map<Object, Object> HANGING_SIGNS = new ConcurrentHashMap<>();
    // GL_FRAMEBUFFER_BINDING, the framebuffer draws currently go to.
    private static final int FRAMEBUFFER_BINDING = 0x8CA6;

    // One counter for every prefix, so two mods with the same prefix never share an id.
    private static final AtomicInteger DYNAMIC_IDS = new AtomicInteger();

    private LegacyRenderGaps() {}

    /** {@code transform.apply(leftHand, poseStack)}: 1.21.5 applies a transform to the stack's current pose. */
    public static void apply(ItemTransform transform, boolean leftHand, PoseStack poseStack) {
        transform.apply(leftHand, poseStack.last());
    }

    /** {@code levelRenderer.ticks}: the client level's game time, which also counts one per tick while the world runs. */
    public static int levelTicks(Object levelRenderer) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        return level == null ? 0 : (int) level.getLevelData().getGameTime();
    }

    /**
     * {@code GlStateManager.getBoundFramebuffer()}: asked of GL directly, as the old method did through its state manager.
     * Off the render thread or without a current GL context, such as on the Vulkan backend, the default framebuffer 0.
     */
    public static int getBoundFramebuffer() {
        if (!RenderSystem.isOnRenderThread() || GLFW.glfwGetCurrentContext() == 0L) return 0;
        return GL11.glGetInteger(FRAMEBUFFER_BINDING);
    }

    /** {@code Sheets.SIGN_SHEET}: the atlas id old sign textures named, which 26.2 no longer builds. */
    public static Identifier signSheet() {
        return Identifier.withDefaultNamespace("textures/atlas/signs.png");
    }

    /** {@code Sheets.SIGN_MATERIALS}: a map old code can still read and fill. */
    public static Map<Object, Object> signMaterials() {
        return SIGNS;
    }

    /** {@code Sheets.HANGING_SIGN_MATERIALS}: the same for hanging signs. */
    public static Map<Object, Object> hangingSignMaterials() {
        return HANGING_SIGNS;
    }

    /** {@code renderer.shouldRenderOffScreen(blockEntity)}: 1.21.6 asks the renderer without the block entity. */
    public static boolean shouldRenderOffScreen(Object renderer, Object blockEntity) {
        return ((BlockEntityRenderer<?, ?>) renderer).shouldRenderOffScreen();
    }

    /** The same from an override's super call, which reached the old default: never off screen. */
    public static boolean shouldRenderOffScreenDefault(Object renderer, Object blockEntity) {
        return false;
    }

    /**
     * {@code image.blendPixel(x, y, abgr)}: draws the color over the pixel with its alpha. The old call took the color in
     * ABGR order, while 1.21.2 pixels read and write ARGB.
     */
    public static void blendPixel(NativeImage image, int x, int y, int abgr) {
        int sourceAlpha = abgr >>> 24;
        int argb = (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
        int target = image.getPixel(x, y);
        int outAlpha = sourceAlpha + (target >>> 24) * (255 - sourceAlpha) / 255;
        int out = outAlpha << 24;
        // Each color channel moves from the old pixel toward the new one by the new color's alpha.
        for (int shift = 0; shift <= 16; shift += 8) {
            int source = (argb >> shift) & 0xFF;
            int old = (target >> shift) & 0xFF;
            out |= ((source * sourceAlpha + old * (255 - sourceAlpha)) / 255) << shift;
        }
        image.setPixel(x, y, out);
    }

    /** {@code new PostChain(textures, resources, target, id)}: none, as 1.21.2 builds post chains only from its own effect files. */
    public static PostChain postChain(Object textures, Object resources, Object target, Object id) {
        return null;
    }

    /** {@code gameRenderer.currentEffect()}: none of the old kind, so callers take their no-effect path. */
    public static PostChain currentEffect(Object gameRenderer) {
        return null;
    }

    /** {@code chain.getName()}: an empty name, as a chain of the old kind is never built. */
    public static String postChainName(Object chain) {
        return "";
    }

    /** {@code textures.register(prefix, texture)}: registers it under a new {@code minecraft:dynamic/<prefix>_<n>} id and returns that id. */
    public static Identifier register(TextureManager textures, String prefix, DynamicTexture texture) {
        Identifier id = Identifier.withDefaultNamespace("dynamic/" + prefix + "_" + DYNAMIC_IDS.incrementAndGet());
        textures.register(id, texture);
        return id;
    }
}
