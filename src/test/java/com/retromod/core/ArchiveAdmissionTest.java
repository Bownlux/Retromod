/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonParser;
import com.retromod.util.ArchiveLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 1 GB, 25 GB, and approval decisions, on small jars: a seam lowers the limits, so a few
 * kilobytes stand for gigabytes and no test writes a large file.
 */
class ArchiveAdmissionTest {

    // A jar over NORMAL is checked; one over VERIFIED needs approval.
    private static final long NORMAL = 4_000;
    private static final long VERIFIED = 40_000;

    @TempDir
    Path game;

    @BeforeEach
    void lowerLimits() {
        ArchiveAdmission.resetForTesting();
        ArchiveAdmission.setLimitsForTesting(NORMAL, VERIFIED);
        ArchiveAdmission.useGameDirectory(game);
        System.clearProperty(ArchiveAdmission.SYSTEM_PROPERTY);
    }

    @AfterEach
    void restore() {
        ArchiveAdmission.resetForTesting();
        ArchiveLimits.resetForTesting();
        System.clearProperty(ArchiveAdmission.SYSTEM_PROPERTY);
    }

    /** A real jar whose entries unpack to about {@code size} bytes. */
    private Path jarOf(String name, long size) throws IOException {
        Path jar = game.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write("{\"id\":\"big\"}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("assets/big/data.bin"));
            // Varied bytes keep the ratio low, as a real resource has it.
            java.util.Random random = new java.util.Random(size);
            byte[] data = new byte[(int) size];
            random.nextBytes(data);
            out.write(data);
            out.closeEntry();
        }
        return jar;
    }

