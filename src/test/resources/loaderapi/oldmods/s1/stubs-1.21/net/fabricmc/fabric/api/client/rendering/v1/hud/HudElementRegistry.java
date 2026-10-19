package net.fabricmc.fabric.api.client.rendering.v1.hud;

import net.minecraft.class_2960;

import java.util.function.Function;

/** Compile-only copy of the Fabric API 0.128.2 (1.21.6) signature. */
public interface HudElementRegistry {
    static void attachElementBefore(class_2960 beforeThis, class_2960 identifier, HudElement element) {
        throw new AssertionError("compile-only");
    }

    static void attachElementAfter(class_2960 afterThis, class_2960 identifier, HudElement element) {
        throw new AssertionError("compile-only");
    }

    static void removeElement(class_2960 identifier) {
        throw new AssertionError("compile-only");
    }

    static void replaceElement(class_2960 identifier, Function<HudElement, HudElement> replacer) {
        throw new AssertionError("compile-only");
    }
}
