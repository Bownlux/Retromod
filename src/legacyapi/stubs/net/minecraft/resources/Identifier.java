// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.resources;

import com.mojang.serialization.Codec;

public final class Identifier {
    public static final Codec<Identifier> CODEC = null;
    public static Identifier fromNamespaceAndPath(String namespace, String path) { throw new UnsupportedOperationException(); }
    public static Identifier withDefaultNamespace(String path) { throw new UnsupportedOperationException(); }
    public static Identifier parse(String id) { throw new UnsupportedOperationException(); }
    public static Identifier tryParse(String id) { throw new UnsupportedOperationException(); }
    public String getNamespace() { throw new UnsupportedOperationException(); }
    public String getPath() { throw new UnsupportedOperationException(); }
    public Identifier withPrefix(String prefix) { throw new UnsupportedOperationException(); }
    public Identifier withSuffix(String suffix) { throw new UnsupportedOperationException(); }
    public Identifier withPath(String path) { throw new UnsupportedOperationException(); }
    public Identifier withPath(java.util.function.UnaryOperator<String> path) { throw new UnsupportedOperationException(); }
    public String toLanguageKey() { throw new UnsupportedOperationException(); }
    public String toLanguageKey(String prefix) { throw new UnsupportedOperationException(); }
    public String toLanguageKey(String prefix, String suffix) { throw new UnsupportedOperationException(); }
    public String toShortLanguageKey() { throw new UnsupportedOperationException(); }
    public String toDebugFileName() { throw new UnsupportedOperationException(); }
}
