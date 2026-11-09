/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The aot command checks its output when --verify is passed, as transform and batch do. */
class CliAotVerifyTest {

    private static final Path REPORT =
            Path.of("config", "retromod", "verify-reports", "aot-verify-fixture.jar.txt");

    @TempDir
    Path dir;

    @BeforeEach
    void clearTransformer() throws Exception {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Files.deleteIfExists(REPORT);
    }

    @AfterEach
    void cleanUp() throws Exception {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Files.deleteIfExists(REPORT);
    }

    @Test
    void aotWithVerifyWritesAReport() throws Exception {
        Path input = dir.resolve("aot-verify-fixture.jar");
        Files.write(input, LoaderApiFixtures.fabricJar());

        CliHarness.aot(input, dir.resolve("aot-verify-output.jar"), "26.3", "--verify");

        assertTrue(Files.isRegularFile(REPORT),
                "aot --verify should write a report to " + REPORT.toAbsolutePath());
        String report = Files.readString(REPORT);
        assertTrue(report.contains("aot-verify-fixture.jar"),
                "the report should name the checked mod:\n" + report);
        assertTrue(report.contains("MC 26.3"),
                "the report should name the target version:\n" + report);
    }
}
