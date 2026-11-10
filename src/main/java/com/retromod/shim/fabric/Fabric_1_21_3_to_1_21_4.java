/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.common.Mc1_21_3To1_21_4CoreMoves;

public class Fabric_1_21_3_to_1_21_4 implements VersionShim {
    @Override public String getShimName() { return "Fabric 1.21.3 to 1.21.4"; }
    @Override public String getSourceVersion() { return "1.21.3"; }
    @Override public String getTargetVersion() { return "1.21.4"; }
    @Override public String getModLoaderType() { return "fabric"; }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        Mc1_21_3To1_21_4CoreMoves.register(transformer);
        // BuiltinItemRendererRegistry was removed in 1.21.4.
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/rendering/v1/BuiltinItemRendererRegistry",
            "com/retromod/shim/fabric/embedded/BuiltinItemRendererRegistryStub"
        );
    }
    
    @Override public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.fabric.embedded.BuiltinItemRendererRegistryStub"
        };
    }
}
