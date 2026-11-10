/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.surfacebuilder.MaterialRules;

/** A rule source of the mod's own; a subclass, because the old context and evaluated rule types are protected. */
public final class OldSurfaceRuleWrapper extends MaterialRules {

    private OldSurfaceRuleWrapper() {}

    /** The mod's own rule source around the world's rule, as TerraBlender wraps it: the old tree first, then the world's. */
    public static MaterialRules.MaterialRule wrap(MaterialRules.MaterialRule world) {
        MaterialRules.MaterialRule built = Test65OldSurfaceRules.tree;
        return new CountingRule(built == null ? world : MaterialRules.sequence(built, world));
    }

    /** Applies its rule through the old interface and counts each application and block it is asked about. */
    record CountingRule(MaterialRules.MaterialRule inner) implements MaterialRules.MaterialRule {
        @Override
        public MaterialRules.BlockStateRule apply(MaterialRules.MaterialRuleContext context) {
            Test65OldSurfaceRules.APPLIED.incrementAndGet();
            MaterialRules.BlockStateRule applied = inner.apply(context);
            return (x, y, z) -> {
                Test65OldSurfaceRules.TRIED.incrementAndGet();
                return applied.tryApply(x, y, z);
            };
        }

        @Override
        public CodecHolder<? extends MaterialRules.MaterialRule> codec() {
            return null;
        }
    }
}
