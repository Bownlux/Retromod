// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.material.rule;

import net.minecraft.world.level.block.state.BlockState;

public interface RuleEvaluator {
    BlockState tryApply(int x, int y, int z);
}
