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

/** Shows the in-game "mods converted, restart to apply" prompt on the title screen (#33). */
public final class RestartPrompt {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Restart");

    // Retromod converts old mods during pre-launch, after the loader already scanned mods/, so
    // they only load on the next launch. Each loader's pre-launch or constructor path records the
    // count here, and the title screen injector shows the prompt once the title screen opens.
    private static volatile int pendingCount = 0;
    // Forge and NeoForge in-place mode translates into Retromod's cache and leaves mods/ untouched.
    private static volatile boolean translatedIntoCache = false;
    private static volatile boolean shownThisSession = false;

    private RestartPrompt() {}

    /** Record that {@code count} mods were converted this launch and need a restart. */
    public static void markPending(int count) {
        if (count > 0) {
            pendingCount = count;
            translatedIntoCache = false;
        }
    }

    /** Record that {@code count} mods were translated into Retromod's cache this launch. */
    public static void markTranslatedIntoCache(int count) {
        // No file in mods/ changed in that mode, so the prompt must not say it did.
        if (count > 0) {
            pendingCount = count;
            translatedIntoCache = true;
        }
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
            String msg = message(n, translatedIntoCache);
            InGameScreenFactory.showRestartConfirm(titleScreen, "Retromod", msg, RestartPrompt::stopClient);
            LOGGER.info("Showed in-game restart prompt for {} converted mod(s)", n);
        } catch (Throwable t) {
            LOGGER.warn("Could not show restart prompt: {}", t.getMessage());
        }
    }

    /** The prompt text for {@code n} mods. */
    static String message(int n, boolean intoCache) {
        String mods = n + " mod" + (n == 1 ? "" : "s");
        String them = n == 1 ? "it" : "them";
        if (intoCache) {
            return "Retromod translated " + mods + " for this Minecraft version.\n"
                    + "The game needs to restart to load " + them + ".\n"
                    + "Your files in mods/ were not changed.\n\n"
                    + "Click Yes to close Minecraft, then relaunch from your launcher.";
        }
        return "Retromod converted " + mods + " to work with this Minecraft version.\n"
                + "The game needs to restart to load " + them + ".\n\n"
                + "Click Yes to close Minecraft, then relaunch from your launcher.";
    }

    /** Text for the desktop popup Forge and NeoForge show after translating into the cache. */
    public static String cacheRestartPopupText(int n) {
        // A Retromod update clears the cache, so this restart is not a one-time event.
        return "Retromod translated " + n + " mod" + (n == 1 ? "" : "s")
                + " for this Minecraft version.\n\n"
                + "Restart Minecraft to load " + (n == 1 ? "it" : "them")
                + ". Your files in mods/ were not changed.\n"
                + "Retromod asks again after it translates a new mod or after a Retromod update.";
    }

    /** Cleanly stop the Minecraft client so the user can relaunch. */
    private static void stopClient() {
        // The player relaunches from their launcher. An in-process relaunch cannot be done
        // reliably across launchers (Prism, MultiMC and vanilla each wrap the launch), and a
        // half-working relaunch is worse than a clean stop.
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
        // restart_prompt lives in config/retromod/config.json. Setting it to false suppresses the prompt.
        return RetromodConfig.getBooleanIfPresent("restart_prompt", true);
    }

    static void resetForTesting() {
        pendingCount = 0;
        translatedIntoCache = false;
        shownThisSession = false;
    }
    static void markShownForTesting() { shownThisSession = true; }
    static int pendingForTesting() { return pendingCount; }
}
