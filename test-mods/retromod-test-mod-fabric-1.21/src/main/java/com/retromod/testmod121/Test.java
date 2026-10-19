/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121;

/** One observable behavior of the translated mod. */
public interface Test {

    /** Short label for the log line. */
    String description();

    /** Return success or fail. Throwing also counts as fail. */
    TestResult run();
}
