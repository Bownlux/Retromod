// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.level;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

public class ServerBossEvent extends BossEvent {
    public ServerBossEvent(UUID id, Component name, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
        throw new UnsupportedOperationException();
    }
}
