/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package vectorwing.farmersdelight.common.item;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tier;

/**
 * Compile-only stand-in for Farmer's Delight 1.20.1's knife constructor, for the #302 case. It is
 * not packaged: that case only runs against the real library.
 */
public class KnifeItem extends DiggerItem {
    public KnifeItem(Tier tier, float attackDamage, float attackSpeed, Item.Properties properties) {
        super(attackDamage, attackSpeed, tier, BlockTags.MINEABLE_WITH_AXE, properties);
    }
}
