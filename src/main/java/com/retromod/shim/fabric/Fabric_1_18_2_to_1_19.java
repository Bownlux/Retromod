/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Fabric 1.18.2 to 1.19: Text API overhaul, chat message, and GameEvent changes. */
public class Fabric_1_18_2_to_1_19 implements VersionShim {

    @Override public String getShimName() { return "Fabric 1.18.2 to 1.19"; }
    @Override public String getSourceVersion() { return "1.18.2"; }
    @Override public String getTargetVersion() { return "1.19"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // LiteralText/TranslatableText were folded into Text
        transformer.registerClassRedirect(
            "net/minecraft/text/LiteralText",
            "net/minecraft/text/Text"
        );
        transformer.registerClassRedirect(
            "net/minecraft/text/TranslatableText",
            "net/minecraft/text/Text"
        );
        transformer.registerConstructorRedirect(
            "net/minecraft/text/LiteralText",
            "(Ljava/lang/String;)V",
            "net/minecraft/text/Text", "literal",
            "(Ljava/lang/String;)Lnet/minecraft/text/MutableText;",
            true
        );
        transformer.registerConstructorRedirect(
            "net/minecraft/text/TranslatableText",
            "(Ljava/lang/String;[Ljava/lang/Object;)V",
            "net/minecraft/text/Text", "translatable",
            "(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/text/MutableText;",
            true
        );
        transformer.registerMethodRedirect(
            "net/minecraft/server/network/ServerPlayerEntity", "sendMessage",
            "(Lnet/minecraft/text/Text;Z)V",
            "com/retromod/shim/fabric/embedded/ChatShim", "sendMessage",
            "(Ljava/lang/Object;Ljava/lang/Object;Z)V"
        );
        transformer.registerClassRedirect(
            "net/minecraft/world/event/GameEvent$Callback",
            "net/minecraft/world/event/GameEvent$Emitter"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
