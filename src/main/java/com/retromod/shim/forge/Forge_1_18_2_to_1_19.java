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
        transformer.registerClassRedirect(
            "net/minecraft/network/chat/TextComponent",
            "net/minecraft/network/chat/Component"
        );
        transformer.registerClassRedirect(
            "net/minecraft/network/chat/TranslatableComponent",
            "net/minecraft/network/chat/Component"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/server/level/ServerPlayer", "sendMessage",
            "(Lnet/minecraft/network/chat/Component;Z)V",
            "com/retromod/shim/forge/embedded/ChatShim", "sendMessage",
            "(Ljava/lang/Object;Ljava/lang/Object;Z)V"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
