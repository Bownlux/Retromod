/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.toasts.ToastManager;

/** The old {@code Minecraft.toastManager} field, which 26.2 moved onto the GUI. */
public final class LegacyToastFields {

    private static volatile Field toastField;

    private LegacyToastFields() {}

    /** Reads the old {@code Minecraft.toastManager} field from the GUI, which holds the toast manager from 26.2. */
    public static ToastManager toastManager(Object minecraft) {
        Gui gui = ((Minecraft) minecraft).gui;
        return gui == null ? null : gui.toastManager();
    }

    /** A write of the old field, which replaces the GUI's toast manager; nothing changes when the GUI is not built yet. */
    public static void setToastManager(Object minecraft, ToastManager manager) {
        Gui gui = ((Minecraft) minecraft).gui;
        if (gui == null || manager == null) return;
        try {
            Field field = toastField;
            if (field == null) {
                field = Gui.class.getDeclaredField("toastManager");
                field.setAccessible(true);
                toastField = field;
            }
            field.set(gui, manager);
        } catch (ReflectiveOperationException | RuntimeException closed) {
            // The GUI keeps its own manager; the old one keeps drawing as before.
        }
    }
}
