// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage.loot.predicates;

import com.mojang.serialization.Codec;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.world.level.storage.loot.LootContext;

public interface LootItemCondition extends Predicate<LootContext> {
    Codec<Holder<LootItemCondition>> CODEC = null;
}
