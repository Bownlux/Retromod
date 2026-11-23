// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client;

import com.mojang.serialization.Codec;

public enum GraphicsPreset {
    FAST, FANCY, FABULOUS, CUSTOM;

    public static final Codec<GraphicsPreset> CODEC = null;

    public String getKey() { throw new UnsupportedOperationException(); }
}
