/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.tests;

import com.retromod.testmod121.RetromodTestMod121;
import com.retromod.testmod121.Test;
import com.retromod.testmod121.TestResult;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.jar.Manifest;

/** Checks this jar's {@code Retromod-Loader-Api-Runtime} against what Retromod expects. */
public final class Test34LoaderApiRuntimeMarker implements Test {

    private static final String ATTRIBUTE = "Retromod-Loader-Api-Runtime";

    @Override
    public String description() {
        return "translated jar names the loader API runtime this host expects";
    }

    @Override
    public TestResult run() {
        String[] expected = new String[1];
        if (!expectedIdentity(expected)) {
            return TestResult.fail("Retromod's loader API runtime is not on the class path");
        }
        String found;
        Path jar;
        try {
            ModContainer container = FabricLoader.getInstance()
                    .getModContainer(RetromodTestMod121.MOD_ID).orElseThrow();
            found = ownAttribute(container);
            jar = ownJar(container);
        } catch (Exception e) {
            return TestResult.fail("could not read this mod's manifest: " + e);
        }
        // Retromod writes the attribute exactly when a runtime serves this host, so its own
        // expectation holds on snapshot, rc and stable builds, and on hosts before 26.1.
        if (Objects.equals(expected[0], found)) return TestResult.success();
        // The setting changed this launch: the rebuilt copy replaces this jar when the game exits.
        if (jar != null && replacedOnExit(jar.getFileName().toString())) return TestResult.success();
        return TestResult.fail("this jar carries " + describe(found) + ", but Retromod expects "
                + describe(expected[0]) + " on this host");
    }

    /** Asks the running Retromod by reflection, so this mod needs no compile dependency on it. */
    private static boolean expectedIdentity(String[] out) {
        try {
            Class<?> version = Class.forName("com.retromod.core.RetromodVersion");
            String host = (String) version.getField("TARGET_MC_VERSION").get(null);
            Class<?> runtime = Class.forName("com.retromod.loaderapi.LoaderApiRuntime");
            out[0] = (String) runtime.getMethod("translationIdentity", String.class, String.class)
                    .invoke(null, "fabric", host);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static boolean replacedOnExit(String jarFileName) {
        try {
            Class<?> identity = Class.forName("com.retromod.core.FabricTranslationIdentity");
            return (Boolean) identity.getMethod("isReplacedOnExit", String.class)
                    .invoke(null, jarFileName);
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static String ownAttribute(ModContainer container) throws Exception {
        for (Path root : container.getRootPaths()) {
            Path manifest = root.resolve("META-INF/MANIFEST.MF");
            if (!Files.exists(manifest)) continue;
            try (InputStream in = Files.newInputStream(manifest)) {
                return new Manifest(in).getMainAttributes().getValue(ATTRIBUTE);
            }
        }
        return null;
    }

    /** This mod's jar, or null when Fabric loaded it from somewhere other than a plain path. */
    private static Path ownJar(ModContainer container) {
        try {
            var paths = container.getOrigin().getPaths();
            return paths.isEmpty() ? null : paths.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String describe(String identity) {
        return identity == null ? "no runtime identity" : identity;
    }
}
