package net.minecraft.world.item;

public final class ItemStack {
    private final Item item;
    private final int count;
    public ItemStack(Item item) { this(item, 1); }
    public ItemStack(Item item, int count) { this.item = item; this.count = count; }
    public Item getItem() { return item; }
    public int getCount() { return count; }
    public boolean isEmpty() { return item == null || count <= 0; }
}
