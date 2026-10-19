package net.minecraft.world.item;

import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;

public class Item implements ItemLike {
    private final String name;
    public Item(String name) { this.name = name; }
    @Override public Item asItem() { return this; }
    @Override public String toString() { return name; }

    /** The tooltip context 1.20.5 added; {@code of} remembers its level so tests can tell them apart. */
    public interface TooltipContext {
        TooltipContext EMPTY = new TooltipContext() {
            @Override public String toString() { return "empty"; }
        };

        static TooltipContext of(Level level) {
            return new TooltipContext() {
                @Override public String toString() { return "level"; }
            };
        }
    }
}
