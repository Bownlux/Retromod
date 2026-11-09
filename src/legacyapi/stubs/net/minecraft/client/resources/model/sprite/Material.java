// Compile-only stub of a 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model.sprite;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

public final class Material {
    public Material(Identifier sprite) {}
    public Identifier sprite() { throw new UnsupportedOperationException(); }

    public static final class Baked {
        public Baked(TextureAtlasSprite sprite, boolean forceTranslucent) {}
        public TextureAtlasSprite sprite() { throw new UnsupportedOperationException(); }
    }
}
