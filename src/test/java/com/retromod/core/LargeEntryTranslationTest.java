/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.ZipSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A file over 128 MiB in a mod jar: one a step reads stops the translation, any other is copied as it is. */
class LargeEntryTranslationTest {

    private static final long OVER_LIMIT = ZipSecurity.DEFAULT_MAX_ENTRY_SIZE + (1 << 20);

    private static final String FABRIC_JSON = """
            {"schemaVersion": 1, "id": "bigmod", "version": "1.0.0", "accessWidener": "bigmod.accesswidener",
             "depends": {"minecraft": "1.20.1"}}""";

    private static final String MODS_TOML = """
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"
            [[mods]]
            modId="bigmod"
            version="1.0.0"
            [[dependencies.bigmod]]
            modId="minecraft"
            mandatory=true
            versionRange="[1.20.1,1.20.2)"
            ordering="NONE"
            side="BOTH"
            """;

    private static final String QUILT_JSON = """
            {"schema_version": 1, "quilt_loader": {"group": "test", "id": "bigmod", "version": "1.0.0",
             "depends": [{"id": "minecraft", "versions": "1.20.1"}]}}""";

    @TempDir
    Path dir;

    private Object savedHeadless;

    // A failed Fabric translation opens Retromod's error dialog when a window can show, so these tests run headless.
    @BeforeEach
    void runHeadless() throws ReflectiveOperationException {
        savedHeadless = headlessFlag().get(null);
        headlessFlag().set(null, Boolean.TRUE);
    }

    @AfterEach
    void forgetDecisions() throws ReflectiveOperationException {
        ArchiveAdmission.resetForTesting();
        headlessFlag().set(null, savedHeadless);
    }

    private static Field headlessFlag() throws NoSuchFieldException {
        Field flag = EnvironmentDetector.class.getDeclaredField("isHeadless");
        flag.setAccessible(true);
        return flag;
    }

    /** A jar of small text entries and one entry of {@link #OVER_LIMIT} zero bytes, which packs small. */
    private Path jarWithLargeEntry(String name, Map<String, String> text, String large) throws IOException {
        Path jar = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> entry : text.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(large));
            byte[] block = new byte[1 << 20];
            for (long written = 0; written < OVER_LIMIT; written += block.length) out.write(block);
            out.closeEntry();
        }
        return jar;
    }

    private static Map<String, String> entries(String... nameAndText) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < nameAndText.length; i += 2) entries.put(nameAndText[i], nameAndText[i + 1]);
        return entries;
    }

    private static void assertCopied(Path output, String name) throws IOException {
        assertNotNull(output, "the translation finished");
        try (ZipFile zip = new ZipFile(output.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            assertNotNull(entry, name + " is in the translated jar");
            assertEquals(OVER_LIMIT, entry.getSize(), name + " keeps its size");
        }
    }

    private static void assertNamesEntry(Throwable failure, String name) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains(name), "the failure names " + name + ": " + messages);
    }

    @Test
    @DisplayName("Fabric: a widener over 128 MiB stops at the per-file limit instead of being read whole")
    void fabricReadFileIsLimited() throws IOException {
        Path jar = jarWithLargeEntry("widener.jar", entries("fabric.mod.json", FABRIC_JSON), "bigmod.accesswidener");
        Path out = Files.createDirectories(dir.resolve("out"));

        IOException failure = assertThrows(IOException.class,
                () -> new FabricModTransformer("1.21.1").transformMod(jar, out));

        assertNamesEntry(failure, "bigmod.accesswidener");
    }

    @Test
    @DisplayName("Fabric: a file over 128 MiB that no step reads is copied unchanged")
    void fabricUnreadFileIsCopied() throws IOException {
        Path jar = jarWithLargeEntry("natives.jar",
                entries("fabric.mod.json", FABRIC_JSON.replace("\"accessWidener\": \"bigmod.accesswidener\",", "")),
                "natives/libbig.so");

        Path output = new FabricModTransformer("1.21.1").transformMod(jar, Files.createDirectories(dir.resolve("out")));

        assertCopied(output, "natives/libbig.so");
    }

    @Test
    @DisplayName("Forge: an access transformer over 128 MiB stops at the per-file limit")
    void forgeReadFileIsLimited() throws IOException {
        Path jar = jarWithLargeEntry("at.jar", entries("META-INF/mods.toml", MODS_TOML),
                "META-INF/accesstransformer.cfg");
        Path out = Files.createDirectories(dir.resolve("out"));

        IOException failure = assertThrows(IOException.class,
                () -> new ForgeModTransformer("1.21.1").translateOrThrow(jar, out, SyntheticLibraries.NONE));

        assertNamesEntry(failure, "META-INF/accesstransformer.cfg");
    }

    @Test
    @DisplayName("Forge: a file over 128 MiB that no step reads is copied unchanged")
    void forgeUnreadFileIsCopied() throws IOException {
        Path jar = jarWithLargeEntry("natives.jar", entries("META-INF/mods.toml", MODS_TOML), "natives/libbig.so");

        Path output = new ForgeModTransformer("1.21.1").translateOrThrow(jar,
                Files.createDirectories(dir.resolve("out")), SyntheticLibraries.NONE);

        assertCopied(output, "natives/libbig.so");
    }

    @Test
    @DisplayName("Quilt: the metadata pass copies a file over 128 MiB instead of unpacking it")
    void quiltMetadataPassCopiesLargeFiles() throws IOException {
        Path jar = jarWithLargeEntry("quilt.jar", entries("quilt.mod.json", QUILT_JSON), "natives/libbig.so");

        new QuiltModTransformer("1.21.1").updateQuiltModJson(jar);

        assertCopied(jar, "natives/libbig.so");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String metadata = new String(zip.getInputStream(zip.getEntry("quilt.mod.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(metadata.contains("1.21.1"), "the metadata pass still ran: " + metadata);
        }
    }
}
