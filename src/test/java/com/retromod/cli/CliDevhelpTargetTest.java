/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** devhelp reads its target from the version after the jar, from --to, or from the shared target. */
class CliDevhelpTargetTest {

    @Test
    void readsVersionRightAfterTheJar() {
        assertEquals("26.3", RetromodCli.devhelpTarget(
                new String[]{"devhelp", "mod.jar", "26.3"}, "26.1"),
                "the command list shows devhelp <mod.jar> [target]");
    }

    @Test
    void readsToFlag() {
        assertEquals("26.3", RetromodCli.devhelpTarget(
                new String[]{"devhelp", "mod.jar", "--to", "26.3"}, "26.1"));
    }

    @Test
    void keepsSharedTargetWithoutAVersion() {
        assertEquals("26.1", RetromodCli.devhelpTarget(new String[]{"devhelp", "mod.jar"}, "26.1"));
    }

    @Test
    void leavesTargetFlagToMainWithoutWarning() throws Exception {
        String[] target = new String[1];
        String err = LoaderApiTestSupport.captureErr(() -> target[0] = RetromodCli.devhelpTarget(
                new String[]{"devhelp", "mod.jar", "--target", "26.3"}, "26.3"));

        assertEquals("26.3", target[0], "main() already applied --target to the shared target");
        assertEquals("", err, "--target is a known option, so devhelp should not warn about it");
    }

    @Test
    void warnsAboutAnExtraArgument() throws Exception {
        String[] target = new String[1];
        String err = LoaderApiTestSupport.captureErr(() -> target[0] = RetromodCli.devhelpTarget(
                new String[]{"devhelp", "mod.jar", "26.3", "extra"}, "26.1"));

        assertEquals("26.3", target[0]);
        assertTrue(err.contains("extra"), "the warning should name the ignored argument: " + err);
    }

    @Test
    void refusesToWithoutAVersion() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RetromodCli.devhelpTarget(new String[]{"devhelp", "mod.jar", "--to"}, "26.1"));

        assertTrue(error.getMessage().contains("--to"),
                "the error should name the flag that lacks a value: " + error.getMessage());
    }
}
