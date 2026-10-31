package fixture;

import net.fabricmc.fabric.api.item.v1.DefaultItemComponentEvents;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds item components the way 26.3 does at data load, by firing Fabric API's modify event. */
public final class FakeComponents {
    private FakeComponents() {}

    /** Each item's components after every listener ran, by component name. */
    public static Map<String, Map<String, String>> build(List<Item> items) {
        Map<Item, DataComponentMap.Builder> builders = new LinkedHashMap<>();
        for (Item item : items) builders.put(item, new DataComponentMap.Builder());
        DefaultItemComponentEvents.MODIFY.invoker().modify((predicate, consumer) ->
                builders.forEach((item, builder) -> { if (predicate.test(item)) consumer.modify(builder, item); }));
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        builders.forEach((item, builder) -> {
            Map<String, String> components = new LinkedHashMap<>();
            for (Map.Entry<DataComponentType<?>, Object> entry : builder.components.entrySet()) {
                components.put(entry.getKey().toString(), String.valueOf(entry.getValue()));
            }
            out.put(item.toString(), components);
        });
        return out;
    }
}
