/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.inplace.InPlaceLaunch;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import com.retromod.util.McI18n;
import com.retromod.util.McOpener;
import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Builds the Retromod screen opened from the title menu. */
public final class MainScreenFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    // The alert stacks its text above its button, so a long list pushes the button off screen.
    private static final int MAX_NAMED_FILES = 5;

    private static Class<?> screenClass;
    private static Class<?> textClass;
    private static Class<?> buttonWidgetClass;
    private static Class<?> minecraftClientClass;
    private static Class<?> pressActionClass;
    private static boolean resolved;

    private MainScreenFactory() {}

    public static void open(Object parentScreen) {
        LOGGER.debug("Opening the Retromod main screen");
        // This screen only opens on a running client, so Minecraft's own openers may run now.
        McOpener.markClientRunning();
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

        // Escape returns to the screen the player came from, like Done. Jars dropped on the
        // screen go the same way as picked ones.
        Object screen = ScreenClassGenerator.createScreen(
                title,
                s -> addAllWidgets(s, parentScreen),
                () -> setScreen(parentScreen),
                MainScreenFactory::takeDroppedFiles
        );
        if (screen == null) {
            LOGGER.warn("Could not create the Retromod main screen");
            return;
        }
        setScreen(screen);
    }

    private static void addAllWidgets(Object screen, Object parentScreen) {
        try {
            int width  = McReflect.getIntField(screen, screenClass, 854, "width", "field_22789");
            int height = McReflect.getIntField(screen, screenClass, 480, "height", "field_22790");

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
            // Stack: subtitle + Add Mods + Open Folder, vertically centered,
            // plus an in-place status row on Forge and NeoForge.
            Object inPlaceStatus = inPlaceStatusText();
            int rows = inPlaceStatus == null ? 3 : 4;
            int totalH = btnHeight * rows + gap * (rows - 1);
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

            // Open retromod-input/ button: the folder Retromod reads old jars from
            Object folderBtn = buildButton(
                    McI18n.translatable("retromod.main.open_folder"),
                    makePressAction(() -> openAndShowInputFolder(screen)),
                    x, startY + (btnHeight + gap) * 2, btnWidth, btnHeight);
            addWidget(screen, folderBtn);

            // In-place status row, a label like the subtitle.
            if (inPlaceStatus != null) {
                int statusWidth = 300;
                Object statusRow = buildButton(inPlaceStatus, makePressAction(() -> { }),
                        width / 2 - statusWidth / 2, startY + (btnHeight + gap) * 3,
                        statusWidth, btnHeight);
                addWidget(screen, statusRow);
            }

            Object doneBtn = buildButton(McI18n.translatable("retromod.settings.done"),
                    makePressAction(() -> setScreen(parentScreen)),
                    width / 2 - 75, height - 28, 150, 20);
            addWidget(screen, doneBtn);

        } catch (Exception e) {
            LOGGER.warn("Could not lay out Retromod main screen: {}", e.getMessage());
        }
    }

    /** Counts from in-place loading in this launch, or null where it did not run. */
    private static Object inPlaceStatusText() {
        // In-place mode changes nothing in mods/, so this row is where a player sees how many
        // mods load from the cache, wait for a restart, or failed.
        return InPlaceLaunch.summaryThisLaunch()
                .map(summary -> McI18n.translatable("retromod.main.inplace_status",
                        summary.served(), summary.translated(), summary.failed()))
                .orElse(null);
    }

    // ACTIONS

    /** Opens the system file picker; without a client, it shows retromod-input/ instead. */
    private static void openNativeFilePicker(Object parentScreen) {
        Object client = getClientInstance();
        if (client == null) {
            LOGGER.warn("Retromod could not show a file picker because the Minecraft client "
                    + "was not found. It opens retromod-input/ instead.");
            showInputFolderInstead(parentScreen, true);
            return;
        }
        new RetromodScreen(client, parentScreen).open();
    }

    /** Hands jars dropped on the Retromod screen to the same path as picked ones. */
    private static void takeDroppedFiles(Object screen, List<Path> dropped) {
        Object client = getClientInstance();
        if (client == null) {
            LOGGER.warn("Retromod could not take the dropped files because the Minecraft client "
                    + "was not found. Put them in retromod-input/ instead.");
            return;
        }
        new RetromodScreen(client, screen).takeDropped(dropped);
    }

    /** The folder button: opens retromod-input/ and shows its path, since no opener reports back. */
    private static void openAndShowInputFolder(Object parentScreen) {
        boolean opened = openInputFolder();
        InGameScreenFactory.showAlert(parentScreen, "Retromod",
                opened ? openedFolderMessage(inputDir()) : inputFolderMessage(false, inputDir()));
    }

    /** Opens retromod-input/ if it can and shows its path; call on Minecraft's main thread. */
    static void showInputFolderInstead(Object parentScreen, boolean pickerFailed) {
        // A picker failure still opens the folder, so the player has somewhere to put the jars.
        if (pickerFailed) openInputFolder();
        InGameScreenFactory.showAlert(parentScreen, "Retromod",
                inputFolderMessage(pickerFailed, inputDir()));
    }

    /** Names the chosen jars Retromod cannot read, opens retromod-input/, and shows its path. */
    static void showUnreadableInstead(Object parentScreen, List<String> fileNames) {
        openInputFolder();
        InGameScreenFactory.showAlert(parentScreen, "Retromod",
                unreadableMessage(fileNames, inputDir()));
    }

    /** What the player reads when Retromod names retromod-input/ instead of picking or opening. */
    static String inputFolderMessage(boolean pickerFailed, Path folder) {
        String failure = pickerFailed
                ? "Retromod could not show a file picker. Put the old mod jars in this folder"
                : "Retromod could not open this folder. Put the old mod jars in it";
        return failure + ", then restart Minecraft:\n\n" + folder;
    }

    /** What the folder button shows after handing the folder to the system. */
    static String openedFolderMessage(Path folder) {
        return "Put the old mod jars in this folder, then restart Minecraft. If no folder window "
                + "opened, open this path in your file manager:\n\n" + folder;
    }

    /** What the player reads when a picked or dropped jar cannot be read where it is. */
    static String unreadableMessage(List<String> fileNames, Path folder) {
        int named = Math.min(fileNames.size(), MAX_NAMED_FILES);
        String files = String.join(", ", fileNames.subList(0, named));
        if (fileNames.size() > named) files += " and " + (fileNames.size() - named) + " more";
        return "Retromod cannot read " + files + " where it is. Put the old mod jars in this "
                + "folder, then restart Minecraft:\n\n" + folder;
    }

    /** Opens retromod-input/ through Minecraft; false when Retromod could only log its path. */
    private static boolean openInputFolder() {
        Path inputDir = inputDir();
        try {
            Files.createDirectories(inputDir);
        } catch (IOException e) {
            LOGGER.warn("Retromod could not create {}: {}", inputDir, e.getMessage());
            return false;
        }
        return McOpener.openFolder(inputDir);
    }

    /** retromod-input/ as the entry points read it, as an absolute path. */
    private static Path inputDir() {
        return resolveInputDir().toAbsolutePath().normalize();
    }

    private static Path resolveInputDir() {
        try {
            Object loader = Class.forName("net.fabricmc.loader.api.FabricLoader")
                    .getMethod("getInstance").invoke(null);
            Path gameDir = (Path) LegacyPublicMethods.method(loader.getClass(), "getGameDir").invoke(loader);
            return gameDir.resolve("retromod-input");
        } catch (Throwable t) {
            // Forge and NeoForge read it from the working folder, as RetromodForge and
            // RetromodNeoForge do.
            return Path.of("retromod-input");
        }
    }

    // BUTTON BUILDERS (mirrored from ConfigScreenFactory for now)

    private static Object buildButton(Object text, Object pressAction,
                                       int x, int y, int w, int h) {
        try {
            if (text == null || pressAction == null) return null;

            Method builder = McReflect.findMethod(buttonWidgetClass,
                    new Class[]{textClass, pressActionClass}, "builder", "method_46430");
            if (builder == null) return null;

            Object b = builder.invoke(null, text, pressAction);

            Method dim = McReflect.findMethod(b.getClass(),
                    new Class[]{int.class, int.class, int.class, int.class},
                    "dimensions", "bounds", "method_46434", "pos");
            if (dim != null) b = dim.invoke(b, x, y, w, h);

            Method build = McReflect.findMethod(b.getClass(), "build", "method_46431");
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
                    "addDrawableChild", "addRenderableWidget", "method_37063", "addWidget");
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
            "net.minecraft.client.gui.screens.Screen",
            "net.minecraft.class_437"  // intermediary, pre-26.1 Fabric
        );
        textClass = McReflect.findClass(
            "net.minecraft.text.Text",
            "net.minecraft.network.chat.Component",
            "net.minecraft.class_2561"
        );
        buttonWidgetClass = McReflect.findClass(
            "net.minecraft.client.gui.widget.ButtonWidget",
            "net.minecraft.client.gui.components.Button",
            "net.minecraft.class_4185"
        );
        minecraftClientClass = McReflect.findClass(
            "net.minecraft.client.MinecraftClient",
            "net.minecraft.client.Minecraft",
            "net.minecraft.class_310"
        );

        // Named first, because Button's other one-method interface is CreateNarration.
        pressActionClass = McReflect.findClass(
            "net.minecraft.client.gui.widget.ButtonWidget$PressAction",
            "net.minecraft.client.gui.components.Button$OnPress",
            "net.minecraft.class_4185$class_4241"
        );
        if (pressActionClass == null && buttonWidgetClass != null) {
            for (Class<?> inner : buttonWidgetClass.getDeclaredClasses()) {
                if (inner.isInterface() && inner.getMethods().length <= 2) {
                    pressActionClass = inner;
                    break;
                }
            }
        }

        return screenClass != null && textClass != null && buttonWidgetClass != null;
    }

    private static void setScreen(Object screen) {
        McScreens.show(screen);
    }

    private static Object getClientInstance() {
        try {
            Method getInstance = McReflect.findMethod(minecraftClientClass, "getInstance",
                    "method_1551");
            if (getInstance != null) return getInstance.invoke(null);
        } catch (Exception e) {
            LOGGER.debug("Could not get MC client: {}", e.getMessage());
        }
        return null;
    }
}
