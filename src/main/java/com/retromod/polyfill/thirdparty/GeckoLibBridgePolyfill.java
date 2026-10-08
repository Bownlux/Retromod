/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.thirdparty;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Formerly redirected GeckoLib 3.x classes onto GeckoLib 4.x names.
 *
 * <p>GeckoLib 4 is a rewrite rather than a rename, so those redirects produced classes that could
 * not link, and they also rewrote GeckoLib 3's own jar when it was translated (#311). A 3.x mod now
 * keeps its GeckoLib 3 library, which Retromod translates beside it and which loads next to the
 * host's GeckoLib 4 ({@link com.retromod.shim.api.common.GeckoLibApiShim}). The provider stays
 * registered so the provider list is stable, but it redirects nothing.
 */
public class GeckoLibBridgePolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "GeckoLib 3.x -> 4.x Bridge";
    }

    @Override
    public String getCategory() {
        return "thirdparty";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[0];
    }

    @Override
    public String[] getPolyfillClasses() {
        return new String[]{};
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // Intentionally empty: see the class comment.
    }
}
