/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Consumer;

/**
 * Creates in-game Minecraft screens via reflection (MC is not on Retromod's
 * compile classpath). Renders inside the game window, not as Swing popups.
 */
public final class InGameScreenFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-GUI");

    // Resolve Minecraft classes only when a screen is first needed.
    private static Class<?> screenClass;
    private static Class<?> textClass;
    private static Class<?> confirmScreenClass;
    private static Class<?> minecraftClientClass;
    private static Class<?> buttonWidgetClass;
    private static boolean resolved = false;

    private InGameScreenFactory() {}

    private static synchronized boolean resolveClasses() {
        if (resolved) return screenClass != null;

        resolved = true;

        screenClass = McReflect.findClass(
            "net.minecraft.client.gui.screen.Screen",
            "net.minecraft.client.gui.screens.Screen"
        );

        textClass = McReflect.findClass(
            "net.minecraft.text.Text",
            "net.minecraft.network.chat.Component"
        );

        // ConfirmLinkScreen has a different constructor and cannot be used here.
        confirmScreenClass = McReflect.findClass(
            "net.minecraft.client.gui.screen.ConfirmScreen",    // yarn
            "net.minecraft.client.gui.screens.ConfirmScreen"    // mojang
        );

        minecraftClientClass = McReflect.findClass(
            "net.minecraft.client.MinecraftClient",
            "net.minecraft.client.Minecraft"
        );

        buttonWidgetClass = McReflect.findClass(
            "net.minecraft.client.gui.widget.ButtonWidget",
            "net.minecraft.client.gui.components.Button"
        );

        if (screenClass == null || textClass == null || minecraftClientClass == null) {
            LOGGER.warn("Retromod could not find the Minecraft screen classes for this version.");
            return false;
        }

        return true;
    }

    private static Object getClientInstance() {
        try {
            Method getInstance = McReflect.findMethod(minecraftClientClass, "getInstance");
            return getInstance != null ? getInstance.invoke(null) : null;
        } catch (Exception e) {
            LOGGER.debug("Could not get the Minecraft client instance: {}", e.getMessage());
            return null;
        }
    }

    private static Object createText(String text) {
        try {
            // Current Yarn and Mojang names share literal().
            Method literal = McReflect.findMethod(textClass,
                new Class[]{String.class}, "literal");
            if (literal != null) {
                return literal.invoke(null, text);
            }

            // Older versions may expose of() instead.
            Method of = McReflect.findMethod(textClass,
                new Class[]{String.class}, "of");
            if (of != null) {
                return of.invoke(null, text);
            }

            // Oldest supported clients use a concrete text class.
            Class<?> literalTextClass = McReflect.findClass(
                "net.minecraft.text.LiteralText",
                "net.minecraft.network.chat.TextComponent"
            );
            if (literalTextClass != null) {
                return literalTextClass.getConstructor(String.class).newInstance(text);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not create Minecraft text: {}", e.getMessage());
        }
        return null;
    }

    private static void setScreen(Object screen) {
        try {
            Object client = getClientInstance();
            if (client == null) return;

            Method setScreen = McReflect.findMethod(minecraftClientClass,
                new Class[]{screenClass}, "setScreen");
            if (setScreen != null) {
                setScreen.invoke(client, screen);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not open the Minecraft screen: {}", e.getMessage());
        }
    }

    /**
     * Show a confirmation dialog in-game with Yes/No buttons.
     *
     * @param title   the dialog title
     * @param message the dialog message
     * @param onYes   callback when Yes is clicked
     * @param onNo    callback when No is clicked (or null to just close)
     */
    public static void showConfirmScreen(String title, String message,
                                          Runnable onYes, Runnable onNo) {
        if (!resolveClasses()) {
            LOGGER.warn("The confirmation screen is unavailable on this Minecraft version.");
            return;
        }

        try {
            Object titleText = createText(title);
            Object messageText = createText(message);

            if (titleText == null || messageText == null) return;

            // ConfirmScreen(callback, title, message), callback being a
            // (boolean confirmed) -> ... BooleanConsumer or vanilla SAM
            Class<?> boolConsumerClass = null;
            try {
                boolConsumerClass = Class.forName("it.unimi.dsi.fastutil.booleans.BooleanConsumer");
            } catch (ClassNotFoundException e) {
                try {
                    boolConsumerClass = Class.forName("net.minecraft.client.gui.screen.ConfirmScreen$1");
                } catch (ClassNotFoundException e2) {
                    // ignored
                }
            }

            if (boolConsumerClass != null) {
                Object callback = Proxy.newProxyInstance(
                    boolConsumerClass.getClassLoader(),
                    new Class<?>[]{boolConsumerClass},
                    (proxy, method, args) -> {
                        if (method.getParameterCount() == 1) {
                            boolean confirmed = (Boolean) args[0];
                            if (confirmed && onYes != null) {
                                onYes.run();
                            } else if (!confirmed && onNo != null) {
                                onNo.run();
                            }
                            setScreen(null);
                        }
                        return null;
                    }
                );

                Constructor<?> ctor = confirmScreenClass.getConstructor(
                    boolConsumerClass, textClass, textClass);
                Object screen = ctor.newInstance(callback, titleText, messageText);
                setScreen(screen);
                return;
            }

            // fall back to a plain notification
            showNotification(title + "\n" + message);

        } catch (Exception e) {
            LOGGER.warn("Could not show the confirmation screen: {}", e.getMessage());
        }
    }

    /**
     * Show a Yes/No confirmation over a known parent screen. Declining returns
     * to {@code parentScreen} rather than clearing to a null screen, so it can
     * be invoked from a title-screen init hook. Used by the restart prompt (#33).
     *
     * @param parentScreen the screen to return to if the user clicks No
     * @param title        dialog title
     * @param message      dialog body (supports newlines)
     * @param onYes        run when the user clicks Yes
     */
    public static void showRestartConfirm(Object parentScreen, String title,
                                          String message, Runnable onYes) {
        if (!resolveClasses() || confirmScreenClass == null) {
            LOGGER.warn("The restart screen is unavailable on this Minecraft version.");
            return;
        }
        try {
            Object titleText = createText(title);
            Object messageText = createText(message);
            if (titleText == null || messageText == null) return;

            Class<?> boolConsumerClass;
            try {
                boolConsumerClass = Class.forName("it.unimi.dsi.fastutil.booleans.BooleanConsumer");
            } catch (ClassNotFoundException e) {
                LOGGER.debug("BooleanConsumer unavailable; cannot build ConfirmScreen");
                return;
            }

            Object callback = Proxy.newProxyInstance(
                boolConsumerClass.getClassLoader(),
                new Class<?>[]{boolConsumerClass},
                (proxy, method, args) -> {
                    if (method.getParameterCount() == 1 && args != null) {
                        boolean confirmed = (Boolean) args[0];
                        if (confirmed) {
                            if (onYes != null) onYes.run(); // stops the client; screen irrelevant
                        } else {
                            setScreen(parentScreen);
                        }
                    }
                    return null;
                }
            );

            Constructor<?> ctor = confirmScreenClass.getConstructor(
                boolConsumerClass, textClass, textClass);
            Object screen = ctor.newInstance(callback, titleText, messageText);
            setScreen(screen);
        } catch (Exception e) {
            LOGGER.warn("Could not show the restart screen: {}", e.getMessage());
        }
    }

    /**
     * Show a results/info screen in-game.
     *
     * @param title   the screen title
     * @param message the message body (supports newlines)
     * @param onClose callback when closed (or null)
     */
    public static void showResultScreen(String title, String message, Runnable onClose) {
        showConfirmScreen(title, message, onClose != null ? onClose : () -> {}, onClose);
    }

    /**
     * Show a simple text notification by setting a screen with a message and "Done" button.
     * Fallback when ConfirmScreen construction fails.
     */
    public static void showNotification(String message) {
        if (!resolveClasses()) return;

        try {
            // NoticeScreen exists on some MC versions
            Class<?> noticeScreenClass = McReflect.findClass(
                "net.minecraft.client.gui.screen.NoticeScreen",
                "net.minecraft.client.gui.screens.NoticeScreen"
            );

            if (noticeScreenClass != null) {
                Object titleText = createText("Retromod");
                Object messageText = createText(message);
                Object buttonText = createText("Done");

                // NoticeScreen(Runnable onClose, Text title, Text message, Text buttonText, boolean showTimer)
                try {
                    Constructor<?> ctor = noticeScreenClass.getConstructor(
                        Runnable.class, textClass, textClass, textClass, boolean.class);
                    Object screen = ctor.newInstance(
                        (Runnable) () -> setScreen(null),
                        titleText, messageText, buttonText, false);
                    setScreen(screen);
                    return;
                } catch (NoSuchMethodException e) {
                    // shorter ctor on older versions
                    Constructor<?> ctor = noticeScreenClass.getConstructor(
                        Runnable.class, textClass, textClass);
                    Object screen = ctor.newInstance(
                        (Runnable) () -> setScreen(null),
                        titleText, messageText);
                    setScreen(screen);
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("NoticeScreen not available: {}", e.getMessage());
        }

        LOGGER.info("Retromod notice: {}", message);
    }

    /** Shows one result per mod and offers to close the game when a restart is needed. */
    public static void showUpdateResults(List<String> results, boolean needsRestart) {
        StringBuilder msg = new StringBuilder();

        for (String result : results) {
            msg.append(result).append("\n");
        }

        if (needsRestart) {
            msg.append("\nRestart Minecraft to load the updated mods.");
        }

        showResultScreen("Retromod results", msg.toString(), () -> {
            if (needsRestart) {
                try {
                    Object client = getClientInstance();
                    if (client != null) {
                        Method scheduleStop = McReflect.findMethod(minecraftClientClass,
                            "scheduleStop");
                        if (scheduleStop != null) {
                            scheduleStop.invoke(client);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("Could not schedule stop: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Show a crash/error screen in-game.
     *
     * @param modName   the mod that caused the crash
     * @param errorMsg  the error message
     * @param onSaveQuit callback for "Save & Quit" action
     */
    public static void showCrashScreen(String modName, String errorMsg, Runnable onSaveQuit) {
        String title = "Retromod found a mod error";
        String message = "Retromod stopped after an updated mod failed:\n\n"
            + "Mod: " + modName + "\n"
            + "Error: " + errorMsg + "\n\n"
            + "Retromod will try to save the current world before exiting.";

        showConfirmScreen(title, message, onSaveQuit, onSaveQuit);
    }
}
