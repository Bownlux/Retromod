// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.permissions;

public interface Permission {
    // A record on the host.
    final class HasCommandLevel implements Permission {
        public HasCommandLevel(PermissionLevel level) { throw new UnsupportedOperationException(); }
    }
}
