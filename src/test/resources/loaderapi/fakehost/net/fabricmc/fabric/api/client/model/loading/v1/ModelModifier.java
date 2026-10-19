package net.fabricmc.fabric.api.client.model.loading.v1;

import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.resources.Identifier;

public final class ModelModifier {
    private ModelModifier() {}

    public interface BeforeBakeItem {
        ItemModel.Unbaked modifyModelBeforeBake(ItemModel.Unbaked model, Context context);

        interface Context {
            Identifier itemId();
        }
    }
}
