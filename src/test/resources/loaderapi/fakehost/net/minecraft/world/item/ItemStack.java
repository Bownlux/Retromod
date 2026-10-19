package net.minecraft.world.item;

public final class ItemStack {
    private final Item item;
    public ItemStack(Item item) { this.item = item; }
    public Item getItem() { return item; }
}
