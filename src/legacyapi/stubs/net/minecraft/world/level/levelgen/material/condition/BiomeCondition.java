// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.material.condition;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;

public final class BiomeCondition implements MaterialCondition {
    @SuppressWarnings("rawtypes")
    public BiomeCondition(HolderSet biomes) { throw new UnsupportedOperationException(); }
    @SuppressWarnings("rawtypes")
    public HolderSet biomes() { throw new UnsupportedOperationException(); }
    public ConditionEvaluator compile(MaterialRuleContext context) { throw new UnsupportedOperationException(); }
    @SuppressWarnings("rawtypes")
    public MapCodec codec() { throw new UnsupportedOperationException(); }
}
