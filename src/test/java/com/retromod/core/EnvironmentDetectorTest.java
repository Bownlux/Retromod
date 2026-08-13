/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link EnvironmentDetector#classExists(String)}: the probe
 * must report a class's presence without initializing it (#46). The single-arg
 * {@link Class#forName(String)} runs the target's {@code <clinit>}, which forced
 * Minecraft static initializers to run during mod construction and crashed an
 * otherwise-fine mod.
 */
class EnvironmentDetectorTest {

    // flag kept in a separate class so the test can read it without initializing the probe target
    static final class InitFlag {
        static volatile boolean SIDE_EFFECT_RAN = false;
    }

    // probe target whose <clinit> flips the flag
    @SuppressWarnings("unused")
    static final class ProbeTarget {
        static { InitFlag.SIDE_EFFECT_RAN = true; }
        static final int X = 1;
    }

    static final class ControlFlag {
        static volatile boolean SIDE_EFFECT_RAN = false;
    }

    @SuppressWarnings("unused")
    static final class ControlTarget {
        static { ControlFlag.SIDE_EFFECT_RAN = true; }
        static final int X = 1;
    }

    @Test
    @DisplayName("#46: classExists() finds a present class WITHOUT running its <clinit>")
    void classExistsDoesNotInitialize() {
        assertFalse(InitFlag.SIDE_EFFECT_RAN, "precondition: probe target not yet initialized");

        boolean present = EnvironmentDetector.classExists(
                "com.retromod.core.EnvironmentDetectorTest$ProbeTarget");

        assertTrue(present, "classExists must find a class that is on the classpath");
        assertFalse(InitFlag.SIDE_EFFECT_RAN,
                "classExists must NOT trigger the probed class's static initializer (#46)");
    }

    @Test
    @DisplayName("classExists() returns false for an absent class and never throws")
    void classExistsAbsentClass() {
        assertFalse(EnvironmentDetector.classExists("net.minecraft.server.NoSuchClass_zzz_46"));
    }

    @Test
    @DisplayName("control: plain Class.forName(String) DOES initialize - the probe target's <clinit> is observable")
    void plainForNameInitializesControl() throws Exception {
        // confirms the harness can observe initialization, so the assertion above is not vacuous
        assertFalse(ControlFlag.SIDE_EFFECT_RAN);
        Class.forName("com.retromod.core.EnvironmentDetectorTest$ControlTarget");
        assertTrue(ControlFlag.SIDE_EFFECT_RAN,
                "single-arg Class.forName initializes - the #46 footgun");
    }
}
