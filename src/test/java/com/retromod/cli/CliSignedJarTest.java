/*
 * Retromod test suite. Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.retromod.core.RetromodTransformer;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.testutil.SignedJarTestSupport;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSignedJarTest {

    @Test
    void topLevelTransformRemovesStaleSigningMetadata(@TempDir Path directory)
            throws Exception {
        Path source = signedFabricJar(directory, "cli-input.jar");
        byte[] original = Files.readAllBytes(source);
        Path output = directory.resolve("cli-output.jar");
        Method transform = RetromodCli.class.getDeclaredMethod(
                "transformJar", Path.class, Path.class, RetromodTransformer.class,
                ModVersionInfo.class);
        transform.setAccessible(true);
        ModVersionInfo info = new ModVersionInfo(
                "signed_cli", "1.0", "1.20.1", "fabric", null,
                Set.of(), Set.of(), false);

        transform.invoke(null, source, output, RetromodTransformer.getInstance(), info);

        assertArrayEquals(original, Files.readAllBytes(source));
        assertSanitized(output);
    }

    @Test
    void nestedTransformRemovesStaleSigningMetadata(@TempDir Path directory)
            throws Exception {
        Path source = signedFabricJar(directory, "nested-input.jar");

        byte[] transformed = RetromodCli.transformNestedJar(
                Files.readAllBytes(source), 1);
        Path output = Files.write(directory.resolve("nested-output.jar"), transformed);

        assertSanitized(output);
    }

    @Test
    void metadataPatchRemovesStaleSigningMetadata(@TempDir Path directory)
            throws Exception {
        Path source = signedFabricJar(directory, "metadata-input.jar");

        RetromodCli.patchModMetadata(source);

        assertSanitized(source);
    }

    private static Path signedFabricJar(Path directory, String name) throws Exception {
        byte[] metadata = ("{\"schemaVersion\":1,\"id\":\"signed_cli\","
                + "\"version\":\"1.0\",\"depends\":{\"minecraft\":\"1.20.1\"}}")
                .getBytes(StandardCharsets.UTF_8);
        return SignedJarTestSupport.createSignedJar(
                directory, name, SignedJarTestSupport.entries("fabric.mod.json", metadata));
    }

    private static void assertSanitized(Path output) throws Exception {
        SignedJarTestSupport.verifyEveryEntry(output);
        assertFalse(SignedJarTestSupport.hasSigningMetadata(output));
    }
}
