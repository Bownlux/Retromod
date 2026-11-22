// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged.
package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

public class SamplerCache {
    public SamplerCache() {}
    public GpuSampler getSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, boolean mipmaps) { throw new UnsupportedOperationException(); }
}
