// Compile-only stub of a Forge 26.2 and 26.3 client host type for src/legacyapi; never packaged.
package net.minecraftforge.client.event;

import net.minecraft.network.chat.Component;
import net.minecraftforge.eventbus.api.event.MutableEvent;

public final class RenderNameTagEvent extends MutableEvent {
    public void setContent(Component content) { throw new UnsupportedOperationException(); }

    public void setScoreContent(Component content) { throw new UnsupportedOperationException(); }

    public Component getOriginalContent() { throw new UnsupportedOperationException(); }

    public Component getOriginalScore() { throw new UnsupportedOperationException(); }
}
