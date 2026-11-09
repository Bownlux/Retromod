/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Method;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.LoggerFactory;

/** An old screen's GUI drawing, list row, renderable, button and key checks on the 26.x GUI. Client only. */
final class OldGuiDrawCase {

    private static final String SHAPES = "Old list rows, renderables, buttons and key checks answer the 26.x GUI (first client tick)";
    private static final String DRAW = "Old GUI drawing with a pose stack, render type fills and lines runs on the 26.x GUI (first HUD frame)";
    private static boolean checked;
    private static boolean drawn;

    private OldGuiDrawCase() {}

    static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(OldGuiDrawCase::onClientTick);
            NeoForge.EVENT_BUS.addListener(OldGuiDrawCase::onHud);
        } catch (Throwable t) {
            log("Old GUI checks register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (checked || Minecraft.getInstance().getWindow() == null) return;
        checked = true;
        log(SHAPES, shapes());
    }

    private static String shapes() {
        try {
            if (!declares(OldRow.class, "extractContent", 5)) return "fail: the old list row has no extractContent";
            if (!declares(OldBadge.class, "extractRenderState", 4)) return "fail: the old renderable has no extractRenderState";
            OldToggle toggle = new OldToggle();
            Method press = null;
            for (Method method : OldToggle.class.getDeclaredMethods()) {
                if (method.getName().equals("onPress") && method.getParameterCount() == 1) press = method;
            }
            if (press == null) return "fail: the old button has no 26.x press";
            press.invoke(toggle, Class.forName("net.minecraft.client.input.MouseButtonInfo")
                    .getConstructor(int.class, int.class).newInstance(0, 0));
            if (toggle.presses != 1) return "fail: the old onPress ran " + toggle.presses + " times";
            KeyMapping key = new KeyMapping("key.retromod_test_mod_neoforge.gui", 74, "key.categories.misc");
            key.matches(74, 0);
            key.matchesMouse(0);
            return "key.categories.misc".equals(key.getCategory()) ? "success" : "fail: getCategory read " + key.getCategory();
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "fail: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    // Nearly transparent, so the HUD looks the same while every old call still runs.
    private static void onHud(RenderGuiEvent.Post event) {
        if (drawn) return;
        drawn = true;
        try {
            GuiGraphics graphics = event.getGuiGraphics();
            PoseStack pose = graphics.pose();
            pose.pushPose();
            shift(pose);
            graphics.fill(RenderType.gui(), 0, 0, 1, 1, 0x01000000);
            graphics.hLine(0, 1, 0, 0x01000000);
            graphics.vLine(0, 0, 1, 0x01000000);
            graphics.fillGradient(0, 0, 1, 1, 0, 0x01000000, 0x01000000);
            pose.popPose();
            log(DRAW, "success");
        } catch (Throwable t) {
            log(DRAW, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // A pose stack passed to another method reaches the rebuilt one, not the 2D stack directly.
    private static void shift(PoseStack pose) {
        pose.translate(1, 1, 0);
    }

    private static boolean declares(Class<?> owner, String name, int parameters) {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return true;
        }
        return false;
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-NeoForge").info("[Retromod-Test-NeoForge] {}: {}", what, result);
    }

    static final class OldRow extends ObjectSelectionList.Entry<OldRow> {
        @Override
        public Component getNarration() {
            return Component.literal("row");
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                boolean hovering, float partialTick) {
            graphics.fill(left, top, left + width, top + height, 0x01000000);
        }
    }

    static final class OldBadge implements Renderable {
        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, 1, 1, 0x01000000);
        }
    }

    static final class OldToggle extends AbstractButton {
        int presses;

        OldToggle() {
            super(0, 0, 20, 20, Component.literal("t"));
        }

        @Override
        public void onPress() {
            presses++;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }
}
