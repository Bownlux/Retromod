/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod;

import com.retromod.testmod.tests.Test35ServerRawChannels;
import com.retromod.testmod.tests.Test36ServerTypedPackets;
import com.retromod.testmod.tests.Test37PacketSenderListeners;
import com.retromod.testmod.tests.Test41LegacyRenderLayersAndColors;
import com.retromod.testmod.tests.Test44LegacyTooltipCallbacks;
import com.retromod.testmod.tests.Test46LibraryStandInAcrossJars;
import com.retromod.testmod.tests.Test47LegacyLootNumbers;
import com.retromod.testmod.tests.Test48LegacyLootEntries;
import com.retromod.testmod.tests.Test49LegacyBlockAndArmorShapes;
import com.retromod.testmod.tests.Test50RenamedMembersAndEitherHolder;
import com.retromod.testmod.tests.Test51LegacyContentRegistries;
import com.retromod.testmod.tests.Test52ContentRegistriesOnHost;
import com.retromod.testmod.tests.Test53LegacyTradesAndBrewing;
import com.retromod.testmod.tests.Test56LegacyCopperConstants;
import com.retromod.testmod.tests.Test54TradesAndBrewingOnHost;
import com.retromod.testmod.tests.Test55LegacyExplosionOnHost;
import com.retromod.testmod.tests.Test57LegacyBufferHelpers;
import com.retromod.testmod.tests.Test58SmallVanillaMembers;
import com.retromod.testmod.tests.Test59SmallVanillaMembersOnServer;
import com.retromod.testmod.tests.Test61UnitCodecs;
import com.retromod.testmod.tests.Test62OldDataAndFinalShapes;
import com.retromod.testmod.tests.Test63IdentifierBufferAndFormatting;
import com.retromod.testmod.tests.Test64ChunkKeysWeightsAndProfessions;
import com.retromod.testmod.tests.Test65OldSurfaceRules;
import com.retromod.testmod.tests.Test66SurfaceRulesOnHost;
import com.retromod.testmod.tests.Test69OldDensityFunctions;
import com.retromod.testmod.tests.Test68SurfaceRuleAccessorOnHost;
import com.retromod.testmod.tests.Test70RegistryTagPairs;
import com.retromod.testmod.tests.Test73EarlyItemStacks;
import com.retromod.testmod.tests.Test75OldEnchantmentRegistry;
import com.retromod.testmod.tests.Test77OldTextJson;
import com.retromod.testmod.tests.Test78OldTextAdapterSubclass;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import java.util.List;

/** Common entry point, so the server cases also run on a dedicated server. */
public class RetromodTestModServer implements ModInitializer {

    @Override
    public void onInitialize() {
        // Test41 registers its block, so it runs before the registries freeze, on both sides.
        TestPhases.run("common-init", List.of(new Test41LegacyRenderLayersAndColors(),
                new Test44LegacyTooltipCallbacks(), new Test46LibraryStandInAcrossJars(),
                new Test47LegacyLootNumbers(), new Test48LegacyLootEntries(),
                new Test49LegacyBlockAndArmorShapes(), new Test50RenamedMembersAndEitherHolder(),
                new Test51LegacyContentRegistries(), new Test53LegacyTradesAndBrewing(),
                new Test56LegacyCopperConstants(), new Test57LegacyBufferHelpers(), new Test58SmallVanillaMembers(),
                new Test61UnitCodecs(), new Test62OldDataAndFinalShapes(),
                new Test63IdentifierBufferAndFormatting(), new Test64ChunkKeysWeightsAndProfessions(),
                new Test65OldSurfaceRules(), new Test69OldDensityFunctions(),
                // Item stacks built here, before a 26.x world binds item components; checked once the server starts.
                new Test73EarlyItemStacks()));
        try {
            // The server cases wait for the server to start, because they need its worlds.
            ServerLifecycleEvents.SERVER_STARTED.register(server -> TestPhases.run("server-started", List.of(
                    new Test35ServerRawChannels(server), new Test36ServerTypedPackets(server),
                    new Test37PacketSenderListeners(), new Test52ContentRegistriesOnHost(server),
                    new Test54TradesAndBrewingOnHost(server), new Test55LegacyExplosionOnHost(server),
                    new Test59SmallVanillaMembersOnServer(server), new Test66SurfaceRulesOnHost(server),
                    new Test68SurfaceRuleAccessorOnHost(server), new Test70RegistryTagPairs(),
                    Test73EarlyItemStacks.checkOnServer(server), new Test75OldEnchantmentRegistry(),
                    new Test77OldTextJson(), new Test78OldTextAdapterSubclass())));
        } catch (NoClassDefFoundError e) {
            System.err.println("[Retromod-Test] Fabric API not installed - server-started test phase skipped ("
                    + e.getMessage() + ")");
        }
    }
}
