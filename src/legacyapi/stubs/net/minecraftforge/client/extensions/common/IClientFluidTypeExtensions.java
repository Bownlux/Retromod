// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.extensions.common;

import net.minecraft.resources.Identifier;

public interface IClientFluidTypeExtensions {
    default int getTintColor() { throw new UnsupportedOperationException(); }
    default Identifier getStillTexture() { throw new UnsupportedOperationException(); }
    default Identifier getFlowingTexture() { throw new UnsupportedOperationException(); }
    default Identifier getOverlayTexture() { throw new UnsupportedOperationException(); }
}