    /** A jar that passes its headers but unpacks past the size it declares. */
    private Path lyingJarOf(String name, int size) throws IOException {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(9, true);
        byte[] content = new byte[size];
        new java.util.Random(7).nextBytes(content);
        deflater.setInput(content);
        deflater.finish();
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) packed.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(content);
        // A local header, the data, a central record that declares half the size, and the end record.
        byte[] nameBytes = "assets/big/data.bin".getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer zip = java.nio.ByteBuffer.allocate(packed.size() + 200)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        zip.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) 8)
                .putInt(0).putInt((int) crc.getValue()).putInt(packed.size()).putInt(size / 2)
                .putShort((short) nameBytes.length).putShort((short) 0).put(nameBytes).put(packed.toByteArray());
        int directory = zip.position();
        zip.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0)
                .putShort((short) 8).putInt(0).putInt((int) crc.getValue()).putInt(packed.size())
                .putInt(size / 2).putShort((short) nameBytes.length).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(0).putInt(0)
                .put(nameBytes);
        int directorySize = zip.position() - directory;
        zip.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) 1)
                .putShort((short) 1).putInt(directorySize).putInt(directory).putShort((short) 0);
        return Files.write(game.resolve(name), java.util.Arrays.copyOf(zip.array(), zip.position()));
    }

    /** A jar whose headers list one file twice, which fails the header check. */
    private Path duplicateJarOf(String name, int size) throws IOException {
        Path jar = game.resolve(name);
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            byte[] data = new byte[size / 2];
            new java.util.Random(3).nextBytes(data);
            out.putNextEntry(new java.util.zip.ZipEntry("a.bin"));
            out.write(data);
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("b.bin"));
            out.write(data);
            out.closeEntry();
        }
        // Rename the second entry to the first in both headers, so the file lists a.bin twice.
        byte[] bytes = Files.readAllBytes(jar);
        String text = new String(bytes, StandardCharsets.ISO_8859_1).replace("b.bin", "a.bin");
        return Files.write(jar, text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void writeConfig(String json) throws IOException {
        Path config = RetromodConfig.configPath(game);
        Files.createDirectories(config.getParent());
        Files.writeString(config, json);
    }

    private static String sha(Path jar) throws IOException {
        return com.retromod.inplace.TranslatedModCache.sha256(jar);
    }

    @Test
    @DisplayName("a jar up to 1 GB is translated as before, with no check, under the 1 GB cap")
    void normalJarIsAllowedWithoutACheck() throws IOException {
        ArchiveAdmission.Decision decision = ArchiveAdmission.decide(jarOf("small.jar", 1_000));

        assertEquals(ArchiveAdmission.Tier.NORMAL, decision.tier());
        assertEquals(ArchiveAdmission.Verdict.ALLOW, decision.verdict());
        assertNull(decision.check(), "a normal jar runs no archive check");
        assertEquals(NORMAL, decision.allowance().maxTotalBytes());
    }

    @Test
    @DisplayName("a jar over 1 GB that passes the check may unpack to its declared size plus room for added files")
    void checkedJarIsAllowedItsDeclaredSize() throws IOException {
        ArchiveAdmission.setLimitsForTesting(NORMAL, VERIFIED, 500);
        ArchiveAdmission.Decision decision = ArchiveAdmission.decide(jarOf("medium.jar", 10_000));

        assertEquals(ArchiveAdmission.Tier.VERIFIED, decision.tier());
        assertEquals(ArchiveAdmission.Verdict.ALLOW, decision.verdict());
        assertTrue(decision.check().passed() && decision.check().contentCounted());
        assertEquals(decision.unpackedBytes() + 500, decision.allowance().maxTotalBytes(),
                "the translation may not unpack far past what the jar declares");

        ArchiveAdmission.setLimitsForTesting(NORMAL, VERIFIED, VERIFIED);
        assertEquals(VERIFIED, ArchiveAdmission.decide(jarOf("other.jar", 10_000)).allowance().maxTotalBytes(),
                "the 25 GB limit still caps the room");
    }

    @Test
    @DisplayName("an approved jar whose check did not pass gets the same declared-size allowance")
    void unverifiedApprovalIsBoundedByTheDeclaredSize() throws IOException {
        ArchiveAdmission.setLimitsForTesting(NORMAL, VERIFIED, 500);
        Path jar = duplicateJarOf("dup.jar", 10_000);
        writeConfig("{\"approved_unverified_jars\": [\"" + sha(jar) + "\"]}");

        ArchiveAdmission.Decision approved = ArchiveAdmission.decide(jar);

        assertEquals(ArchiveAdmission.Verdict.ALLOW, approved.verdict());
        assertEquals(approved.unpackedBytes() + 500, approved.allowance().maxTotalBytes());
    }

    @Test
    @DisplayName("a jar over 1 GB that fails the check is held with a zip-bomb warning, never refused outright")
    void failedCheckIsHeldWithAWarning() throws IOException {
        Path jar = duplicateJarOf("dup.jar", 10_000);

        ArchiveAdmission.Decision decision = ArchiveAdmission.decide(jar);

        assertEquals(ArchiveAdmission.Verdict.HOLD, decision.verdict());
        assertTrue(decision.unverified());
        assertTrue(decision.message().contains("may be a zip bomb")
                && decision.message().contains(ArchiveAdmission.APPROVED_UNVERIFIED_KEY)
                && decision.message().contains("--allow-unverified-jars"), decision.message());
        assertEquals(sha(jar), decision.sha256(), "the hash is what the player approves");
    }

    @Test
    @DisplayName("allow_huge_jars does not cover a failed check; a per-jar approval or the CLI flag does")
    void failedCheckNeedsAnExplicitApproval() throws IOException {
        Path jar = duplicateJarOf("dup.jar", 10_000);

        writeConfig("{\"allow_huge_jars\": true, \"approved_huge_jars\": [\"" + sha(jar) + "\"]}");
        assertEquals(ArchiveAdmission.Verdict.HOLD, ArchiveAdmission.decide(jar).verdict(),
                "neither the switch nor an approval given to a passing jar covers a failed check");

        writeConfig("{\"approved_unverified_jars\": [\"" + sha(jar).toUpperCase() + "\"]}");
        ArchiveAdmission.Decision approved = ArchiveAdmission.decide(jar);
        assertEquals(ArchiveAdmission.Verdict.ALLOW, approved.verdict());
        assertTrue(approved.unverified(), "it stays marked as unverified in the log");

        writeConfig("{}");
        ArchiveAdmission.allowUnverifiedJarsForThisRun();
        assertEquals(ArchiveAdmission.Verdict.ALLOW, ArchiveAdmission.decide(jar).verdict());
    }

    @Test
    @DisplayName("a jar over 25 GB is held until approved, and only its headers are checked meanwhile")
    void hugeJarIsHeldUntilApproved() throws IOException {
        Path jar = jarOf("huge.jar", 50_000);

        ArchiveAdmission.Decision held = ArchiveAdmission.decide(jar);
        assertEquals(ArchiveAdmission.Tier.HUGE, held.tier());
        assertEquals(ArchiveAdmission.Verdict.HOLD, held.verdict());
        assertFalse(held.unverified());
        assertFalse(held.check().contentCounted(), "a held jar is not unpacked");
        assertTrue(held.message().contains(ArchiveAdmission.ALLOW_KEY)
                && held.message().contains(held.sha256()), held.message());

        writeConfig("{\"approved_huge_jars\": [\"" + held.sha256() + "\"]}");
        ArchiveAdmission.Decision approved = ArchiveAdmission.decide(jar);
        assertEquals(ArchiveAdmission.Verdict.ALLOW, approved.verdict());
        assertTrue(approved.check().contentCounted(), "an approved jar is fully checked");
        assertTrue(approved.allowance().maxTotalBytes() >= approved.unpackedBytes());
    }

    @Test
    @DisplayName("the config switch, its system property, and --allow-huge-jars each allow a passing jar over 25 GB")
    void hugeJarSwitches() throws IOException {
        Path jar = jarOf("huge.jar", 50_000);

        writeConfig("{\"allow_huge_jars\": true}");
        assertEquals(ArchiveAdmission.Verdict.ALLOW, ArchiveAdmission.decide(jar).verdict());

        writeConfig("{\"allow_huge_jars\": false}");
        System.setProperty(ArchiveAdmission.SYSTEM_PROPERTY, "true");
        assertEquals(ArchiveAdmission.Verdict.ALLOW, ArchiveAdmission.decide(jar).verdict());
        System.clearProperty(ArchiveAdmission.SYSTEM_PROPERTY);
        assertEquals(ArchiveAdmission.Verdict.HOLD, ArchiveAdmission.decide(jar).verdict());

        ArchiveAdmission.allowHugeJarsForThisRun();
        assertEquals(ArchiveAdmission.Verdict.ALLOW, ArchiveAdmission.decide(jar).verdict());
    }

    @Test
    @DisplayName("a jar over 25 GB approved on passing headers is held again with the warning if its content fails")
    void approvedHugeJarWhoseContentFailsIsHeldAgain() throws IOException {
        Path jar = lyingJarOf("lying.jar", 100_000);

        ArchiveAdmission.Decision first = ArchiveAdmission.decide(jar);
        assertEquals(ArchiveAdmission.Verdict.HOLD, first.verdict());
        assertFalse(first.unverified(), "its headers pass, so the first question has no warning");

        writeConfig("{\"allow_huge_jars\": true, \"approved_huge_jars\": [\"" + first.sha256() + "\"]}");
        ArchiveAdmission.Decision second = ArchiveAdmission.decide(jar);
        assertEquals(ArchiveAdmission.Verdict.HOLD, second.verdict());
        assertTrue(second.unverified(), "the full count failed, so the question now warns");
        assertTrue(second.check().problem().contains("unpacks past"), second.check().problem());
    }

    @Test
    @DisplayName("Allow records the hash in the list that matches what the prompt showed, keeping other settings")
    void approvalIsWrittenToTheRightList() throws IOException {
        writeConfig("{\"write_translated_jars\": true}");
        ArchiveAdmission.Decision huge = ArchiveAdmission.decide(jarOf("huge.jar", 50_000));
        ArchiveAdmission.Decision unverified = ArchiveAdmission.decide(duplicateJarOf("dup.jar", 10_000));

        ArchiveAdmission.approve(huge);
        ArchiveAdmission.approve(unverified);
        ArchiveAdmission.approve(huge);

        var config = JsonParser.parseString(Files.readString(RetromodConfig.configPath(game))).getAsJsonObject();
        assertTrue(config.get("write_translated_jars").getAsBoolean(), "other settings stay");
        assertEquals(1, config.getAsJsonArray(ArchiveAdmission.APPROVED_KEY).size(), "no duplicate hash");
        assertEquals(huge.sha256(), config.getAsJsonArray(ArchiveAdmission.APPROVED_KEY).get(0).getAsString());
        assertEquals(unverified.sha256(),
                config.getAsJsonArray(ArchiveAdmission.APPROVED_UNVERIFIED_KEY).get(0).getAsString());
        assertEquals(ArchiveAdmission.Verdict.ALLOW, ArchiveAdmission.decide(huge.jar()).verdict());
        assertEquals(ArchiveAdmission.Verdict.ALLOW, ArchiveAdmission.decide(unverified.jar()).verdict());
    }

    @Test
    @DisplayName("admit applies the allowance on this thread, and a held jar throws and is listed for the prompt")
    void admitScopesTheAllowance() throws IOException {
        Path medium = jarOf("medium.jar", 10_000);
        try (ArchiveLimits.Scope ignored = ArchiveAdmission.admit(medium)) {
            assertEquals(VERIFIED, ArchiveLimits.maxTotalBytes());
        }
        assertEquals(com.retromod.util.ZipSecurity.DEFAULT_MAX_TOTAL_SIZE, ArchiveLimits.maxTotalBytes(),
                "the scope ends, and readers outside one keep their usual cap");

        Path huge = jarOf("huge.jar", 50_000);
        ArchiveAdmission.HeldException held = assertThrows(ArchiveAdmission.HeldException.class,
                () -> ArchiveAdmission.admit(huge));
        assertEquals(huge, held.decision().jar());
        assertEquals(1, ArchiveAdmission.heldThisLaunch().size());
        assertThrows(ArchiveAdmission.HeldException.class, () -> ArchiveAdmission.admit(huge));
        assertEquals(1, ArchiveAdmission.heldThisLaunch().size(), "a jar is listed once");
    }

    @Test
    @DisplayName("a path that holds the whole jar in memory refuses any jar over its own limit and names the alternative")
    void inMemoryPathRefusesLargeJars() throws IOException {
        Path medium = jarOf("medium.jar", 10_000);

        ArchiveAdmission.RefusedException refused = assertThrows(ArchiveAdmission.RefusedException.class,
                () -> ArchiveAdmission.admitInMemory(medium, Long.MAX_VALUE, "AOT precompilation", "Use transform."));

        assertTrue(refused.getMessage().contains("AOT precompilation")
                && refused.getMessage().contains("medium.jar") && refused.getMessage().endsWith("Use transform."));
        try (ArchiveLimits.Scope ignored = ArchiveAdmission.admitInMemory(jarOf("small.jar", 1_000),
                Long.MAX_VALUE, "AOT", "")) {
            assertEquals(NORMAL, ArchiveLimits.maxTotalBytes());
        }
        ArchiveAdmission.RefusedException overOwnLimit = assertThrows(ArchiveAdmission.RefusedException.class,
                () -> ArchiveAdmission.admitInMemory(jarOf("small2.jar", 1_000), 500, "AOT", ""));
        assertTrue(overOwnLimit.getMessage().contains("up to 500 bytes"), overOwnLimit.getMessage());
    }

    @Test
    @DisplayName("a jar whose declared size crosses the streaming mark streams its resources")
    void largeJarsStreamTheirResources() throws IOException {
        ArchiveLimits.setStreamResourcesFromForTesting(5_000);

        assertFalse(ArchiveAdmission.decide(jarOf("small.jar", 1_000)).allowance().streamResources());
        assertTrue(ArchiveAdmission.decide(jarOf("medium.jar", 10_000)).allowance().streamResources());
    }
}
