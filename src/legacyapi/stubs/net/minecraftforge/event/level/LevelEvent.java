// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.event.level;

import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.InheritableEvent;

public interface LevelEvent extends InheritableEvent {
    LevelAccessor getLevel();

    // A record on the host.
    final class Unload implements LevelEvent {
        public static final EventBus<Unload> BUS = null;

        public LevelAccessor getLevel() { throw new UnsupportedOperationException(); }
    }
}
