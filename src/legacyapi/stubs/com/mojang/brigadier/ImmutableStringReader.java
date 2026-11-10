// Compile-only stub of a Brigadier type the host ships, for src/legacyapi; never packaged.
package com.mojang.brigadier;

public interface ImmutableStringReader {
    int getCursor();
    boolean canRead();
    char peek();
}
