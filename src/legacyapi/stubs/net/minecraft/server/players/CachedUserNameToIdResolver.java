// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.players;

import java.util.Optional;
import java.util.UUID;

public class CachedUserNameToIdResolver implements UserNameToIdResolver {
    public void add(NameAndId entry) { throw new UnsupportedOperationException(); }
    public Optional<NameAndId> get(String name) { throw new UnsupportedOperationException(); }
    public Optional<NameAndId> get(UUID id) { throw new UnsupportedOperationException(); }
    public void resolveOfflineUsers(boolean offline) { throw new UnsupportedOperationException(); }
    public void save() { throw new UnsupportedOperationException(); }
}
