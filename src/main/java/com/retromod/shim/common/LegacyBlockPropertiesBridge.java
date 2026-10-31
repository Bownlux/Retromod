/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyBlockProperties;

/** Maps the pre-1.20.5 {@code BlockBehaviour.Properties.copy}, for every loader that reaches 1.20.5. */
public final class LegacyBlockPropertiesBridge {

    static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    static final String COPY_DESC = "(Lnet/minecraft/world/level/block/state/BlockBehaviour;)L" + PROPERTIES + ";";
    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyBlockProperties";

    private LegacyBlockPropertiesBridge() {}

    public static void register(RetromodTransformer t) {
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyBlockProperties.class);
        // 1.20.5 split copy into ofFullCopy and ofLegacyCopy, and the legacy one copies what copy
        // did. The copy then passes through a map color guard; ofLegacyCopy stays a plain call, so
        // the 26.x registry id redirect of it still applies.
        t.registerReturnAdapterRedirect(PROPERTIES, "copy", COPY_DESC, "ofLegacyCopy", COPY_DESC,
                SUPPORT, "guardMapColor", "(Ljava/lang/Object;)Ljava/lang/Object;");
    }
}
