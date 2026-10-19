/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package net.fabricmc.fabric.api.client.rendering.v1.hud;

import net.minecraft.util.Identifier;

/** Compile-only copy of the Fabric API 0.128.2 (1.21.6) signatures; the jar task leaves it out. */
public interface HudElementRegistry {
    static void attachElementBefore(Identifier beforeThis, Identifier identifier, HudElement element) {
        throw new AssertionError("compile-only");
    }

    static void attachElementAfter(Identifier afterThis, Identifier identifier, HudElement element) {
        throw new AssertionError("compile-only");
    }
}
