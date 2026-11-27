// Compile-only stub of a NeoForge 1.21.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.common.world.chunk;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

public class TicketHelper {
    public Map<BlockPos, TicketSet> getBlockTickets() { throw new UnsupportedOperationException(); }
    public Map<UUID, TicketSet> getEntityTickets() { throw new UnsupportedOperationException(); }
    public void removeAllTickets(BlockPos owner) { throw new UnsupportedOperationException(); }
    public void removeAllTickets(UUID owner) { throw new UnsupportedOperationException(); }
    public void removeTicket(BlockPos owner, long chunk, boolean ticking) { throw new UnsupportedOperationException(); }
    public void removeTicket(UUID owner, long chunk, boolean ticking) { throw new UnsupportedOperationException(); }
}
