// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.common.util;

// The host order is ALLOW, DEFAULT, DENY, the reverse of Forge 1.20.1's Event.Result.
public enum Result {
    ALLOW,
    DEFAULT,
    DENY;

    public boolean isAllowed() { return this == ALLOW; }

    public boolean isDefault() { return this == DEFAULT; }

    public boolean isDenied() { return this == DENY; }
}
