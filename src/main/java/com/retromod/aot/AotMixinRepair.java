/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.mixin.MixinRefmapRepairIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Mixin half of an AOT class transform.
 *
 * <p>A Mixin selector is annotation text, so remapping a class does not touch it. Both AOT
 * compilers used to stop at the class remap, which left every selector on the name the mod was
 * built against and made all of a prepared mod's Mixins fail to apply on a current host. Selector
 * and annotation changes run before the ordinary class remap. Descriptor-based repairs run after
 * it, matching the runtime and batch pipelines.
 */
final class AotMixinRepair {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-aot");

    private AotMixinRepair() {}

    /** Applies annotation and selector repairs before the ordinary class remap. */
    static byte[] applyPreRemap(MixinCompatibilityTransformer mixinTransformer,
            byte[] classBytes, String className) {
        try {
            return mixinTransformer.transformMixinClass(classBytes);
        } catch (Throwable t) {
            LOGGER.warn("Could not prepare the Mixin in {} ({}). Keeping its original bytecode.",
                    className, t.toString());
            return classBytes;
        }
    }

    /**
     * Applies repairs to {@code classBytes}, which must already be remapped.
     *
     * @return the repaired class, or the input unchanged when it holds no Mixin or a repair fails
     */
    static byte[] apply(MixinCompatibilityTransformer mixinTransformer, byte[] classBytes,
            String className) {
        return apply(mixinTransformer, classBytes, className, MixinRefmapRepairIndex.empty());
    }

    /**
     * Repairs one Mixin with the selector facts collected from its owning archive's refmaps.
     * The index must not be shared with an outer or nested archive because identical source
     * selector text can map to different targets in separate bundled libraries.
     */
    static byte[] apply(MixinCompatibilityTransformer mixinTransformer, byte[] classBytes,
            String className, MixinRefmapRepairIndex refmapRepairs) {
        try {
            byte[] out = mixinTransformer.stripBlocklistedHandlers(classBytes);
            return mixinTransformer.applyPostRemapRepairs(out, refmapRepairs);
        } catch (Throwable t) {
            // The remapped bytecode is still worth keeping, so this is not fatal.
            LOGGER.warn("Could not repair the Mixin in {} ({}). Keeping its transformed bytecode.",
                    className, t.toString());
            return classBytes;
        }
    }
}
