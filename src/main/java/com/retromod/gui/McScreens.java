/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Shows a Minecraft screen through whichever screen setter the running game has. */
final class McScreens {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-GUI");

    private McScreens() {}

    /** Shows {@code screen}, or closes the current screen for null; false when that failed. */
    static boolean show(Object screen) {
        Class<?> minecraft = McReflect.findClass(
                "net.minecraft.client.MinecraftClient",    // yarn
                "net.minecraft.client.Minecraft",          // mojang
                "net.minecraft.class_310");                // intermediary, pre-26.1 Fabric
        Class<?> screenClass = McReflect.findClass(
                "net.minecraft.client.gui.screen.Screen",  // yarn
                "net.minecraft.client.gui.screens.Screen", // mojang
                "net.minecraft.class_437");                // intermediary
        if (minecraft == null || screenClass == null) {
            LOGGER.warn("Retromod could not open its screen because Minecraft's client or Screen "
                    + "class was not found.");
            return false;
        }
        try {
            Method getInstance = McReflect.findMethod(minecraft, "getInstance", "method_1551");
            Object client = getInstance != null ? getInstance.invoke(null) : null;
            if (client == null) {
                LOGGER.warn("Retromod could not open its screen because the Minecraft client "
                        + "is not running yet.");
                return false;
            }
            return show(client, screenClass, screen);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Retromod could not reach the Minecraft client to open its screen: {}",
                    describe(e));
            return false;
        }
    }

    /** Shows {@code screen} on {@code client}; false when no setter exists or the call failed. */
    static boolean show(Object client, Class<?> screenClass, Object screen) {
        try {
            // Minecraft.setScreen sets screens on every version up to 26.1.
            Method clientSetter = McReflect.findMethod(client.getClass(),
                    new Class[]{screenClass}, "setScreen", "method_1507");
            if (clientSetter != null) {
                clientSetter.invoke(client, screen);
                return true;
            }

            // 26.2 moved the setter to Gui, which the client holds in its public gui field.
            Field guiField = McReflect.findField(client.getClass(), "gui");
            Object gui = guiField != null ? guiField.get(client) : null;
            Method guiSetter = gui != null
                    ? McReflect.findMethod(guiField.getType(), new Class[]{screenClass}, "setScreen")
                    : null;
            if (guiSetter != null) {
                guiSetter.invoke(gui, screen);
                return true;
            }

            LOGGER.warn("Retromod could not open its screen: this Minecraft version has neither "
                    + "Minecraft.setScreen nor Gui.setScreen.");
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Retromod could not open its screen: {}", describe(e));
            return false;
        }
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException wrapped
                && wrapped.getCause() != null ? wrapped.getCause() : error;
        return cause.getClass().getSimpleName()
                + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
    }
}
