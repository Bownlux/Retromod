/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package net.fabricmc.fabric.api.client.rendering.v1;

import net.minecraft.client.gui.LayeredDrawer;
import net.minecraft.util.Identifier;

/** Compile-only copy of the Fabric API 0.119.4 (1.21.4) signatures, left out of the jar. */
public interface IdentifiedLayer extends LayeredDrawer.Layer {
    Identifier HOTBAR_AND_BARS = null;
    Identifier STATUS_EFFECTS = null;
    Identifier DEBUG = null;
    Identifier CHAT = null;

    Identifier id();

    static IdentifiedLayer of(Identifier id, LayeredDrawer.Layer layer) {
        throw new AssertionError("compile-only");
    }
}
