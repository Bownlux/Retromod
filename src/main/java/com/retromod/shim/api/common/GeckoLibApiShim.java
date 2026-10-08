/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/**
 * Keeps GeckoLib 3 references pointing at GeckoLib 3.
 *
 * <p>GeckoLib 4 is a rewrite, not a rename. {@code IAnimatable}, {@code AnimationData},
 * {@code AnimationEvent}, and the 3.x renderers have no 4.x class with the same methods, so a
 * class redirect produced bytecode that could not link. It also rewrote GeckoLib 3's own jar when
 * that jar was translated, turning the library into a broken copy of 4.x (#311). The two versions
 * use different mod IDs ({@code geckolib3} and {@code geckolib}) and different packages, so they
 * load side by side. A 3.x mod works when its GeckoLib 3 jar goes through Retromod with it.
 *
 * <p>The GeckoLib 4 core package merge is a real move, so {@link GeckoLibCorePackageMoves} still
 * registers from here on every loader.
 */
public class GeckoLibApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "GeckoLib API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "3.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "4.4.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "common";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // GeckoLib 3 names stay as they are: see the class comment. GeckoLib 4.0 to 4.4 core
        // names do move, because 4.5 merged that package; applies only where the host's GeckoLib did.
        GeckoLibCorePackageMoves.register(transformer);
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
