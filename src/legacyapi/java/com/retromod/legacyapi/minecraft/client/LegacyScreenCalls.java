/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;

/** Client calls content mods made on screens, model parts and text, rebuilt on 1.21.11 and 26.x. */
public final class LegacyScreenCalls {

    private LegacyScreenCalls() {}

    /** {@code screen.init(minecraft, width, height)}: the screen already knows the game. */
    public static void init(Screen screen, Minecraft minecraft, int width, int height) {
        screen.init(width, height);
    }

    public static boolean isCopy(int key) {
        return shortcut(key, Keys.C);
    }

    public static boolean isCut(int key) {
        return shortcut(key, Keys.X);
    }

    public static boolean isPaste(int key) {
        return shortcut(key, Keys.V);
    }

    public static boolean isSelectAll(int key) {
        return shortcut(key, Keys.A);
    }

    // Control (Command on macOS) and the letter, without shift or alt, as the old checks asked.
    private static boolean shortcut(int key, int letter) {
        if (key != letter) return false;
        Minecraft minecraft = Minecraft.getInstance();
        return editModifierDown(minecraft) && !minecraft.hasShiftDown() && !minecraft.hasAltDown();
    }

    // The host's own control check never looks at Command, which the old check used on macOS.
    private static boolean editModifierDown(Minecraft minecraft) {
        if (!InputQuirks.REPLACE_CTRL_KEY_WITH_CMD_KEY) return minecraft.hasControlDown();
        return Keys.down(minecraft, Keys.LEFT_SUPER) || Keys.down(minecraft, Keys.RIGHT_SUPER);
    }

    /** The host's numbers for the keys the old checks named, looked up once; 26.3 renumbered every key. */
    private static final class Keys {
        static final int C = code("c");
        static final int X = code("x");
        static final int V = code("v");
        static final int A = code("a");
        static final int LEFT_SUPER = code("left.win");
        static final int RIGHT_SUPER = code("right.win");
        // 26.3 asks for the key alone, older hosts for the window and the key.
        static final MethodHandle WINDOW_FORM = keyDown(MethodType.methodType(boolean.class, Window.class, int.class));
        static final MethodHandle KEY_FORM = WINDOW_FORM != null ? null : keyDown(MethodType.methodType(boolean.class, int.class));

        private static int code(String name) {
            return InputConstants.getKey("key.keyboard." + name).getValue();
        }

        private static MethodHandle keyDown(MethodType type) {
            try {
                return MethodHandles.publicLookup().findStatic(InputConstants.class, "isKeyDown", type);
            } catch (ReflectiveOperationException absent) {
                return null;
            }
        }

        static boolean down(Minecraft minecraft, int key) {
            try {
                if (WINDOW_FORM != null) return (boolean) WINDOW_FORM.invokeExact(minecraft.getWindow(), key);
                return KEY_FORM != null && (boolean) KEY_FORM.invokeExact(key);
            } catch (Throwable failed) {
                return false;
            }
        }
    }

    /** {@code part.copyFrom(other)}: position, rotation and scale, which the host's stored pose leaves out. */
    public static void copyFrom(ModelPart part, ModelPart other) {
        part.x = other.x;
        part.y = other.y;
        part.z = other.z;
        part.xRot = other.xRot;
        part.yRot = other.yRot;
        part.zRot = other.zRot;
        part.xScale = other.xScale;
        part.yScale = other.yScale;
        part.zScale = other.zScale;
    }

    /** {@code splitter.componentStyleAtWidth(text, width)}: the style of the character under that width, or null past the end. */
    public static Style componentStyleAtWidth(StringSplitter splitter, FormattedCharSequence text, int width) {
        float[] used = {0};
        Style[] found = {null};
        text.accept((index, style, codePoint) -> {
            used[0] += width(splitter, style, codePoint);
            if (used[0] > width) {
                found[0] = style;
                return false;
            }
            return true;
        });
        return found[0];
    }

    public static Style componentStyleAtWidth(StringSplitter splitter, FormattedText text, int width) {
        float[] used = {0};
        // Section-sign codes change the style and take no width, as the old walk through the decomposer did.
        Optional<Style> found = text.visit((style, content) -> {
            boolean fits = StringDecomposer.iterateFormatted(content, style, (index, formatted, codePoint) -> {
                used[0] += width(splitter, formatted, codePoint);
                return used[0] <= width;
            });
            return fits ? Optional.empty() : Optional.of(style);
        }, Style.EMPTY);
        return found.orElse(null);
    }

    // One character in its style, so bold text measures wider as it did.
    private static float width(StringSplitter splitter, Style style, int codePoint) {
        return splitter.stringWidth(FormattedText.of(new String(Character.toChars(codePoint)), style));
    }
}
