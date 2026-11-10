/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.thirdparty;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/** Leaves EMI plugins' class names alone, because EMI never moved the classes they use. */
public class EmiBridgePolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "EMI 0.x -> 1.x Bridge";
    }

    @Override
    public String getCategory() {
        return "thirdparty";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{};
    }

    @Override
    public String[] getPolyfillClasses() {
        return new String[]{};
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // Every EMI from 0.1.0 (1.18.2) to 1.1.24 (1.21.1) ships api/render/EmiTexture, api/render/EmiRender and
        // the root EmiPort, while api/EmiDrawContext and runtime/EmiPort exist in none. Moving them broke current
        // plugins that draw with EmiTexture, so nothing is moved.
    }
}
