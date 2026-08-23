/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.core.RetromodConfig;
import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * In-game "mods converted - restart to apply" prompt (issue #33).
 *
 * <p>Retromod transforms old mods into {@code mods/} during pre-launch, but the
 * loader already scanned {@code mods/} by then, so the freshly-converted mods
 * only load on the <em>next</em> launch. Previously that was communicated with a
 * log line / Swing popup before the window opened; this shows an in-game
 * confirmation on the title screen instead, with a button to close the game so
 * the user can relaunch.
 *
 * <p>Loader-agnostic: each loader's pre-launch/constructor path calls
 * {@link #markPending(int)} with the number of mods it converted; the title
 * screen injector calls {@link #maybeShow(Object)} once the title screen opens.
 *
 * <p>Gated by {@code restart_prompt} in {@code config/retromod/config.json}
 * (default on). Set it to {@code false} to suppress the prompt.
 *
 * <p>On confirm we cleanly stop the client (the user relaunches from their
 * launcher). True in-process relaunch isn't attempted; it can't be done
 * reliably across launchers (Prism/MultiMC/vanilla each wrap the launch), and a
 * half-working relaunch is worse than a clean stop.
 */
public final class RestartPrompt {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Restart");

    private static volatile int pendingCount = 0;
    private static volatile boolean shownThisSession = false;

    private RestartPrompt() {}

    /** Record that {@code count} mods were converted this launch and need a restart. */
    public static void markPending(int count) {
        if (count > 0) pendingCount = count;
    }

    /** Whether the prompt should be shown now (pending, enabled, not yet shown). */
    static boolean shouldShow() {
        return pendingCount > 0 && !shownThisSession && configEnabled();
    }

    /**
     * Show the restart prompt once, if appropriate. Called from the title-screen
     * injector with the live title-screen object (used as the parent to return to
     * if the user declines).
     */
    public static void maybeShow(Object titleScreen) {
        if (!shouldShow()) return;
        shownThisSession = true; // once per session, even if the show below no-ops
        try {
            int n = pendingCount;
            String msg = "Retromod converted " + n + " mod" + (n == 1 ? "" : "s")
                    + " to work with this Minecraft version.\n"
                    + "The game needs to restart to load "
                    + (n == 1 ? "it" : "them") + ".\n\n"
                    + "Click Yes to close Minecraft, then relaunch from your launcher.";
            InGameScreenFactory.showRestartConfirm(titleScreen, "Retromod", msg, RestartPrompt::stopClient);
            LOGGER.info("Showed in-game restart prompt for {} converted mod(s)", n);
        } catch (Throwable t) {
            LOGGER.warn("Could not show restart prompt: {}", t.getMessage());
        }
    }

    /** Cleanly stop the Minecraft client so the user can relaunch. */
    private static void stopClient() {
        try {
            Class<?> mcClass = McReflect.findClass(
                    "net.minecraft.client.MinecraftClient",  // yarn
                    "net.minecraft.client.Minecraft");       // mojang
            if (mcClass == null) return;
            Method getInstance = McReflect.findMethod(mcClass, "getInstance");
            Object client = getInstance != null ? getInstance.invoke(null) : null;
            if (client == null) return;
            // Prefer scheduleStop() (clean shutdown on the main thread); fall back to stop().
            Method stop = McReflect.findMethod(mcClass, "scheduleStop", "stop", "close");
            if (stop != null) {
                LOGGER.info("Stopping client to apply converted mods (user will relaunch)");
                stop.invoke(client);
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not stop client for restart: {}", t.getMessage());
        }
    }

    /** Read {@code restart_prompt} from config (default {@code true} when absent). */
    static boolean configEnabled() {
        return RetromodConfig.getBooleanIfPresent("restart_prompt", true);
    }

    static void resetForTesting() { pendingCount = 0; shownThisSession = false; }
    static void markShownForTesting() { shownThisSession = true; }
    static int pendingForTesting() { return pendingCount; }
}
