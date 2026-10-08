/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.fabric.FabricRegistryPolyfill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fabric API still ships {@code FabricRegistryBuilder}. Redirecting it to the datagen
 * {@code RegistrySetBuilder} turned Porting Lib's {@code FabricRegistryBuilder.from(...)} into
 * {@code NoSuchMethodError} during initialization (#249).
 */
class FabricRegistryPolyfillTest {

    private static final String BUILDER = "net/fabricmc/fabric/api/event/registry/FabricRegistryBuilder";

    @AfterEach
    void clearRedirects() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void leavesFabricRegistryBuilderPointingAtFabricApi() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new FabricRegistryPolyfill().registerPolyfills(transformer);

        assertNull(transformer.getClassRedirects().get(BUILDER),
                "FabricRegistryBuilder exists on every Fabric API and must not be redirected");
        assertFalse(Arrays.asList(new FabricRegistryPolyfill().getRemovedClasses()).contains(BUILDER),
                "FabricRegistryBuilder must not be reported as removed");
    }
}
