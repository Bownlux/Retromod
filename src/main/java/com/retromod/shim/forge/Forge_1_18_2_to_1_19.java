/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/**
 * Forge 1.18.2 to 1.19: TextComponent/TranslatableComponent became the
 * Component.literal/Component.translatable factories, plus a message-send change.
 */
public class Forge_1_18_2_to_1_19 implements VersionShim {

    @Override public String getShimName() { return "Forge 1.18.2 to 1.19"; }
    @Override public String getSourceVersion() { return "1.18.2"; }
    @Override public String getTargetVersion() { return "1.19"; }
    @Override public String getModLoaderType() { return "forge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerConstructorRedirect(
            "net/minecraft/network/chat/TextComponent",
            "(Ljava/lang/String;)V",
            "net/minecraft/network/chat/Component", "literal",
            "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;",
            true
        );
        transformer.registerConstructorRedirect(
            "net/minecraft/network/chat/TranslatableComponent",
            "(Ljava/lang/String;[Ljava/lang/Object;)V",
            "net/minecraft/network/chat/Component", "translatable",
            "(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;",
            true
        );
        // A translation key with no arguments used its own constructor, and it is the common
        // form: one 1.18.2 mod built 103 of its 104 components this way (#311).
        transformer.registerConstructorRedirect(
            "net/minecraft/network/chat/TranslatableComponent",
            "(Ljava/lang/String;)V",
            "net/minecraft/network/chat/Component", "translatable",
            "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;",
            true
        );
        // MutableComponent, not Component: the factories return it, and append or withStyle
        // called on the old class only exist there. Component is an interface without them.
        transformer.registerClassRedirect(
            "net/minecraft/network/chat/TextComponent",
            "net/minecraft/network/chat/MutableComponent"
        );
        transformer.registerClassRedirect(
            "net/minecraft/network/chat/TranslatableComponent",
            "net/minecraft/network/chat/MutableComponent"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/server/level/ServerPlayer", "sendMessage",
            "(Lnet/minecraft/network/chat/Component;Z)V",
            "com/retromod/shim/forge/embedded/ChatShim", "sendMessage",
            "(Ljava/lang/Object;Ljava/lang/Object;Z)V"
        );
        LegacyForge119Bridge.register(transformer);
        com.retromod.shim.common.LegacyMinecraft119Bridge.register(transformer);
        com.retromod.shim.common.LegacyResourceApiBridge.register(transformer);
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
