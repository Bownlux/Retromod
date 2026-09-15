/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * The NeoForge 21.9 Transfer API rework needs nothing at the call site.
 *
 * <p>The rework added {@code ResourceHandler} and {@code EnergyHandler}, but it kept
 * {@code IItemHandler} and {@code IEnergyStorage} as compatibility interfaces. Checked against the
 * 26.1 jar: both are still there with the same methods they had on 1.21.1, and each gained an
 * {@code of(...)} adapter for going the other way. {@code IFluidHandler} is still there too, at
 * {@code net.neoforged.neoforge.fluids.capability}.
 *
 * <p>This provider used to redirect all three onto the new types. That was wrong three times over.
 * {@code ResourceHandler} shares no method with {@code IItemHandler}, so a mod class implementing
 * the old interface was rewritten onto one whose methods it does not have. The energy redirect
 * named a class that does not exist at that package. The fluid redirect named a source class that
 * does not exist either, so it never matched anything. Only the loader entry points switch this
 * category off, so the two live redirects landed on the CLI and AOT paths, where they turned
 * working item and energy mods into {@code AbstractMethodError} and {@code NoClassDefFoundError}.
 *
 * <p>The registration stays so the service list is stable, and so the next reader finds the
 * reasoning instead of re-adding the redirects. What a mod can still need here is capability
 * re-registration, which is not something a class redirect can express.
 */
public class NeoForgeTransferPolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "NeoForge Transfer API Rework";
    }

    @Override
    public String getCategory() {
        return "neoforge";
    }

    @Override
    public String[] getRemovedClasses() {
        // Nothing was removed. IItemHandler, IEnergyStorage and IFluidHandler all survive.
        return new String[]{};
    }

    @Override
    public String[] getPolyfillClasses() {
        return new String[]{};
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // Deliberately empty. See the class comment for why a redirect here does harm.
    }
}
