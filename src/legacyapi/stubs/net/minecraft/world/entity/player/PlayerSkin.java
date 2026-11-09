// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.entity.player;

import net.minecraft.core.ClientAsset;

public final class PlayerSkin {
    public static PlayerSkin insecure(ClientAsset.Texture body, ClientAsset.Texture cape, ClientAsset.Texture elytra, PlayerModelType model) {
        throw new UnsupportedOperationException();
    }
    public PlayerModelType model() { throw new UnsupportedOperationException(); }
}
