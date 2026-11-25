/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.LegacyPoseGui;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Screen and widget calls that mods for 1.19.4 and older made with a pose stack, run through the frame's extractor on 26.1 and newer. */
public final class LegacyPoseScreens {

    // Screen classes whose old render failed once; they draw through the host's own extract from then on.
    private static final Set<Class<?>> FAILED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private LegacyPoseScreens() {}

    /** Whether the screen's old render still runs, which stops for its class after it failed once. */
    public static boolean runsOldRender(Object screen) {
        return !FAILED.contains(screen.getClass());
    }

    /** An old render that reached something the host lacks: its class falls back to the host's extract, said once. */
    public static void oldRenderFailed(Object screen, Throwable failure) {
        if (FAILED.add(screen.getClass())) {
            LegacyLog.warn("The old screen " + screen.getClass().getName() + " could not draw itself (" + failure
                    + "), so it draws only its widgets from now on.");
        }
    }

    /** A pose stack for one frame of an old screen's own {@code render(pose, ...)}, drawing through the extractor. */
    public static PoseStack poseFor(GuiGraphicsExtractor graphics) {
        return (PoseStack) LegacyPoseGui.poseForGraphics(graphics);
    }

    /** The extractor behind an old pose stack with the pose stack's transform pushed, or null for a pose stack from elsewhere. */
    public static GuiGraphicsExtractor beginDraw(PoseStack pose) {
        return (GuiGraphicsExtractor) LegacyPoseGui.beginDraw(pose);
    }

    /** The extractor behind an old pose stack, or null for a pose stack from elsewhere. */
    public static GuiGraphicsExtractor graphicsOf(PoseStack pose) {
        return (GuiGraphicsExtractor) LegacyPoseGui.graphicsOf(pose);
    }

    /** Pops the transform {@link #beginDraw} pushed. */
    public static void endDraw(GuiGraphicsExtractor graphics) {
        LegacyPoseGui.endDraw(graphics);
    }

    /** {@code widget.render(pose, mouseX, mouseY, partialTick)}: the widget's own extract, under the pose stack's transform. */
    public static void render(Renderable widget, PoseStack pose, int mouseX, int mouseY, float partialTick) {
        GuiGraphicsExtractor graphics = beginDraw(pose);
        // A pose stack the mod made itself has no frame to draw into.
        if (graphics == null) return;
        try {
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        } finally {
            endDraw(graphics);
        }
    }

    /** {@code new Button(x, y, width, height, message, onPress)}, which 26.1 replaced with the builder. */
    public static Button button(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
        return Button.builder(message, onPress).bounds(x, y, width, height).build();
    }

    /** {@code screen.renderBackground(pose)}: the host draws a screen's background before the screen, so this draws nothing more. */
    public static void renderBackground(Object screen, PoseStack pose) {
    }

    /** {@code screen.renderBackground(pose, offset)}, the same. */
    public static void renderBackground(Object screen, PoseStack pose, int offset) {
    }

    /** {@code screen.renderDirtBackground(offset)}, the same. */
    public static void renderDirtBackground(Object screen, int offset) {
    }

    /** {@code screen.renderTooltip(pose, text, x, y)}: shown when the frame ends, as the host shows tooltips. */
    public static void renderTooltip(Object screen, PoseStack pose, Component text, int x, int y) {
        GuiGraphicsExtractor graphics = graphicsOf(pose);
        if (graphics != null) graphics.setTooltipForNextFrame(text, x, y);
    }

    /** {@code screen.renderTooltip(pose, lines, x, y)} with lines already split for width. */
    @SuppressWarnings("unchecked")
    public static void renderTooltip(Object screen, PoseStack pose, List<? extends FormattedCharSequence> lines, int x, int y) {
        GuiGraphicsExtractor graphics = graphicsOf(pose);
        if (graphics != null) graphics.setTooltipForNextFrame((List<FormattedCharSequence>) lines, x, y);
    }

    /** {@code screen.renderComponentTooltip(pose, lines, x, y)}, which splits nothing. */
    public static void renderComponentTooltip(Object screen, PoseStack pose, List<Component> lines, int x, int y) {
        GuiGraphicsExtractor graphics = graphicsOf(pose);
        if (graphics != null) graphics.setComponentTooltipForNextFrame(Minecraft.getInstance().font, lines, x, y);
    }
}
