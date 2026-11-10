/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.core.RetromodConfig;
import com.retromod.util.McI18n;
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

    // On intermediary hosts (Fabric before 26.1) close() keeps its plain name, because
    // AutoCloseable declares it. It tears the game down mid-frame, so stop()'s id comes first.
    static final String[] STOP_NAMES = {"scheduleStop", "stop", "method_1592", "close"};

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
     * if the user declines). Returns true only when the prompt opened.
     */
    public static boolean maybeShow(Object titleScreen) {
        if (!shouldShow()) return false;
        // Once per session, even when it fails: a resize re-inits the title screen every time.
        shownThisSession = true;
        int n = pendingCount;
        boolean shown;
        try {
            // The text comes from the player's language, with the count in its %s slot.
            Object msg = McI18n.translatable(messageKey(translatedIntoCache), n);
            shown = InGameScreenFactory.showRestartConfirm(titleScreen, "Retromod", msg,
                    RestartPrompt::stopClient);
        } catch (Throwable t) {
            LOGGER.warn("Could not show restart prompt: {}", t.getMessage());
            shown = false;
        }
        if (shown) {
            LOGGER.info("Showed in-game restart prompt for {} converted mod(s)", n);
        } else {
            LOGGER.warn("Retromod could not open its restart prompt. Restart Minecraft to load "
                    + "the {} mod(s) it prepared.", n);
        }
        return shown;
    }

    /** The lang key of the prompt text; the cache text says mods/ was not changed. */
    static String messageKey(boolean intoCache) {
        return intoCache ? "retromod.restart.cache" : "retromod.restart.converted";
    }

    /** The console line for {@code count} mods that load after a restart, or null for none. */
    public static String consoleText(boolean dedicatedServer, int count, boolean intoCache) {
        if (count <= 0) return null;
        String mods = count + (count == 1 ? " mod" : " mods");
        // A client player restarts Minecraft; only a server admin restarts a server.
        String restart = (dedicatedServer ? "Restart the server" : "Restart Minecraft")
                + " to load " + (count == 1 ? "it" : "them") + ".";
        if (intoCache) {
            return "Translated " + mods + " into retromod-cache/. " + restart
                    + " The files in mods/ were not changed.";
        }
        return "Updated " + mods + ". " + restart;
    }

    /** Cleanly stop the Minecraft client so the user can relaunch. */
    static void stopClient() {
        // The player relaunches from their launcher. An in-process relaunch cannot be done
        // reliably across launchers (Prism, MultiMC and vanilla each wrap the launch), and a
        // half-working relaunch is worse than a clean stop.
        try {
            Class<?> mcClass = McReflect.findClass(
                    "net.minecraft.client.MinecraftClient",  // yarn
                    "net.minecraft.client.Minecraft",        // mojang
                    "net.minecraft.class_310");              // intermediary, pre-26.1 Fabric
            if (mcClass == null) return;
            Method getInstance = McReflect.findMethod(mcClass, "getInstance", "method_1551");
            Object client = getInstance != null ? getInstance.invoke(null) : null;
            if (client != null) stop(client);
        } catch (Throwable t) {
            LOGGER.warn("Could not stop client for restart: {}", t.getMessage());
        }
    }

    /** Stops {@code client} through Yarn's scheduleStop() or Mojang's stop(). */
    static void stop(Object client) throws ReflectiveOperationException {
        // Both ask the main loop to finish, a clean shutdown. close() is the last resort.
        Method stop = McReflect.findMethod(client.getClass(), STOP_NAMES);
        if (stop != null) {
            LOGGER.info("Stopping client to apply converted mods (user will relaunch)");
            stop.invoke(client);
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
