// Compile-only stub of a Fabric API host type on 26.x for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.renderer.v1.sprite;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public interface SpriteFinder {
    TextureAtlasSprite find(QuadView quad);
    TextureAtlasSprite find(float u, float v);
}
