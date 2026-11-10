// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.texture;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.function.Supplier;

public class DynamicTexture extends AbstractTexture {
    public DynamicTexture(Supplier<String> label, NativeImage pixels) { throw new UnsupportedOperationException(); }
    public void upload() { throw new UnsupportedOperationException(); }
    public NativeImage getPixels() { throw new UnsupportedOperationException(); }
}
