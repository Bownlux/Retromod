/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.sweepfixture;

/**
 * Redirect targets for {@code com.retromod.core.AgentPhantomSweepTest}. They live under
 * {@code com/retromod/shim/} because the Java agent transforms that package as it loads, which is
 * what makes the phantom sweep re-enter the transformer. Nothing may reference them directly, or
 * they would load before the sweep does.
 */
public final class SweepTargets {
    private SweepTargets() {}

    public static final class First { public static void call() {} }
    public static final class Second { public static void call() {} }
    public static final class Third { public static void call() {} }
    public static final class Fourth { public static void call() {} }
}
