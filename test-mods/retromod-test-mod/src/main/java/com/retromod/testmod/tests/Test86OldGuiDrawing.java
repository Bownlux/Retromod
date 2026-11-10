/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/** Old GUI drawing, list rows, drawables, buttons and key checks, run on the 26.x GUI. */
public final class Test86OldGuiDrawing implements Test {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Test");
    private static boolean drawn;

    @Override
    public String description() {
        return "old GUI drawing, list rows, drawables, buttons and key checks";
    }

    @Override
    public TestResult run() {
        try {
            // A 26.x game calls these methods instead of the old ones the classes declare.
            if (!declares(OldRow.class, "extractContent", 5)) return TestResult.fail("the old list row has no extractContent");
            if (!declares(OldBadge.class, "extractRenderState", 4)) return TestResult.fail("the old drawable has no extractRenderState");
            OldToggle toggle = new OldToggle();
            Method press = newPress(OldToggle.class);
            if (press == null) return TestResult.fail("the old button has no 26.x press");
            press.invoke(toggle, Class.forName("net.minecraft.client.input.MouseButtonInfo")
                    .getConstructor(int.class, int.class).newInstance(0, 0));
            if (toggle.presses != 1) return TestResult.fail("the old onPress ran " + toggle.presses + " times");
            KeyBinding binding = new KeyBinding("key.retromod_test.test86", 74, "key.categories.misc");
            binding.matchesKey(74, 0);
            binding.matchesMouse(0);
            if (!"key.categories.misc".equals(binding.getCategory())) {
                return TestResult.fail("getCategory read " + binding.getCategory());
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return TestResult.fail(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
        // The drawing itself needs a frame, so it reports in the log.
        HudRenderCallback.EVENT.register(Test86OldGuiDrawing::draw);
        return TestResult.success();
    }

    // Nearly transparent, so the HUD looks the same while every old call still runs.
    private static void draw(DrawContext context, float tickDelta) {
        if (drawn || context == null) return;
        drawn = true;
        try {
            MatrixStack pose = context.getMatrices();
            pose.push();
            shift(pose);
            context.fill(RenderLayer.getGui(), 0, 0, 1, 1, 0x01000000);
            context.drawHorizontalLine(0, 1, 0, 0x01000000);
            context.drawVerticalLine(0, 0, 1, 0x01000000);
            context.fillGradient(RenderLayer.getGui(), 0, 0, 1, 1, 0x01000000, 0x01000000, 0);
            pose.pop();
            LOGGER.info("[Retromod-Test] Test86: the old HUD drawing ran on the 26.x GUI");
        } catch (Throwable t) {
            LOGGER.error("[Retromod-Test] Test86 FAILED: the old HUD drawing threw {}", t.toString());
        }
    }

    // A pose stack passed to another method reaches the rebuilt one, not the 2D stack directly.
    private static void shift(MatrixStack pose) {
        pose.translate(1, 1, 0);
    }

    private static boolean declares(Class<?> owner, String name, int parameters) {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return true;
        }
        return false;
    }

    private static Method newPress(Class<?> owner) {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals("onPress") && method.getParameterCount() == 1) return method;
        }
        return null;
    }

    static final class OldRow extends AlwaysSelectedEntryListWidget.Entry<OldRow> {
        @Override
        public Text getNarration() {
            return Text.literal("row");
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY,
                boolean hovered, float tickDelta) {
            context.fill(x, y, x + entryWidth, y + entryHeight, 0x01000000);
        }
    }

    static final class OldBadge implements Drawable {
        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, 1, 1, 0x01000000);
        }
    }

    static final class OldToggle extends PressableWidget {
        int presses;

        OldToggle() {
            super(0, 0, 20, 20, Text.literal("t"));
        }

        @Override
        public void onPress() {
            presses++;
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        }
    }
}
