// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.item.properties.numeric;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;

public interface RangeSelectItemModelProperty {
    float get(ItemStack stack, ClientLevel level, ItemOwner owner, int seed);
    MapCodec<? extends RangeSelectItemModelProperty> type();
}
