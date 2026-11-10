// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model;

import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.resources.Identifier;

public class ModelManager {
    private ModelManager() {}
    public ItemModel getItemModel(Identifier id) { throw new UnsupportedOperationException(); }
    public BlockStateModelSet getBlockStateModelSet() { throw new UnsupportedOperationException(); }
    public java.util.function.Supplier<net.minecraft.client.model.geom.EntityModelSet> entityModels() { throw new UnsupportedOperationException(); }
}
