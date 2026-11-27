// Compile-only stub of a NeoForge 1.21.1 to 26.3 host type for src/legacyapi; never packaged. 1.21.1 names the id ResourceLocation.
package net.neoforged.neoforge.common.world.chunk;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public final class TicketController {
    public TicketController(Identifier id) { throw new UnsupportedOperationException(); }
    public TicketController(Identifier id, LoadingValidationCallback callback) { throw new UnsupportedOperationException(); }
    public boolean forceChunk(ServerLevel level, BlockPos owner, int chunkX, int chunkZ, boolean add, boolean ticking) { throw new UnsupportedOperationException(); }
    public boolean forceChunk(ServerLevel level, Entity owner, int chunkX, int chunkZ, boolean add, boolean ticking) { throw new UnsupportedOperationException(); }
    public boolean forceChunk(ServerLevel level, UUID owner, int chunkX, int chunkZ, boolean add, boolean ticking) { throw new UnsupportedOperationException(); }
}
