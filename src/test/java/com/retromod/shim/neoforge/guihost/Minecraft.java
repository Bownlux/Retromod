/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge.guihost;

/** Stands in for the client: one instance with one window. */
public class Minecraft {
    public static final Minecraft INSTANCE = new Minecraft();
    public final Window window = new Window();

    public static Minecraft getInstance() {
        return INSTANCE;
    }

    public Window getWindow() {
        return window;
    }
}
