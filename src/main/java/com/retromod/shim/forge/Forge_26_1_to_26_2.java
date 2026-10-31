/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.common.Mc26_1To26_2CoreMoves;

/**
 * Forge 26.1 to 26.2 shim (shared Mojang-name class moves). Registered ahead of a Forge 26.2
 * release; the host-version gate keeps it inactive until a 26.2 host exists. Forge-API renames
 * follow when that release lands.
 */
public class Forge_26_1_to_26_2 implements VersionShim {

    @Override
    public String getShimName() {
        return "Forge 26.1 to 26.2";
    }

    @Override
    public String getSourceVersion() {
        return "26.1";
    }

    @Override
    public String getTargetVersion() {
        return "26.2";
    }

    @Override
    public String getModLoaderType() {
        return "forge";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        Mc26_1To26_2CoreMoves.register(transformer);

        // Forge 26.2 (65.x) swapped EventBus 6 for EventBus 7: IEventBus is gone, so every old
        // Forge mod dies at construction on getModEventBus()/DeferredRegister.register(bus)/
        // MinecraftForge.EVENT_BUS. Bridge the old idiom onto BusGroup (#85). LexForge-only:
        // on a NeoForge runtime (or the CLI's neoforge target) the Forge->NeoForge migration owns
        // these idioms, and this shim only survives there by services-ordering luck (review).
        if (com.retromod.util.McReflect.isNeoForge()) {
            return;
        }
        ForgeEventBusSynthetics.register(transformer);
        // EventBus 7 split each tick event into Pre and Post records, so old tick listeners and
        // their phase and side reads go through Retromod's rebuilt tick helpers.
        LegacyTickEventBridge.register(transformer);

        // MC 26.x requires the registry id stamped on Block/Item Properties BEFORE construction,
        // and Forge's DeferredRegister.register(String, Supplier) builds the object with no id
        // (RegisterEvent dies "Block/Item id not set" - same domino as NeoForge #87, hit in-game
        // on Macaw's here too). Forge has no id-aware register overload, so the supplier is
        // wrapped and the id comes from the returned RegistryObject.getKey().
        RegistryIdBridgeSynthetic.registerForgeRedirects(transformer);
    }
}
