/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.mixin.MixinCompatibilityTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Mixin half of an AOT class transform.
 *
 * <p>A Mixin selector is annotation text, so remapping a class does not touch it. Both AOT
 * compilers used to stop at the class remap, which left every selector on the name the mod was
 * built against and made all of a prepared mod's Mixins fail to apply on a current host. These
 * are the same repairs the transform and batch commands run, in the same post-remap position.
 */
final class AotMixinRepair {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-aot");

    private AotMixinRepair() {}

    /**
     * Repairs the Mixin in {@code classBytes}, which must already be remapped.
     *
     * @return the repaired class, or the input unchanged when it holds no Mixin or a repair fails
     */
    static byte[] apply(MixinCompatibilityTransformer mixinTransformer, byte[] classBytes,
            String className) {
        try {
            byte[] out = mixinTransformer.stripBlocklistedHandlers(classBytes);
            out = mixinTransformer.applyLegacyMemberBridges(out);
            return mixinTransformer.adaptValueIoHandlers(out);
        } catch (Throwable t) {
            // The remapped bytecode is still worth keeping, so this is not fatal.
            LOGGER.warn("Could not repair the Mixin in {} ({}). Keeping its transformed bytecode.",
                    className, t.toString());
            return classBytes;
        }
    }
}
