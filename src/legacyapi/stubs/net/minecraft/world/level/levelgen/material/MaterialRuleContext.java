// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.material;

import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.material.condition.ConditionEvaluator;

public final class MaterialRuleContext {
    @SuppressWarnings("rawtypes")
    public Holder getBiome() { throw new UnsupportedOperationException(); }
    @SuppressWarnings("rawtypes")
    public Set<Holder> possibleBiomes() { throw new UnsupportedOperationException(); }

    public abstract static class LazyYCondition implements ConditionEvaluator {
        protected final MaterialRuleContext context;
        protected LazyYCondition(MaterialRuleContext context) { this.context = context; }
        public boolean test() { throw new UnsupportedOperationException(); }
        protected abstract boolean compute();
    }
}
