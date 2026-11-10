// Compile-only stub of a Brigadier type the host ships, for src/legacyapi; never packaged.
package com.mojang.brigadier;

public class StringReader implements ImmutableStringReader {
    public StringReader(String string) { throw new UnsupportedOperationException(); }
    public int getCursor() { throw new UnsupportedOperationException(); }
    public void setCursor(int cursor) { throw new UnsupportedOperationException(); }
    public boolean canRead() { throw new UnsupportedOperationException(); }
    public char peek() { throw new UnsupportedOperationException(); }
}
