// Compile-only stub of a Fabric API 26.x host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.model.loading.v1;

import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;

public final class ModelModifier {
    private ModelModifier() {}

    @FunctionalInterface
    public interface AfterBakeItem {
        ItemModel modifyModelAfterBake(ItemModel model, Context context);

        interface Context {
            Identifier itemId();
            ItemModel.Unbaked sourceModel();
            ItemModel.BakingContext bakingContext();
            Matrix4fc transformation();
        }
    }
}
