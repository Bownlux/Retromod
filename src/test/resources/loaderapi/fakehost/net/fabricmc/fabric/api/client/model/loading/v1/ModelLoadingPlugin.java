package net.fabricmc.fabric.api.client.model.loading.v1;

import net.fabricmc.fabric.api.event.Event;

import java.util.ArrayList;
import java.util.List;

public interface ModelLoadingPlugin {
    List<ModelLoadingPlugin> PLUGINS = new ArrayList<>();

    static void register(ModelLoadingPlugin plugin) { PLUGINS.add(plugin); }

    static List<ModelLoadingPlugin> getAll() { return List.copyOf(PLUGINS); }

    void initialize(Context context);

    interface Context {
        Event<ModelModifier.BeforeBakeItem> modifyItemModelBeforeBake();
    }
}
