package net.fabricmc.fabric.api.client.screen.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.class_332;
import net.minecraft.class_437;

/** Compile-only copy of the Fabric API 0.92 (1.20.1) signatures. */
public final class ScreenEvents {
    private ScreenEvents() {}

    public static Event<BeforeRender> beforeRender(class_437 screen) {
        throw new AssertionError("compile-only");
    }

    public static Event<AfterRender> afterRender(class_437 screen) {
        throw new AssertionError("compile-only");
    }

    @FunctionalInterface
    public interface BeforeRender {
        void beforeRender(class_437 screen, class_332 drawContext, int mouseX, int mouseY, float tickDelta);
    }

    @FunctionalInterface
    public interface AfterRender {
        void afterRender(class_437 screen, class_332 drawContext, int mouseX, int mouseY, float tickDelta);
    }
}
