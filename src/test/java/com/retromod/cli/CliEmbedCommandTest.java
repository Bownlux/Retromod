/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.embedder.ApiEmbedder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The embed command says what it wrote, and fails when the jar gained no replacement classes. */
class CliEmbedCommandTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    @Test
    void modWithoutRemovedApisWritesNothingAndSucceeds() {
        int exit = RetromodCli.printEmbedResult(new ApiEmbedder.EmbedResult(null, 0, 0), out, err);

        assertEquals(0, exit);
        assertTrue(printed().contains("No jar was written"), printed());
    }

    @Test
    void jarWithoutReplacementClassesFails() {
        Path output = Path.of("mods", "legacy-retromod.jar");

        int exit = RetromodCli.printEmbedResult(new ApiEmbedder.EmbedResult(output, 1, 0), out, err);

        assertEquals(1, exit, "a jar with no replacement classes is not a success");
        assertTrue(errors().contains("config/retromod/api-archive/"),
                "the error should say where the archive goes: " + errors());
        assertFalse(printed().toLowerCase().contains("added"),
                "nothing was added, so the output must not say so: " + printed());
    }

    @Test
    void jarWithReplacementClassesNamesTheOutputAndCount() {
        Path output = Path.of("mods", "legacy-retromod.jar");

        int exit = RetromodCli.printEmbedResult(new ApiEmbedder.EmbedResult(output, 1, 2), out, err);

        assertEquals(0, exit);
        assertTrue(printed().contains(output.toString()), "the output path should be named: " + printed());
        assertTrue(printed().contains("Replacement classes added: 2"), printed());
    }

    private String printed() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String errors() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }
}
