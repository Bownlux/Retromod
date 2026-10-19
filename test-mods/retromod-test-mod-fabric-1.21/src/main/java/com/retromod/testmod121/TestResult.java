/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121;

/** Result of a single {@link Test}. */
public final class TestResult {

    private final boolean passed;
    private final String reason;

    private TestResult(boolean passed, String reason) {
        this.passed = passed;
        this.reason = reason;
    }

    public static TestResult success() {
        return new TestResult(true, null);
    }

    public static TestResult fail(String reason) {
        return new TestResult(false, reason);
    }

    public boolean passed() {
        return passed;
    }

    public String reason() {
        return reason;
    }
}
