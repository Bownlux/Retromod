// Compile-only stub of a NeoForge 1.21.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.common.world.chunk;

import net.minecraft.server.level.ServerLevel;

public interface LoadingValidationCallback {
    void validateTickets(ServerLevel level, TicketHelper ticketHelper);
}
