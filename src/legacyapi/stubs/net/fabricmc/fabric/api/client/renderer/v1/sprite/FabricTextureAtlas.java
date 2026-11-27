// Compile-only stub of a Fabric API host type on 26.x for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.renderer.v1.sprite;

public interface FabricTextureAtlas {
    default SpriteFinder spriteFinder() { throw new UnsupportedOperationException(); }
}
