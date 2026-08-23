/*
 * Retromod test suite. Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.testutil.SignedJarTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignedJarTransformationTest {

    @Test
    void fabricMutationSanitizesWhileNativeCopyStaysByteExact(@TempDir Path directory)
            throws Exception {
        Path oldSource = signedFabricJar(directory, "old-fabric.jar", "1.20.1");
        Path transformed = new FabricModTransformer("26.1").transformMod(
                oldSource, Files.createDirectory(directory.resolve("fabric-output")));

        assertSanitized(transformed);

        Path nativeSource = signedFabricJar(directory, "native-fabric.jar", "26.1");
        assertPassThrough(nativeSource, new FabricModTransformer("26.1").transformMod(
                nativeSource, Files.createDirectory(directory.resolve("fabric-native-output"))));
    }

    @Test
    void quiltMutationSanitizesWhileNativeCopyStaysByteExact(@TempDir Path directory)
            throws Exception {
        Path oldSource = signedQuiltJar(directory, "old-quilt.jar", "1.20.1");
        Path transformed = new QuiltModTransformer("26.1").transformMod(
                oldSource, Files.createDirectory(directory.resolve("quilt-output")));

        assertSanitized(transformed);

        Path nativeSource = signedQuiltJar(directory, "native-quilt.jar", "26.1");
        assertPassThrough(nativeSource, new QuiltModTransformer("26.1").transformMod(
                nativeSource, Files.createDirectory(directory.resolve("quilt-native-output"))));
    }

    @Test
    void forgeMutationSanitizesWhileNativeCopyStaysByteExact(@TempDir Path directory)
            throws Exception {
        Path oldSource = signedForgeJar(directory, "old-forge.jar", "1.20.1");
        Path transformed = new ForgeModTransformer("26.1").transformMod(
                oldSource, Files.createDirectory(directory.resolve("forge-output")));

        assertNotNull(transformed);
        assertSanitized(transformed);

        Path nativeSource = signedForgeJar(directory, "native-forge.jar", "26.1");
        assertPassThrough(nativeSource, new ForgeModTransformer("26.1").transformMod(
                nativeSource, Files.createDirectory(directory.resolve("forge-native-output"))));
    }

    private static void assertSanitized(Path output) throws Exception {
        assertNotNull(output);
        SignedJarTestSupport.verifyEveryEntry(output);
        assertFalse(SignedJarTestSupport.hasSigningMetadata(output));
    }

    private static void assertPassThrough(Path source, Path output) throws Exception {
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(output));
        SignedJarTestSupport.verifyEveryEntry(output);
        assertTrue(SignedJarTestSupport.hasSigningMetadata(output),
                "a byte-for-byte pass-through keeps the still-valid source signature");
    }

    private static Path signedFabricJar(Path directory, String name, String minecraft)
            throws Exception {
        byte[] metadata = ("{\"schemaVersion\":1,\"id\":\"signed_fabric\","
                + "\"version\":\"1.0\",\"depends\":{\"minecraft\":\""
                + minecraft + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        return SignedJarTestSupport.createSignedJar(
                directory, name, SignedJarTestSupport.entries("fabric.mod.json", metadata));
    }

    private static Path signedQuiltJar(Path directory, String name, String minecraft)
            throws Exception {
        byte[] metadata = ("""
                {
                  "schema_version": 1,
                  "quilt_loader": {
                    "group": "retromod.example",
                    "id": "signed_quilt",
                    "depends": [
                      {"id": "quilt_loader", "versions": ">=0.20.0"},
                      {"id": "minecraft", "versions": "%s"}
                    ]
                  }
                }
                """).formatted(minecraft).getBytes(StandardCharsets.UTF_8);
        return SignedJarTestSupport.createSignedJar(
                directory, name, SignedJarTestSupport.entries("quilt.mod.json", metadata));
    }

    private static Path signedForgeJar(Path directory, String name, String minecraft)
            throws Exception {
        byte[] metadata = ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"signed_forge\"\nversion=\"1.0\"\n"
                + "[[dependencies.signed_forge]]\nmodId=\"minecraft\"\n"
                + "versionRange=\"[" + minecraft + "]\"\n")
                .getBytes(StandardCharsets.UTF_8);
        return SignedJarTestSupport.createSignedJar(directory, name,
                SignedJarTestSupport.entries("META-INF/mods.toml", metadata));
    }
}
