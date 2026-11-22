// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.notifications;

import net.minecraft.world.level.gamerules.GameRule;

public interface NotificationService {
    <T> void onGameRuleChanged(GameRule<T> rule, T value);
}
