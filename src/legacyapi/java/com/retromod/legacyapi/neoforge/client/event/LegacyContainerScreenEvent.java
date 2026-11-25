/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.bus.api.Event;

/** NeoForge 1.21.1's container screen events, which NeoForge 26.3 removed; Retromod posts the render ones after each container screen draws. */
public abstract class LegacyContainerScreenEvent extends Event {

    // Screens whose own render method posted this copy's events this frame, so Retromod's post does not repeat them.
    private static final Map<Object, Boolean> SELF_POSTED = new WeakHashMap<>();

    private final AbstractContainerScreen<?> screen;

    protected LegacyContainerScreenEvent(AbstractContainerScreen<?> screen) {
        this.screen = screen;
    }

    public AbstractContainerScreen<?> getContainerScreen() {
        return screen;
    }

    /** Whether the screen posted this copy's render events itself since its frame began, as some old container screens do. */
    static boolean postedBy(Object screen) {
        synchronized (SELF_POSTED) {
            return SELF_POSTED.containsKey(screen);
        }
    }

    static void frameStarted(Object screen) {
        synchronized (SELF_POSTED) {
            SELF_POSTED.remove(screen);
        }
    }

    /** The render events; Background draws in screen space, Foreground with the pose moved to the container's corner. */
    public abstract static class Render extends LegacyContainerScreenEvent {
        private final GuiGraphicsExtractor graphics;
        private final int mouseX;
        private final int mouseY;

        protected Render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            super(screen);
            this.graphics = graphics;
            this.mouseX = mouseX;
            this.mouseY = mouseY;
            // A container screen that builds the event in its own render posts it itself, so Retromod skips its post that frame.
            if (!Posting.active()) {
                synchronized (SELF_POSTED) {
                    SELF_POSTED.put(screen, Boolean.TRUE);
                }
            }
        }

        public GuiGraphicsExtractor getGuiGraphics() {
            return graphics;
        }

        public int getMouseX() {
            return mouseX;
        }

        public int getMouseY() {
            return mouseY;
        }

        public static class Background extends Render {
            public Background(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
                super(screen, graphics, mouseX, mouseY);
            }
        }

        public static class Foreground extends Render {
            public Foreground(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
                super(screen, graphics, mouseX, mouseY);
            }
        }
    }

    /** Marks the events Retromod builds itself, on the render thread that builds them. */
    static final class Posting {
        private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

        static boolean active() {
            return Boolean.TRUE.equals(ACTIVE.get());
        }

        static void set(boolean active) {
            if (active) ACTIVE.set(Boolean.TRUE);
            else ACTIVE.remove();
        }
    }
}
