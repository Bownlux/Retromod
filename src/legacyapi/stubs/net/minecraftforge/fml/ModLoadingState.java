// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.fml;

// A record on the host; only its type and the state interface matter to rebuilt code.
public final class ModLoadingState implements IModLoadingState {
    private ModLoadingState() {}

    public String name() { throw new UnsupportedOperationException(); }

    public String previous() { throw new UnsupportedOperationException(); }
}
