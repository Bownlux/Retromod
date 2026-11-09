// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.common.extensions;

import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.model.data.ModelData;

// NeoForge patches Minecraft's BlockGetter to extend it, so every level and chunk region answers it.
public interface IBlockGetterExtension {
    default ModelData getModelData(BlockPos pos) { throw new UnsupportedOperationException(); }
}
