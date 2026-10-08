/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 26.1 moved {@code BlockAndTintGetter} into the client package, so on a dedicated server the first
 * common class that checks a level against it fails to verify. Porting Lib's fluid type did exactly
 * that during Sophisticated Core's initialization (#249).
 */
class DedicatedServerTintGetterShimTest {

    @AfterEach
    void clearRedirects() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void appliesOnlyOnAHostWithTheLightGetterButNoClientTintGetter() {
        assertTrue(DedicatedServerTintGetterShim.appliesToHost(false, true),
                "a 26.x dedicated server has the light getter and no client tint getter");
        assertFalse(DedicatedServerTintGetterShim.appliesToHost(true, true),
                "a client keeps the real type, because block color code still uses it");
        assertFalse(DedicatedServerTintGetterShim.appliesToHost(false, false),
                "an offline transform sees neither class and must not guess");
    }

    @Test
    void isHeldBackBelow26_1() {
        DedicatedServerTintGetterShim shim = new DedicatedServerTintGetterShim();
        assertFalse(ShimRegistry.isAvailableOnHost(shim, "1.21.11"),
                "before 26.1 every level still implements the common BlockAndTintGetter");
        assertTrue(ShimRegistry.isAvailableOnHost(shim, "26.1.2"));
    }

    @Test
    void registersNothingWhenTheHostCannotBeRead() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new DedicatedServerTintGetterShim().registerRedirects(transformer);
        assertNull(transformer.getClassRedirects().get(DedicatedServerTintGetterShim.CLIENT_TINT_GETTER),
                "the unit-test classpath has no Minecraft, so the redirect must stay off");
    }
}
