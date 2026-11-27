// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package it.unimi.dsi.fastutil;

public interface Hash {
    interface Strategy<K> {
        int hashCode(K value);
        boolean equals(K first, K second);
    }
}
