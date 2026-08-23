/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/**
 * Redirects {@code FabricLoader.getModContainer(String)} to
 * {@link com.retromod.compat.VersionSpoofer#getModContainer(Object, String)} so a mod reads the
 * spoofed version from {@code /retromod/version-spoofs.json} instead of the installed one. The
 * spoof rules live in that JSON resource.
 */
public final class VersionSpoofShim implements AuxiliaryVersionShim {

    @Override
    public String getShimName() {
        return "Runtime Version Spoofer";
    }

    @Override
    public String getSourceVersion() {
        // not tied to an MC version
        return "*";
    }

    @Override
    public String getTargetVersion() {
        return "*";
    }

    @Override
    public String getModLoaderType() {
        // only Fabric code calls getModContainer
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // devirtualize: the receiver becomes the first static arg so VersionSpoofer can wrap the result
        transformer.registerMethodRedirect(
                "net/fabricmc/loader/api/FabricLoader",
                "getModContainer",
                "(Ljava/lang/String;)Ljava/util/Optional;",
                "com/retromod/compat/VersionSpoofer",
                "getModContainer",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/util/Optional;",
                true);
    }
}
