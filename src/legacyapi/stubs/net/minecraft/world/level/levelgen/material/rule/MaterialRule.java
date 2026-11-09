// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.material.rule;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;

public interface MaterialRule {
    RuleEvaluator compile(MaterialRuleContext context);
    @SuppressWarnings("rawtypes")
    MapCodec codec();
}
