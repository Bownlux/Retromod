// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.notifications;

import net.minecraft.world.level.gamerules.GameRule;

public class EmptyNotificationService implements NotificationService {
    public EmptyNotificationService() { throw new UnsupportedOperationException(); }
    @Override
    public <T> void onGameRuleChanged(GameRule<T> rule, T value) { throw new UnsupportedOperationException(); }
}
