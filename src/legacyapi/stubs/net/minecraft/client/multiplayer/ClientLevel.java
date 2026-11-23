// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.multiplayer;

import net.minecraft.world.level.Level;

public class ClientLevel extends Level {
    public ClientLevelData getLevelData() { throw new UnsupportedOperationException(); }

    public static class ClientLevelData implements net.minecraft.world.level.storage.LevelData {}
}
