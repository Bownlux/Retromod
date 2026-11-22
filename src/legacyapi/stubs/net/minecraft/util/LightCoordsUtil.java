// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged.
package net.minecraft.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;

public class LightCoordsUtil {
    private LightCoordsUtil() {}
    public static int getLightCoords(BrightnessGetter brightness, BlockAndLightGetter level, BlockState state, BlockPos pos) { throw new UnsupportedOperationException(); }

    public interface BrightnessGetter {
        BrightnessGetter DEFAULT = null;
        int packedBrightness(BlockAndLightGetter level, BlockPos pos);
    }
}
