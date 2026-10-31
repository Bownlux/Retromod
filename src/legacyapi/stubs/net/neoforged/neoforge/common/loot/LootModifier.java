// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.common.loot;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

public abstract class LootModifier {
    protected LootModifier(Optional<Holder<LootItemCondition>> condition, int priority) { throw new UnsupportedOperationException(); }
}
