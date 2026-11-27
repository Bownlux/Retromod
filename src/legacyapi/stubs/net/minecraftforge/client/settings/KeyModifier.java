// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.settings;

// An enum on the host.
public abstract class KeyModifier {
    public static final KeyModifier CONTROL = null;
    public static final KeyModifier SHIFT = null;
    public static final KeyModifier ALT = null;
    public static final KeyModifier NONE = null;
    public abstract boolean isActive(IKeyConflictContext conflictContext);
}
