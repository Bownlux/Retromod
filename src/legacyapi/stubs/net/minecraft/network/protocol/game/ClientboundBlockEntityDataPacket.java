// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.protocol.game;

import java.util.function.BiFunction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ClientboundBlockEntityDataPacket {
    public static ClientboundBlockEntityDataPacket create(BlockEntity blockEntity,
            BiFunction<BlockEntity, RegistryAccess, CompoundTag> tagFor) { throw new UnsupportedOperationException(); }
}
