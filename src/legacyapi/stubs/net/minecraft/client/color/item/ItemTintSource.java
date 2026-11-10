// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged.
package net.minecraft.client.color.item;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public interface ItemTintSource {
    int calculate(ItemStack stack, ClientLevel level, LivingEntity entity);
    MapCodec<? extends ItemTintSource> type();
}
