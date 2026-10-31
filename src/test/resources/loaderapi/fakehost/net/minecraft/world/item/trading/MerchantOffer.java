package net.minecraft.world.item.trading;

import net.minecraft.world.item.ItemStack;

public final class MerchantOffer {
    private final ItemStack costA;
    private final ItemStack costB;
    private final ItemStack result;
    private final int maxUses;
    private final int xp;
    private final float priceMultiplier;
    public MerchantOffer(ItemStack costA, ItemStack costB, ItemStack result, int maxUses, int xp, float priceMultiplier) {
        this.costA = costA; this.costB = costB; this.result = result;
        this.maxUses = maxUses; this.xp = xp; this.priceMultiplier = priceMultiplier;
    }
    public ItemStack getCostA() { return costA; }
    public ItemStack getCostB() { return costB; }
    public ItemStack getResult() { return result; }
    public int getMaxUses() { return maxUses; }
    public int getXp() { return xp; }
    public float getPriceMultiplier() { return priceMultiplier; }
}
