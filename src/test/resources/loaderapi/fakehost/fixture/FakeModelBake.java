package fixture;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.resources.Identifier;

/** Runs every model loading plugin's item modifiers over one item model, as a model reload does. */
public final class FakeModelBake {
    private FakeModelBake() {}

    public static ItemModel.Unbaked bakeItem(Identifier itemId, ItemModel.Unbaked model) {
        Event<ModelModifier.BeforeBakeItem> event = new Event<>();
        for (ModelLoadingPlugin plugin : ModelLoadingPlugin.getAll()) {
            plugin.initialize(() -> event);
        }
        ItemModel.Unbaked current = model;
        for (ModelModifier.BeforeBakeItem modifier : event.listeners) {
            current = modifier.modifyModelBeforeBake(current, () -> itemId);
        }
        return current;
    }
}
