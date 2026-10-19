package net.minecraft.world.level.block;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.state.BlockState;

public class Block implements ItemLike {
    private final String name;
    private final BlockState state;
    private final Item item;
    public Block(String name) { this.name = name; this.state = new BlockState(this); this.item = new Item(name + "_item"); }
    public BlockState defaultBlockState() { return state; }
    @Override public Item asItem() { return item; }
    @Override public String toString() { return name; }
}
