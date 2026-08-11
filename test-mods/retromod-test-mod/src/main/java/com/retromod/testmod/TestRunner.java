/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod;

import com.retromod.testmod.tests.BasicTests;
import com.retromod.testmod.tests.BlockItemTests;
import com.retromod.testmod.tests.BlockTests;
import com.retromod.testmod.tests.CodecTests;
import com.retromod.testmod.tests.DeferredItemStackTests;
import com.retromod.testmod.tests.EnchantmentTests;
import com.retromod.testmod.tests.EntityTypeTests;
import com.retromod.testmod.tests.EnumTests;
import com.retromod.testmod.tests.GuiTests;
import com.retromod.testmod.tests.IdentifierTests;
import com.retromod.testmod.tests.ItemTests;
import com.retromod.testmod.tests.LoaderTests;
import com.retromod.testmod.tests.MathTests;
import com.retromod.testmod.tests.MiscApiTests;
import com.retromod.testmod.tests.NbtTests;
import com.retromod.testmod.tests.RegistryTests;
import com.retromod.testmod.tests.SoundParticleTests;
import com.retromod.testmod.tests.StatusEffectTests;
import com.retromod.testmod.tests.TagTests;
import com.retromod.testmod.tests.Test05SuperKeyPressed;
import com.retromod.testmod.tests.Test06RenderPipelineBridge;
import com.retromod.testmod.tests.Test07PlayerModelPartsAccessor;
import com.retromod.testmod.tests.Test08AutomaticMixinDescriptor;
import com.retromod.testmod.tests.Test09ConstructorHeuristicIsolation;
import com.retromod.testmod.tests.Test10ExactTargetPrefixCapture;
import com.retromod.testmod.tests.Test11ChatOptionsSuperclass;
import com.retromod.testmod.tests.Test12ClientCommandParserAccessor;
import com.retromod.testmod.tests.Test13WindowHandleShim;
import com.retromod.testmod.tests.TextTests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs each test when the Minecraft APIs it uses are ready. Most tests run
 * during client initialization. Tests that need dynamic registries wait until
 * a world is joined. Each phase logs a run ID so mixed logs remain traceable.
 */
public final class TestRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Test");
    private static final String PREFIX = "[Retromod-Test]";

    private static final List<Test> IMMEDIATE      = buildImmediateSuite();
    private static final List<Test> CLIENT_STARTED = buildClientStartedSuite();
    private static final List<Test> WORLD_JOIN     = buildWorldJoinSuite();

    private TestRunner() {}

    // Test suites

    private static List<Test> buildImmediateSuite() {
        List<Test> all = new ArrayList<>();
        all.addAll(BasicTests.all());
        all.addAll(TextTests.all());
        all.addAll(IdentifierTests.all());
        all.addAll(RegistryTests.all());
        all.addAll(BlockItemTests.all());
        all.addAll(BlockTests.all());
        all.addAll(ItemTests.all());
        all.addAll(EntityTypeTests.all());
        all.addAll(EnumTests.all());
        all.addAll(MathTests.all());
        all.addAll(NbtTests.all());
        all.addAll(SoundParticleTests.all());
        all.addAll(GuiTests.all());
        all.addAll(LoaderTests.all());
        all.addAll(CodecTests.all());
        all.addAll(TagTests.all());
        all.addAll(MiscApiTests.all());
        all.add(new Test05SuperKeyPressed());
        all.add(new Test06RenderPipelineBridge());
        all.add(new Test07PlayerModelPartsAccessor());
        all.add(new Test08AutomaticMixinDescriptor());
        all.add(new Test09ConstructorHeuristicIsolation());
        all.add(new Test10ExactTargetPrefixCapture());
        all.add(new Test11ChatOptionsSuperclass());
        all.add(new Test12ClientCommandParserAccessor());
        return List.copyOf(all);
    }

    private static List<Test> buildClientStartedSuite() {
        // The GLFW window is assigned after client entry points run during Minecraft
        // construction. CLIENT_STARTED is the first harness phase where its native handle
        // can be read safely.
        return List.of(new Test13WindowHandleShim());
    }

    private static List<Test> buildWorldJoinSuite() {
        List<Test> all = new ArrayList<>();
        all.addAll(DeferredItemStackTests.all());
        all.addAll(EnchantmentTests.all());
        all.addAll(StatusEffectTests.all());
        return List.copyOf(all);
    }

    // Lifecycle entry points

    /** {@code onInitializeClient} entry point. Runs all init-phase tests. */
    public static void runImmediate() {
        runPhase("init", IMMEDIATE);
    }

    /** {@code ClientLifecycleEvents.CLIENT_STARTED} entry point. */
    public static void runOnClientStarted() {
        runPhase("client-started", CLIENT_STARTED);
    }

    /** Runs world tests after each join. The suite does not keep state between worlds. */
    public static void runOnWorldJoin() {
        runPhase("world-join", WORLD_JOIN);
    }

    // Shared runner

    private static void runPhase(String phaseName, List<Test> tests) {
        if (tests.isEmpty()) return;

        String runId = UUID.randomUUID().toString().substring(0, 8);
        LOGGER.info("{} RUN_ID={} phase={} ({} tests)", PREFIX, runId, phaseName, tests.size());

        int passed = 0;
        int failed = 0;
        for (int i = 0; i < tests.size(); i++) {
            Test test = tests.get(i);
            int n = i + 1;
            TestResult result;
            try {
                result = test.run();
                if (result == null) {
                    result = TestResult.fail("test returned null");
                }
            } catch (Throwable t) {
                String msg = t.getMessage();
                result = TestResult.fail(t.getClass().getSimpleName()
                        + (msg != null ? ": " + msg : ""));
            }
            if (result.passed()) {
                passed++;
                LOGGER.info("{} [{}] {} ({}): success",
                        PREFIX, phaseName, n, test.description());
            } else {
                failed++;
                LOGGER.warn("{} [{}] {} ({}): fail: {}",
                        PREFIX, phaseName, n, test.description(), result.reason());
            }
        }

        LOGGER.info("{} [{}] SUMMARY: {}/{} passed{}",
                PREFIX, phaseName, passed, tests.size(),
                failed > 0 ? " (" + failed + " failed)" : "");
    }
}
