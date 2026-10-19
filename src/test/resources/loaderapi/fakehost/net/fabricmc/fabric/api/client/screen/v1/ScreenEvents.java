package net.fabricmc.fabric.api.client.screen.v1;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.IdentityHashMap;
import java.util.Map;

/** Per-screen render events, as Fabric API 26.x keeps them. */
public final class ScreenEvents {
    private static final Map<Screen, Event<BeforeExtract>> BEFORE = new IdentityHashMap<>();
    private static final Map<Screen, Event<AfterExtract>> AFTER = new IdentityHashMap<>();

    private ScreenEvents() {}

    public static Event<BeforeExtract> beforeExtract(Screen screen) {
        return BEFORE.computeIfAbsent(screen, ignored -> EventFactory.createArrayBacked(BeforeExtract.class,
                listeners -> (s, g, x, y, delta) -> {
                    for (BeforeExtract listener : listeners) listener.beforeExtract(s, g, x, y, delta);
                }));
    }

    public static Event<AfterExtract> afterExtract(Screen screen) {
        return AFTER.computeIfAbsent(screen, ignored -> EventFactory.createArrayBacked(AfterExtract.class,
                listeners -> (s, g, x, y, delta) -> {
                    for (AfterExtract listener : listeners) listener.afterExtract(s, g, x, y, delta);
                }));
    }

    @FunctionalInterface
    public interface BeforeExtract {
        void beforeExtract(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickDelta);
    }

    @FunctionalInterface
    public interface AfterExtract {
        void afterExtract(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickDelta);
    }
}
