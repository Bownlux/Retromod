// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.event;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;

public final class ModifyDefaultComponentsEvent extends net.neoforged.bus.api.Event {
    public void modify(ItemLike item, Initializer initializer) { throw new UnsupportedOperationException(); }
    public void modifyMatching(ItemWithComponentsPredicate predicate, Initializer initializer) { throw new UnsupportedOperationException(); }

    public interface Initializer {
        void run(DataComponentMap.Builder builder, HolderLookup.Provider registries, Item item);
    }

    public interface ItemWithComponentsPredicate {
        boolean test(Item item, DataComponentGetter components);
    }
}
