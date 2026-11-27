// Compile-only stub of a NeoForge 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.event;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.Event;

public abstract class ScreenEvent extends Event {
    public Screen getScreen() { throw new UnsupportedOperationException(); }

    public abstract static class Render extends ScreenEvent {
        public GuiGraphicsExtractor getGuiGraphics() { throw new UnsupportedOperationException(); }
        public int getMouseX() { throw new UnsupportedOperationException(); }
        public int getMouseY() { throw new UnsupportedOperationException(); }

        public static class Pre extends Render {
            private Pre() {}
        }

        public static class Post extends Render {
            private Post() {}
        }
    }
}
