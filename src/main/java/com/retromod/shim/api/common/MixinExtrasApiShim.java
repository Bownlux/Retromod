/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/**
 * Maps old bundled MixinExtras (0.0.x - 0.1.x) package paths and annotation names onto the
 * version bundled with modern Fabric Loader / NeoForge / Forge.
 */
public class MixinExtrasApiShim implements AuxiliaryVersionShim {

    @Override
    public String getShimName() {
        return "MixinExtras Legacy -> Bundled MixinExtras Compatibility";
    }

    @Override
    public String getSourceVersion() {
        return "*";
    }

    @Override
    public String getTargetVersion() {
        return "*";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // JiJ copies of MixinExtras are providers, not legacy API consumers. Their bootstrap and
        // service calls must stay inside MixinExtras while mod-owned callers still use the shim.
        transformer.registerRedirectProviderPackage("com/llamalad7/mixinextras/");

        // Modern loaders init MixinExtras themselves, so a mod's own init() call becomes a no-op.
        transformer.registerMethodRedirect(
            "com/llamalad7/mixinextras/MixinExtrasBootstrap",
            "init",
            "()V",
            "com/retromod/shim/api/common/embedded/MixinExtrasShim",
            "noopInit",
            "()V"
        );

        // older package path before restructuring
        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/bootstrap/MixinExtrasBootstrap",
            "com/llamalad7/mixinextras/MixinExtrasBootstrap"
        );

        // Injector annotations moved sub-package between 0.0.x and 0.2+.
        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/injector/WrapOperation",
            "com/llamalad7/mixinextras/injector/wrapoperation/WrapOperation"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/injector/Operation",
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/injector/v2/ModifyExpressionValue",
            "com/llamalad7/mixinextras/injector/ModifyExpressionValue"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/injector/WrapWithCondition",
            "com/llamalad7/mixinextras/injector/wrapmethod/WrapWithCondition"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/injector/v2/ModifyReturnValue",
            "com/llamalad7/mixinextras/injector/ModifyReturnValue"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/injector/v2/ModifyReceiver",
            "com/llamalad7/mixinextras/injector/ModifyReceiver"
        );

        // @Local / LocalRef family: packages unchanged, listed so old refs still resolve.
        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/sugar/Local",
            "com/llamalad7/mixinextras/sugar/Local"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/sugar/ref/LocalRef",
            "com/llamalad7/mixinextras/sugar/ref/LocalRef"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/sugar/ref/LocalIntRef",
            "com/llamalad7/mixinextras/sugar/ref/LocalIntRef"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/sugar/ref/LocalFloatRef",
            "com/llamalad7/mixinextras/sugar/ref/LocalFloatRef"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/sugar/ref/LocalDoubleRef",
            "com/llamalad7/mixinextras/sugar/ref/LocalDoubleRef"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/sugar/ref/LocalBooleanRef",
            "com/llamalad7/mixinextras/sugar/ref/LocalBooleanRef"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/sugar/ref/LocalLongRef",
            "com/llamalad7/mixinextras/sugar/ref/LocalLongRef"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/sugar/Share",
            "com/llamalad7/mixinextras/sugar/Share"
        );

        // Some mods name MixinExtrasPlugin in their mixin config; it still has to resolve.
        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/MixinExtrasPlugin",
            "com/retromod/shim/api/common/embedded/MixinExtrasShim$PluginCompat"
        );

        // service internals some mods reached into
        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/service/MixinExtrasService",
            "com/retromod/shim/api/common/embedded/MixinExtrasShim$ServiceCompat"
        );

        transformer.registerClassRedirect(
            "com/llamalad7/mixinextras/MixinExtrasVersion",
            "com/retromod/shim/api/common/embedded/MixinExtrasShim$VersionCompat"
        );

        transformer.registerMethodRedirect(
            "com/llamalad7/mixinextras/MixinExtrasVersion",
            "getVersion",
            "()Ljava/lang/String;",
            "com/retromod/shim/api/common/embedded/MixinExtrasShim",
            "getVersion",
            "()Ljava/lang/String;"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.common.embedded.MixinExtrasShim"
        };
    }
}
