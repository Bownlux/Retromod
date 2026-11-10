// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model;

import java.util.Map;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

public class ModelBakery {
    private ModelBakery() {}

    // Records on the host.
    public static final class BakingResult {
        private BakingResult() {}
        public MissingModels missingModels() { throw new UnsupportedOperationException(); }
        public Map<BlockState, BlockStateModel> blockStateModels() { throw new UnsupportedOperationException(); }
        public Map<Identifier, ItemModel> itemStackModels() { throw new UnsupportedOperationException(); }
    }

    public static final class MissingModels {
        private MissingModels() {}
        public BlockStateModel block() { throw new UnsupportedOperationException(); }
    }
}
