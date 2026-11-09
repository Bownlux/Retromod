/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121;

import com.retromod.testmod121.tests.Test34LoaderApiRuntimeMarker;
import com.retromod.testmod121.tests.Test39PayloadPacketShapes;
import com.retromod.testmod121.tests.Test40ClientPayloadPacket;
import com.retromod.testmod121.tests.Test42RenderLayerAndColorShapes;
import com.retromod.testmod121.tests.Test46ScreenShapesFrom121;
import com.retromod.testmod121.tests.Test57LegacyRecipeLookups;
import com.retromod.testmod121.tests.Test58LegacySaveOverrides;
import com.retromod.testmod121.tests.Test81OldSingletonLootEntry;
import com.retromod.testmod121.tests.Test82OldLootReads;
import com.retromod.testmod121.tests.Test85OldPlacementTypes;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Runs the suite on a dedicated server or a client, logging one line per test and a summary. */
public final class TestRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Test");
    private static final String PREFIX = "[Retromod-Test-1.21]";

    private TestRunner() {}

    private static List<Test> suite() {
        // Test numbers continue from the 1.20.1 test mod.
        // Test42 registers a block, so it runs here, before the registries freeze.
        List<Test> tests = new ArrayList<>(List.of(new Test34LoaderApiRuntimeMarker(),
                new Test42RenderLayerAndColorShapes(), new Test46ScreenShapesFrom121()));
        // Client networking classes cannot load on a dedicated server.
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            tests.add(new Test40ClientPayloadPacket());
        }
        return tests;
    }

    /** Runs every init-phase test. */
    public static void runInit() {
        // Test81's loot entry type must be registered before the server reads the loot table that names it.
        try {
            Test81OldSingletonLootEntry.registerType();
        } catch (Throwable t) {
            LOGGER.warn("{} [init] the 1.21.1 single loot entry type did not register: fail: {}: {}", PREFIX,
                    t.getClass().getSimpleName(), t.getMessage());
        }
        // Test85's placement modifier type registers while the mod initializes, as the mods' own do.
        try {
            Test85OldPlacementTypes.registerType();
        } catch (Throwable t) {
            LOGGER.warn("{} [init] the 1.21.1 placement modifier type did not register: fail: {}: {}", PREFIX,
                    t.getClass().getSimpleName(), t.getMessage());
        }
        runPhase("init", suite());
    }

    /** Runs the cases that need a started server, dedicated or integrated. */
    public static void runServerStarted(MinecraftServer server) {
        // Test58 needs the server's registries and a world to build its entity in.
        List<Test> tests = new ArrayList<>(List.of(new Test39PayloadPacketShapes(),
                new Test58LegacySaveOverrides(server), new Test81OldSingletonLootEntry(server), new Test82OldLootReads(server),
                new Test85OldPlacementTypes()));
        // Retromod bridges the old recipe lookups on Fabric from 26.1, once the mod's names are remapped to Mojang's.
        if (!hostVersion().startsWith("1.")) tests.add(new Test57LegacyRecipeLookups(server));
        runPhase("server-started", tests);
    }

    /** The running Minecraft version, such as 1.21.11 or 26.3. */
    private static String hostVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(minecraft -> minecraft.getMetadata().getVersion().getFriendlyString()).orElse("");
    }

    private static void runPhase(String phase, List<Test> tests) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        LOGGER.info("{} RUN_ID={} phase={} ({} tests)", PREFIX, runId, phase, tests.size());
        int passed = 0;
        for (int i = 0; i < tests.size(); i++) {
            Test test = tests.get(i);
            TestResult result;
            try {
                result = test.run();
                if (result == null) result = TestResult.fail("test returned null");
            } catch (Throwable t) {
                result = TestResult.fail(t.getClass().getSimpleName()
                        + (t.getMessage() != null ? ": " + t.getMessage() : ""));
            }
            if (result.passed()) {
                passed++;
                LOGGER.info("{} [{}] {} ({}): success", PREFIX, phase, i + 1, test.description());
            } else {
                LOGGER.warn("{} [{}] {} ({}): fail: {}", PREFIX, phase, i + 1, test.description(),
                        result.reason());
            }
        }
        LOGGER.info("{} [{}] SUMMARY: {}/{} passed{}", PREFIX, phase, passed, tests.size(),
                passed == tests.size() ? "" : " (" + (tests.size() - passed) + " failed)");
    }
}
