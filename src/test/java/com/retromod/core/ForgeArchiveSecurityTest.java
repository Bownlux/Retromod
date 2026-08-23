/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgeArchiveSecurityTest {

    @Test
    void incompletePassThroughCannotReplaceExistingMod(@TempDir Path directory)
            throws Exception {
        Path source = Files.writeString(directory.resolve("invalid.jar"), "not a jar");
        Path target = directory.resolve("existing.jar");
        writeJar(target, List.of(new Entry("marker.txt", new byte[] {1, 2, 3})));
        byte[] existing = Files.readAllBytes(target);

        assertThrows(IOException.class,
                () -> ForgeModTransformer.copyArchiveReplacingAtomically(source, target));

        assertArrayEquals(existing, Files.readAllBytes(target));
        try (var children = Files.list(directory)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".existing.jar.")));
        }
    }

    @Test
    void normalizedDuplicateEntryAbortsForgeTransform(@TempDir Path directory)
            throws Exception {
        String metadata = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n"
                + "[[mods]]\nmodId=\"duplicate_fixture\"\nversion=\"1.0\"\n"
                + "[[dependencies.duplicate_fixture]]\nmodId=\"minecraft\"\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\n";
        Path source = directory.resolve("duplicate.jar");
        writeJar(source, List.of(
                new Entry("META-INF/mods.toml", metadata.getBytes(StandardCharsets.UTF_8)),
                new Entry("assets/example//value.txt", new byte[] {1}),
                new Entry("assets/example/value.txt", new byte[] {2})));
        Path output = Files.createDirectory(directory.resolve("output"));

        Path transformed = new ForgeModTransformer("26.2").transformMod(source, output);

        assertNull(transformed);
        assertFalse(Files.exists(output.resolve("duplicate-retromod.jar")));
    }

    @Test
    void cacheDoesNotTrustAnUnstampedArchive(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("cached-retromod.jar");
        writeJar(output, List.of(new Entry("marker.txt", new byte[] {1})));
        String key = "snapshot,forge,26.2,hash";
        Files.writeString(output.resolveSibling(
                output.getFileName() + ForgeModTransformer.CACHE_SIDECAR_SUFFIX), key);
        ForgeModTransformer forge = new ForgeModTransformer("26.2");
        Method current = ForgeModTransformer.class.getDeclaredMethod(
                "isCacheUpToDate", Path.class, String.class);
        current.setAccessible(true);

        assertFalse((boolean) current.invoke(forge, output, key));
    }

    @Test
    void cacheSidecarPublicationReplacesRatherThanFollowsSymlink(@TempDir Path directory)
            throws Exception {
        Path output = directory.resolve("cached-retromod.jar");
        Path sidecar = output.resolveSibling(
                output.getFileName() + ForgeModTransformer.CACHE_SIDECAR_SUFFIX);
        Path victim = Files.writeString(directory.resolve("victim.txt"), "unchanged");
        try {
            Files.createSymbolicLink(sidecar, victim.getFileName());
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("Symbolic links are unavailable: " + e.getMessage());
        }
        ForgeModTransformer forge = new ForgeModTransformer("26.2");
        Method publish = ForgeModTransformer.class.getDeclaredMethod(
                "writeCacheSidecar", Path.class, String.class);
        publish.setAccessible(true);

        publish.invoke(forge, output, "new-key");

        assertFalse(Files.isSymbolicLink(sidecar));
        assertArrayEquals("unchanged".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(victim));
    }

    @Test
    void transformedMembershipRefusesSymlinksAndOversizedManifests(@TempDir Path directory)
            throws Exception {
        Path stamped = directory.resolve("stamped.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(
                ForgeModTransformer.TRANSFORMED_ATTRIBUTE, "true");
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(stamped), manifest)) {
            output.putNextEntry(new JarEntry("marker.txt"));
            output.write(1);
            output.closeEntry();
        }

        Path linked = directory.resolve("linked.jar");
        try {
            Files.createSymbolicLink(linked, stamped.getFileName());
            assertFalse(ForgeModTransformer.isTransformedMod(linked));
        } catch (UnsupportedOperationException | IOException e) {
            // The bounded-manifest assertion still runs where symlinks are unavailable.
        }

        Path oversized = directory.resolve("oversized.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(oversized))) {
            output.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            output.write("Manifest-Version: 1.0\r\nRetromod-Transformed: true\r\nX-Fill: "
                    .getBytes(StandardCharsets.UTF_8));
            output.write(new byte[2 * 1024 * 1024]);
            output.write("\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        assertFalse(ForgeModTransformer.isTransformedMod(oversized));
    }

    @Test
    void publishedManifestValuesCannotCreateAdditionalHeaders(@TempDir Path directory)
            throws Exception {
        Path extracted = Files.createDirectory(directory.resolve("extracted"));
        ForgeModTransformer forge = new ForgeModTransformer("26.2");
        Method stamp = ForgeModTransformer.class.getDeclaredMethod(
                "stampTransformedManifest", Path.class, String.class);
        stamp.setAccessible(true);

        stamp.invoke(forge, extracted, "source.jar\r\nInjected: yes");

        try (var input = Files.newInputStream(
                extracted.resolve("META-INF/MANIFEST.MF"))) {
            Manifest manifest = new Manifest(input);
            assertNull(manifest.getMainAttributes().getValue("Injected"));
            assertEquals("source.jar__Injected: yes", manifest.getMainAttributes()
                    .getValue("Retromod-Original-Jar"));
        }
    }

    private static void writeJar(Path target, List<Entry> entries) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            for (Entry entry : entries) {
                output.putNextEntry(new JarEntry(entry.name()));
                output.write(entry.bytes());
                output.closeEntry();
            }
        }
    }

    private record Entry(String name, byte[] bytes) {
    }
}
