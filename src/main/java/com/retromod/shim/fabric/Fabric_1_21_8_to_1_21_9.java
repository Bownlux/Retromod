/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 * 
 * Fabric API changes documented at:
 * https://fabricmc.net/2025/09/23/1219.html
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;

/**
 * Fabric 1.21.8 -> 1.21.9: Entity#getWorld rename, Resource Loader rework, removed
 * World Render Events, and the KeyBinding category change.
 */
public class Fabric_1_21_8_to_1_21_9 implements VersionShim {
    
    @Override
    public String getShimName() {
        return "Fabric 1.21.8 to 1.21.9";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.21.8";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.9";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {

        // Entity#getWorld -> getEntityWorld, including common named subclasses.
        transformer.registerMethodRedirect(
            "net/minecraft/entity/Entity", "getWorld", "()Lnet/minecraft/world/World;",
            "net/minecraft/entity/Entity", "getEntityWorld", "()Lnet/minecraft/world/World;"
        );

        // Fabric-distributed bytecode uses intermediary names on every pre-26 host. This alias
        // changed at the same 1.21.8 -> 1.21.9 boundary and has the inherited-owner shape from
        // #179. On 26.x the full intermediary-to-Mojang remap owns this call instead.
        if (!RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)) {
            transformer.registerInheritedMethodRedirect(
                "net/minecraft/class_1297", "method_37908", "()Lnet/minecraft/class_1937;",
                "net/minecraft/class_1297", "method_73183", "()Lnet/minecraft/class_1937;"
            );
        }

        transformer.registerMethodRedirect(
            "net/minecraft/entity/LivingEntity", "getWorld", "()Lnet/minecraft/world/World;",
            "net/minecraft/entity/LivingEntity", "getEntityWorld", "()Lnet/minecraft/world/World;"
        );
        
        transformer.registerMethodRedirect(
            "net/minecraft/entity/player/PlayerEntity", "getWorld", "()Lnet/minecraft/world/World;",
            "net/minecraft/entity/player/PlayerEntity", "getEntityWorld", "()Lnet/minecraft/world/World;"
        );
        
        transformer.registerMethodRedirect(
            "net/minecraft/server/network/ServerPlayerEntity", "getWorld", "()Lnet/minecraft/world/World;",
            "net/minecraft/server/network/ServerPlayerEntity", "getEntityWorld", "()Lnet/minecraft/world/World;"
        );
        
        transformer.registerMethodRedirect(
            "net/minecraft/client/network/ClientPlayerEntity", "getWorld", "()Lnet/minecraft/world/World;",
            "net/minecraft/client/network/ClientPlayerEntity", "getEntityWorld", "()Lnet/minecraft/world/World;"
        );
        
        // Resource Loader v1 was added alongside v0. ResourceManagerHelper remains available,
        // so old listener registrations should stay on Fabric's native compatibility path.
        
        // WorldRenderEvents has no 1.21.9 replacement yet; redirect to a no-op shim.
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents",
            "com/retromod/shim/fabric/embedded/WorldRenderEventsShim"
        );

        // String-category KeyBinding constructor is gone in 1.21.9; the shim builds the
        // Category record from the old String argument.
        transformer.registerConstructorRedirect(
            "net/minecraft/client/option/KeyBinding",
            "(Ljava/lang/String;Lnet/minecraft/client/util/InputUtil$Type;ILjava/lang/String;)V",
            "com/retromod/shim/fabric/embedded/KeyBindingShim", "create",
            "(Ljava/lang/String;Ljava/lang/Object;ILjava/lang/String;)Ljava/lang/Object;"
        );

        // BlockEntityRenderer moved to OrderedRenderCommandQueue; no redirect needed for the common cases.
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.fabric.embedded.WorldRenderEventsShim",
            "com.retromod.shim.fabric.embedded.EntityWorldShim",
            "com.retromod.shim.fabric.embedded.KeyBindingShim"
        };
    }
}
