package fixture;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.trading.MerchantOffer;

/** Stands in for the per-mod embedded trade factory interface, with the old getOffer. */
public interface FakeItemListing {
    MerchantOffer getOffer(LivingEntity trader, RandomSource random);
}
