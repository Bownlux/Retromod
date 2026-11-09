/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fabric retired the {@code fabric} umbrella dependency ID in favour of {@code fabric-api}, so a mod
 * that still depends on the old ID is rejected for a dependency that is actually installed.
 *
 * <p>These fixtures are inline on purpose. The matching exact-jar check in
 * {@code AutoClickyFrameMergeTest} reads a third-party mod that is not in the repository, so it is
 * skipped in CI; this is the coverage that always runs.
 */
class FabricMetadataCompatTest {

    private static JsonObject dependsOf(byte[] json) {
        return JsonParser.parseString(new String(json, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("depends");
    }

    private static byte[] migrate(String json) {
        return FabricMetadataCompat.migrateLegacyFabricApiDependency(
                json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("The retired umbrella ID becomes the current one and keeps its version range")
    void renamesLegacyUmbrellaId() {
        JsonObject depends = dependsOf(migrate("""
                {"id": "example", "depends": {"fabric": ">=0.90.0", "minecraft": ">=1.20.5"}}"""));

        assertFalse(depends.has("fabric"), "the retired ID must not survive");
        assertEquals(">=0.90.0", depends.get("fabric-api").getAsString(),
                "the declared range carries over to the current ID");
        assertEquals(">=1.20.5", depends.get("minecraft").getAsString(),
                "unrelated dependencies must be left alone");
    }

    @Test
    @DisplayName("An explicit current dependency wins when a mod declares both IDs")
    void explicitCurrentIdWins() {
        JsonObject depends = dependsOf(migrate("""
                {"depends": {"fabric": "*", "fabric-api": ">=0.100.0"}}"""));

        assertFalse(depends.has("fabric"));
        assertEquals(">=0.100.0", depends.get("fabric-api").getAsString(),
                "the mod's own explicit range must not be overwritten by the umbrella entry");
    }

    @Test
    @DisplayName("Metadata without the retired ID is returned untouched")
    void leavesUnaffectedMetadataAlone() {
        String json = """
                {"id": "example", "depends": {"fabric-api": "*"}}""";
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), migrate(json),
                "a mod that is already correct must come back byte for byte, so nothing else "
                        + "in its metadata is reformatted");
    }

    @Test
    @DisplayName("Metadata with no dependency block is returned untouched")
    void toleratesMissingDependsBlock() {
        String json = "{\"id\": \"example\"}";
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), migrate(json));
    }

    @Test
    @DisplayName("Unreadable metadata is passed through rather than throwing")
    void malformedMetadataIsNotFatal() {
        // A mod's metadata is not ours to validate, and failing here would take down a load that
        // would otherwise have produced Fabric's own, much clearer error.
        String json = "{ this is not json";
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), migrate(json));

        String wrongShape = "{\"depends\": \"not-an-object\"}";
        assertArrayEquals(wrongShape.getBytes(StandardCharsets.UTF_8), migrate(wrongShape));
    }

    private static List<String> ranges(String json) {
        return FabricMetadataCompat.readMinecraftRanges(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("JSON escapes in the minecraft range are decoded")
    void readsEscapedRangeDecoded() {
        // Gradle's JSON writer escapes '>' and '=', so cloth-config ships ">=26.1-" this way.
        assertEquals(List.of(">=26.1-"), ranges("""
                {"depends": {"minecraft": "\\u003e\\u003d26.1-"}}"""));
    }

    @Test
    @DisplayName("Every alternative of a minecraft array is returned in order")
    void readsEveryArrayAlternative() {
        assertEquals(List.of("1.21", "1.21.1"), ranges("""
                {"depends": {"fabricloader": ">=0.15", "minecraft": ["1.21", "1.21.1"]}}"""));
    }

    @Test
    @DisplayName("Only depends.minecraft counts as the declared range")
    void ignoresMinecraftOutsideDepends() {
        assertEquals(List.of(), ranges("""
                {"breaks": {"minecraft": "<1.20"}, "suggests": {"minecraft": "26.1"}}"""));
        assertEquals(List.of("1.20.1"), ranges("""
                {"breaks": {"minecraft": "<1.20"}, "depends": {"minecraft": "1.20.1"}}"""));
    }

    @Test
    @DisplayName("Missing or unreadable metadata declares no range")
    void missingOrMalformedMetadataDeclaresNothing() {
        assertEquals(List.of(), ranges("{\"id\": \"example\"}"));
        assertEquals(List.of(), ranges("{ this is not json"));
        assertEquals(List.of(), ranges("{\"depends\": [\"minecraft\"]}"));
        assertEquals(List.of(), ranges("{\"depends\": {\"minecraft\": {\"min\": \"1.20\"}}}"));
    }

    @Test
    @DisplayName("Deep metadata is passed through before tree parsing")
    void deeplyNestedMetadataIsLeftUntouched() {
        String nested = "[".repeat(257) + "0" + "]".repeat(257);
        String metadata = "{\"depends\":{\"fabric\":\"*\"},\"padding\":"
                + nested + "}";
        byte[] original = metadata.getBytes(StandardCharsets.UTF_8);

        assertSame(original, FabricMetadataCompat.migrateLegacyFabricApiDependency(original));
    }

    @Test
    @DisplayName("An exact Java version opens above, so a newer Java is accepted")
    void exactJavaVersionOpensAbove() {
        // Cobblemon 1.8.4 declares "java": "21", which Fabric refuses on the Java 25 that 26.x needs.
        assertEquals(">=21", javaOf("{\"depends\":{\"java\":\"21\",\"minecraft\":\"1.21.1\"}}"));
        assertEquals(">=17", javaOf("{\"depends\":{\"java\":\">=17 <22\"}}"));
        assertEquals(">=8", javaOf("{\"depends\":{\"java\":[\"1.8\",\"17.0.2\"]}}"));
        assertEquals(">=21", javaOf("{\"depends\":{\"java\":\"21.x\"}}"));
    }

    @Test
    @DisplayName("An open Java floor or a wildcard is left as written")
    void openJavaVersionIsUnchanged() {
        byte[] open = "{\"depends\":{\"java\":\">=21\"}}".getBytes(StandardCharsets.UTF_8);
        byte[] any = "{\"depends\":{\"java\":\"*\"}}".getBytes(StandardCharsets.UTF_8);

        assertSame(open, FabricMetadataCompat.openJavaDependency(open));
        assertSame(any, FabricMetadataCompat.openJavaDependency(any));
    }

    private static String javaOf(String json) {
        return dependsOf(FabricMetadataCompat.openJavaDependency(json.getBytes(StandardCharsets.UTF_8)))
                .get("java").getAsString();
    }
}
