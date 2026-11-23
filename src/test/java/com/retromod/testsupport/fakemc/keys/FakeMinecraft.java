/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.keys;

/** Stands in for {@code Minecraft}: the modifier state a test sets, and the window. */
public final class FakeMinecraft {
    public static boolean control;
    public static boolean shift;
    public static boolean alt;
    private static final FakeMinecraft INSTANCE = new FakeMinecraft();
    private final FakeWindow window = new FakeWindow();

    public static FakeMinecraft getInstance() {
        return INSTANCE;
    }

    public FakeWindow getWindow() {
        return window;
    }

    public boolean hasControlDown() {
        return control;
    }

    public boolean hasShiftDown() {
        return shift;
    }

    public boolean hasAltDown() {
        return alt;
    }
}
