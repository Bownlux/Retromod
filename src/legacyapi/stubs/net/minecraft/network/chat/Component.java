// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.chat;

public interface Component {
    static MutableComponent literal(String text) { throw new UnsupportedOperationException(); }
    static MutableComponent empty() { throw new UnsupportedOperationException(); }
    Style getStyle();
    default MutableComponent copy() { throw new UnsupportedOperationException(); }
}
