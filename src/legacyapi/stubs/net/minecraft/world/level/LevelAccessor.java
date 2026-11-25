// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level;

public interface LevelAccessor {
    // A default method on the host.
    default net.minecraft.world.Difficulty getDifficulty() { throw new UnsupportedOperationException(); }
    void levelEvent(net.minecraft.world.entity.Entity source, int type, net.minecraft.core.BlockPos pos, int data);
    // Default methods on the host.
    default void gameEvent(net.minecraft.world.entity.Entity source, net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event,
            net.minecraft.core.BlockPos pos) { throw new UnsupportedOperationException(); }
    default void gameEvent(net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event, net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.gameevent.GameEvent.Context context) { throw new UnsupportedOperationException(); }
    // Declared on LevelReader on the host, which LevelAccessor extends.
    net.minecraft.core.RegistryAccess registryAccess();
}
