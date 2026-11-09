// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.texture;

import net.minecraft.resources.Identifier;

public class TextureManager {
    private TextureManager() {}
    public void register(Identifier location, AbstractTexture texture) { throw new UnsupportedOperationException(); }
    public AbstractTexture getTexture(Identifier location) { throw new UnsupportedOperationException(); }
}
