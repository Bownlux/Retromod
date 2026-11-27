/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.gui.screens;

import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Screen members later versions removed: component clicks in 1.21.11 and the error wrapper in 1.21.2. */
public abstract class LegacyScreenMembers extends Screen {

    // Never built: extending Screen is what lets this class call its protected static click handlers.
    private LegacyScreenMembers() {
        super((Component) null);
    }

    /** Runs the style's click event the way the screen would, and says whether it had one. */
    public static boolean handleComponentClicked(Screen screen, Style style) {
        if (style == null) return false;
        ClickEvent click = style.getClickEvent();
        Minecraft minecraft = Minecraft.getInstance();
        // A shift click only inserted text into chat, which the chat screen still does itself.
        if (click == null || minecraft.hasShiftDown()) return false;
        if (needsPlayer(click)) {
            // Commands, dialogs and custom actions go to the server, so they need a connected player.
            if (minecraft.player == null) return false;
            defaultHandleGameClickEvent(click, minecraft, screen);
        } else if (!(click instanceof ClickEvent.ChangePage)) {
            // Page turns belong to the book screen that shows them.
            defaultHandleClickEvent(click, minecraft, screen);
        }
        return true;
    }

    private static boolean needsPlayer(ClickEvent click) {
        return click instanceof ClickEvent.RunCommand || click instanceof ClickEvent.ShowDialog
                || click instanceof ClickEvent.Custom;
    }

    /** {@code Screen.wrapScreenError(task, description, screenName)}: runs the task, and reports a failure as a crash naming the screen. */
    public static void wrapScreenError(Runnable task, String description, String screenName) {
        try {
            task.run();
        } catch (Throwable failure) {
            CrashReport report = CrashReport.forThrowable(failure, description);
            report.addCategory("Affected screen").setDetail("Screen name", screenName);
            throw new ReportedException(report);
        }
    }
}
