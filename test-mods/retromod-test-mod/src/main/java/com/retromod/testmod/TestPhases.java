/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/** Runs one phase of tests and holds no suites, so a server never loads client-only cases. */
public final class TestPhases {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Test");
    private static final String PREFIX = "[Retromod-Test]";

    private TestPhases() {}

    /** Runs {@code tests} and logs one line per test and a summary under {@code phaseName}. */
    public static void run(String phaseName, List<Test> tests) {
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
