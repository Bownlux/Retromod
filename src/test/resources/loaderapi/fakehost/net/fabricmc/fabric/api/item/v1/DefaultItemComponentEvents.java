package net.fabricmc.fabric.api.item.v1;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;

import java.util.function.Consumer;
import java.util.function.Predicate;

public final class DefaultItemComponentEvents {
    public static final Event<ModifyCallback> MODIFY = EventFactory.createArrayBacked(ModifyCallback.class,
            listeners -> context -> { for (ModifyCallback listener : listeners) listener.modify(context); });

    private DefaultItemComponentEvents() {}

    public interface ModifyCallback {
        void modify(ModifyContext context);
    }

    public interface ModifyConsumer {
        void modify(DataComponentMap.Builder builder, Item item);
    }

    public interface ModifyContext {
        void modify(Predicate<Item> items, ModifyConsumer consumer);

        default void modify(Item item, Consumer<DataComponentMap.Builder> builder) {
            modify(candidate -> candidate == item, (components, ignored) -> builder.accept(components));
        }
    }
}
