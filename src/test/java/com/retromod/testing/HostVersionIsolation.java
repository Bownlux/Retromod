/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testing;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Starts every test class on the default host version and puts it back afterwards.
 *
 * <p>Shims choose what to register from {@code RetromodVersion.TARGET_MC_VERSION}, and many tests
 * set it. A class that did not restore it changed what later classes registered, so the suite
 * passed or failed by class order, and a different order on CI reverted a release push. This
 * runs before a class's own {@code @BeforeAll}, so a class may still choose its version there.
 */
public final class HostVersionIsolation implements BeforeAllCallback, AfterAllCallback {

    private static final String DEFAULT = RetromodVersion.TARGET_MC_VERSION;

    @Override
    public void beforeAll(ExtensionContext context) {
        RetromodVersion.TARGET_MC_VERSION = DEFAULT;
    }

    @Override
    public void afterAll(ExtensionContext context) {
        RetromodVersion.TARGET_MC_VERSION = DEFAULT;
    }
}
