// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.players;

import java.util.Optional;
import java.util.UUID;

public interface UserNameToIdResolver {
    void add(NameAndId entry);
    Optional<NameAndId> get(String name);
    Optional<NameAndId> get(UUID id);
    void resolveOfflineUsers(boolean offline);
    void save();
}
