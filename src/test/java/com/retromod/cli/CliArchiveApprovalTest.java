/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.ArchiveAdmission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The CLI's approval flags can sit anywhere, and each covers only its own kind of jar. */
class CliArchiveApprovalTest {

    @TempDir
    Path tmp;

    @AfterEach
    void reset() {
        ArchiveAdmission.resetForTesting();
    }

    private Path hugeJar() throws IOException {
        Path jar = tmp.resolve("huge.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("data.bin"));
            byte[] data = new byte[5_000];
            new java.util.Random(1).nextBytes(data);
            out.write(data);
            out.closeEntry();
        }
        return jar;
    }

    @Test
    @DisplayName("--allow-huge-jars and --allow-unverified-jars are removed from the arguments and applied")
    void flagsAreStrippedAndApplied() throws IOException {
        ArchiveAdmission.resetForTesting();
        ArchiveAdmission.setLimitsForTesting(100, 1_000);
        ArchiveAdmission.useGameDirectory(tmp);
        Path jar = hugeJar();
        assertEquals(ArchiveAdmission.Verdict.HOLD, ArchiveAdmission.decide(jar).verdict());

        String[] left = RetromodCli.stripArchiveApprovals(
                new String[]{"transform", "--allow-huge-jars", "huge.jar", "--target", "26.3"});

        assertArrayEquals(new String[]{"transform", "huge.jar", "--target", "26.3"}, left);
        assertEquals(ArchiveAdmission.Verdict.ALLOW, ArchiveAdmission.decide(jar).verdict());
    }

    @Test
    @DisplayName("--allow-huge-jars leaves a jar whose check failed held; --allow-unverified-jars lets it through")
    void unverifiedJarsNeedTheirOwnFlag() throws IOException {
        ArchiveAdmission.resetForTesting();
        ArchiveAdmission.setLimitsForTesting(10, 1_000);
        ArchiveAdmission.useGameDirectory(tmp);
        // Two entries with one name fail the header check.
        Path jar = tmp.resolve("dup.jar");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            for (String name : new String[]{"a.txt", "b.txt"}) {
                out.putNextEntry(new java.util.zip.ZipEntry(name));
                out.write("some content here".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        byte[] bytes = Files.readAllBytes(jar);
        Files.write(jar, new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                .replace("b.txt", "a.txt").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        RetromodCli.stripArchiveApprovals(new String[]{"--allow-huge-jars"});
        assertEquals(ArchiveAdmission.Verdict.HOLD, ArchiveAdmission.decide(jar).verdict());

        RetromodCli.stripArchiveApprovals(new String[]{"--allow-unverified-jars"});
        assertEquals(ArchiveAdmission.Verdict.ALLOW, ArchiveAdmission.decide(jar).verdict());
    }
}
