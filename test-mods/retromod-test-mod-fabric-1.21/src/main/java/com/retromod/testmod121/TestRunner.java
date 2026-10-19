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
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
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
        runPhase("init", suite());
    }

    /** Runs the cases that need a started server, dedicated or integrated. */
    public static void runServerStarted() {
        runPhase("server-started", List.of(new Test39PayloadPacketShapes()));
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
