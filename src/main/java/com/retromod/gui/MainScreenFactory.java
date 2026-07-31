/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.util.McI18n;
import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the Retromod screen opened from the title menu.
 * It provides shortcuts for adding mods, opening the input folder, and changing settings.
 */
public final class MainScreenFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static Class<?> screenClass;
    private static Class<?> textClass;
    private static Class<?> buttonWidgetClass;
    private static Class<?> minecraftClientClass;
    private static Class<?> pressActionClass;
    private static boolean resolved;

    private MainScreenFactory() {}

    public static void open(Object parentScreen) {
        LOGGER.debug("Opening the Retromod main screen");
        if (!resolveClasses()) {
            LOGGER.warn("Cannot open the Retromod screen because the required Minecraft "
                    + "GUI classes are unavailable");
            return;
        }

        Object title = McI18n.translatable("retromod.main.title");
        if (title == null) {
            LOGGER.warn("Could not create the Retromod screen title");
            return;
        }

        Object screen = ScreenClassGenerator.createScreen(
                title,
                s -> addAllWidgets(s, parentScreen),
                () -> LOGGER.debug("Closing the Retromod main screen")
        );
        if (screen == null) {
            LOGGER.warn("Could not create the Retromod main screen");
            return;
        }
        setScreen(screen);
    }

    private static void addAllWidgets(Object screen, Object parentScreen) {
        try {
            int width  = McReflect.getIntField(screen, screenClass, 854, "width");
            int height = McReflect.getIntField(screen, screenClass, 480, "height");

            // Uses a Unicode gear glyph since SpriteIconButton is unreliable
            // across MC versions. The text is short ("⚙") so the button can
            // still be square (20x20).
            Object settingsBtn = buildButton(McI18n.literal("\u2699"),
                    makePressAction(() -> ConfigScreenFactory.open(screen)),
                    width - 22, 2, 20, 20);
            addWidget(screen, settingsBtn);

            int btnWidth  = 240;
            int btnHeight = 24;
            int gap       = 8;

            // Title text via a non-interactive button (cheapest way to get
            // labeled text without a full DrawContext call from reflection).
            // Stack: subtitle + Add Mods + Open Folder, vertically centered.
            int totalH = btnHeight * 3 + gap * 2;
            int startY = Math.max(50, (height - totalH) / 2 - 10);
            int x = width / 2 - btnWidth / 2;

            // Subtitle / instructions row (label-style, click does nothing)
            Object subtitle = buildButton(
                    McI18n.translatable("retromod.main.subtitle"),
                    makePressAction(() -> {}),
                    x, startY, btnWidth, btnHeight);
            addWidget(screen, subtitle);

            // Add Mods button: opens the OS native file picker
            Object addBtn = buildButton(
                    McI18n.translatable("retromod.main.add_mods"),
                    makePressAction(() -> openNativeFilePicker(screen)),
                    x, startY + btnHeight + gap, btnWidth, btnHeight);
            addWidget(screen, addBtn);

            // Open Mods Folder button: reliable cross-platform alternative
            Object folderBtn = buildButton(
                    McI18n.translatable("retromod.main.open_folder"),
                    makePressAction(MainScreenFactory::openInputFolder),
                    x, startY + (btnHeight + gap) * 2, btnWidth, btnHeight);
            addWidget(screen, folderBtn);

            Object doneBtn = buildButton(McI18n.translatable("retromod.settings.done"),
                    makePressAction(() -> setScreen(parentScreen)),
                    width / 2 - 75, height - 28, 150, 20);
            addWidget(screen, doneBtn);

        } catch (Exception e) {
            LOGGER.warn("Could not lay out Retromod main screen: {}", e.getMessage());
        }
    }

    // ACTIONS

    /**
     * Open the OS native file picker on a daemon thread so we don't block
     * the render thread. macOS-specific system properties are set first to
     * give AWT the best chance of initializing alongside LWJGL/GLFW.
     */
    private static void openNativeFilePicker(Object parentScreen) {
        // macOS hardening: GLFW owns the main thread (-XstartOnFirstThread),
        // which can prevent AppKit from initializing AWT. These properties
        // make AWT behave more like a background utility than a foreground
        // app, which sidesteps the worst of the conflict.
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        System.setProperty("apple.awt.UIElement", "true");
        System.setProperty("apple.awt.application.appearance", "system");
        System.setProperty("java.awt.headless", "false");

        Thread t = new Thread(() -> {
            try {
                Class<?> retroScreenCls = Class.forName("com.retromod.gui.RetromodScreen");
                Object client = getClientInstance();
                Object instance = retroScreenCls
                        .getConstructor(Object.class, Object.class)
                        .newInstance(client, parentScreen);
                retroScreenCls.getMethod("open").invoke(instance);
            } catch (Throwable t2) {
                LOGGER.warn("File picker failed ({}). Fall back to 'Open Mods Folder'.",
                        t2.getMessage());
            }
        }, "Retromod-FilePicker");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Open {@code retromod-input/} in the OS file manager so the user can
     * drop JARs into it. Works on every platform, using MC's own
     * Util.getOperatingSystem().open() (same call as the vanilla
     * "Open Resource Pack Folder" button).
     */
    private static void openInputFolder() {
        try {
            Path inputDir = resolveInputDir();
            Files.createDirectories(inputDir);

            Class<?> utilClass = McReflect.findClass(
                "net.minecraft.util.Util",
                "net.minecraft.Util"
            );
            if (utilClass != null) {
                Method getOs = McReflect.findMethod(utilClass, "getOperatingSystem", "getPlatform");
                if (getOs != null) {
                    Object os = getOs.invoke(null);
                    Method open = McReflect.findMethod(os.getClass(),
                            new Class[]{java.io.File.class}, "open");
                    if (open != null) {
                        open.invoke(os, inputDir.toFile());
                        return;
                    }
                }
            }
            // Fallback: java.awt.Desktop
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(inputDir.toFile());
            }
        } catch (Exception e) {
            LOGGER.warn("Could not open retromod-input folder: {}", e.getMessage());
        }
    }

    private static Path resolveInputDir() {
        try {
            Object loader = Class.forName("net.fabricmc.loader.api.FabricLoader")
                    .getMethod("getInstance").invoke(null);
            Path gameDir = (Path) loader.getClass().getMethod("getGameDir").invoke(loader);
            return gameDir.resolve("retromod-input");
        } catch (Throwable t) {
            return Path.of("retromod-input");
        }
    }

    // BUTTON BUILDERS (mirrored from ConfigScreenFactory for now)

    private static Object buildButton(Object text, Object pressAction,
                                       int x, int y, int w, int h) {
        try {
            if (text == null || pressAction == null) return null;

            Method builder = McReflect.findMethod(buttonWidgetClass,
                    new Class[]{textClass, pressActionClass}, "builder");
            if (builder == null) return null;

            Object b = builder.invoke(null, text, pressAction);

            Method dim = McReflect.findMethod(b.getClass(),
                    new Class[]{int.class, int.class, int.class, int.class},
                    "dimensions", "bounds", "pos");
            if (dim != null) b = dim.invoke(b, x, y, w, h);

            Method build = McReflect.findMethod(b.getClass(), "build");
            return build != null ? build.invoke(b) : null;
        } catch (Exception e) {
            LOGGER.debug("Could not build main-screen button: {}", e.getMessage());
            return null;
        }
    }

    private static Object makePressAction(Runnable onPress) {
        if (pressActionClass == null) return null;
        return Proxy.newProxyInstance(
                pressActionClass.getClassLoader(),
                new Class<?>[]{pressActionClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() != Object.class) onPress.run();
                    return null;
                });
    }

    private static void addWidget(Object screen, Object widget) {
        if (widget == null) return;
        try {
            Method add = McReflect.findMethod(screenClass,
                    "addDrawableChild", "addRenderableWidget", "addWidget");
            if (add != null) {
                add.setAccessible(true);
                add.invoke(screen, widget);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not add widget: {}", e.getMessage());
        }
    }

    // MC CLASS RESOLUTION

    private static boolean resolveClasses() {
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
        buttonWidgetClass = McReflect.findClass(
            "net.minecraft.client.gui.widget.ButtonWidget",
            "net.minecraft.client.gui.components.Button"
        );
        minecraftClientClass = McReflect.findClass(
            "net.minecraft.client.MinecraftClient",
            "net.minecraft.client.Minecraft"
        );

        if (buttonWidgetClass != null) {
            for (Class<?> inner : buttonWidgetClass.getDeclaredClasses()) {
                if (inner.isInterface() && inner.getMethods().length <= 2) {
                    pressActionClass = inner;
                    break;
                }
            }
        }
        if (pressActionClass == null) {
            pressActionClass = McReflect.findClass(
                "net.minecraft.client.gui.widget.ButtonWidget$PressAction",
                "net.minecraft.client.gui.components.Button$OnPress"
            );
        }

        return screenClass != null && textClass != null && buttonWidgetClass != null;
    }

    private static void setScreen(Object screen) {
        try {
            Object client = getClientInstance();
            if (client == null) return;
            Method set = McReflect.findMethod(minecraftClientClass,
                    new Class[]{screenClass}, "setScreen");
            if (set != null) set.invoke(client, screen);
        } catch (Exception e) {
            LOGGER.debug("Could not set screen: {}", e.getMessage());
        }
    }

    private static Object getClientInstance() {
        try {
            Method getInstance = McReflect.findMethod(minecraftClientClass, "getInstance");
            if (getInstance != null) return getInstance.invoke(null);
        } catch (Exception e) {
            LOGGER.debug("Could not get MC client: {}", e.getMessage());
        }
        return null;
    }
}
