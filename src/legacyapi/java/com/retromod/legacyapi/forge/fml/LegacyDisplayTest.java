/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.fml;

import java.util.function.BiPredicate;
import java.util.function.Supplier;
import net.neoforged.fml.IExtensionPoint;

/** Forge's {@code IExtensionPoint.DisplayTest} on NeoForge, which reads a mod's display test from its mods toml instead. */
public final class LegacyDisplayTest implements IExtensionPoint {

    /** The version a server-only mod reports, as Forge's constant of the same name. */
    public static final String IGNORESERVERONLY = "IGNORESERVERONLY";
    public static final Supplier<LegacyDisplayTest> IGNORE_SERVER_VERSION =
            () -> new LegacyDisplayTest(IGNORESERVERONLY, (remote, fromServer) -> true);
    public static final Supplier<LegacyDisplayTest> IGNORE_ALL_VERSION =
            () -> new LegacyDisplayTest(() -> "", (remote, fromServer) -> true);

    private final Supplier<String> suppliedVersion;
    private final BiPredicate<String, Boolean> remoteVersionTest;

    public LegacyDisplayTest(String version, BiPredicate<String, Boolean> remoteVersionTest) {
        this(() -> version, remoteVersionTest);
    }

    public LegacyDisplayTest(Supplier<String> suppliedVersion, BiPredicate<String, Boolean> remoteVersionTest) {
        this.suppliedVersion = suppliedVersion;
        this.remoteVersionTest = remoteVersionTest;
    }

    public Supplier<String> suppliedVersion() {
        return suppliedVersion;
    }

    public BiPredicate<String, Boolean> remoteVersionTest() {
        return remoteVersionTest;
    }

    // Forge's registerDisplayTest overloads on ModLoadingContext and ModContainer. NeoForge has no
    // display test to register, and Retromod writes a side-only mod's test into its mods toml.

    public static void registerDisplayTest(Object owner, LegacyDisplayTest test) {
    }

    public static void registerDisplayTest(Object owner, Supplier<?> test) {
    }

    public static void registerDisplayTest(Object owner, String version, BiPredicate<String, Boolean> test) {
    }

    public static void registerDisplayTest(Object owner, Supplier<String> version, BiPredicate<String, Boolean> test) {
    }
}
