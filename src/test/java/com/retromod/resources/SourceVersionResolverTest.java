/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every transform path asks this resolver which Minecraft version a jar was built for, so the
 * version gates of the resource converters agree everywhere. It reads what the metadata declares,
 * falls back to evidence only for the pre-Flattening Forge shapes, and otherwise says unknown.
 */
class SourceVersionResolverTest {

    @Test
    @DisplayName("A Fabric jar resolves to the minecraft version it depends on")
    void fabricDeclaredVersion(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory.resolve("fabric.jar"),
                entries("fabric.mod.json", fabricMetadata("oldfab", "~1.20.1")));

        SourceVersionResolver.Resolution resolution = SourceVersionResolver.inspect(jar);

        assertEquals("1.20.1", resolution.minecraftVersion());
        assertEquals(MigrationContext.Loader.FABRIC, resolution.loader());
        assertEquals(Set.of("oldfab"), resolution.modIds());
    }

    @Test
    @DisplayName("A Fabric jar that accepts any minecraft version is unknown, never old")
    void fabricWildcardIsUnknown(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory.resolve("fabric.jar"), entries(
                "fabric.mod.json", fabricMetadata("anyfab", "*"),
                "mcmod.info", "[{\"modid\":\"anyfab\",\"mcversion\":\"1.12.2\"}]"));

        assertNull(SourceVersionResolver.resolve(jar),
                "Fabric metadata rules out the 1.12 evidence a leftover mcmod.info would give");
    }

    @Test
    @DisplayName("A Quilt jar is read from quilt.mod.json and keeps Quilt as its loader")
    void quiltDeclaredVersion(@TempDir Path directory) throws Exception {
        String quilt = "{\"schema_version\":1,\"quilt_loader\":{\"group\":\"fixture\","
                + "\"id\":\"quiltmod\",\"version\":\"1.0\",\"depends\":[{\"id\":\"minecraft\","
                + "\"versions\":\"~1.20.1\"}]}}";
        Path jar = writeJar(directory.resolve("quilt.jar"), entries(
                "quilt.mod.json", quilt,
                "fabric.mod.json", fabricMetadata("quiltmod", "1.19.2")));

        SourceVersionResolver.Resolution resolution = SourceVersionResolver.inspect(jar);

        assertEquals("1.20.1", resolution.minecraftVersion());
        assertEquals(MigrationContext.Loader.QUILT, resolution.loader());
    }

    @Test
    @DisplayName("mcmod.info in its usual array form gives the declared version")
    void mcmodInfoArrayForm(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory.resolve("array.jar"), entries("mcmod.info",
                "[{\"modid\":\"oldmod\",\"version\":\"1.0\",\"mcversion\":\"1.12.2\"}]"));

        SourceVersionResolver.Resolution resolution = SourceVersionResolver.inspect(jar);

        assertEquals("1.12.2", resolution.minecraftVersion());
        assertEquals(MigrationContext.Loader.FORGE, resolution.loader());
        assertEquals(Set.of("oldmod"), resolution.modIds());
    }

    @Test
    @DisplayName("mcmod.info in its modListVersion 2 object form gives the declared version")
    void mcmodInfoObjectForm(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory.resolve("object.jar"), entries("mcmod.info",
                "{\"modListVersion\":2,\"modList\":[{\"modid\":\"abyssal\",\"version\":\"2.0\","
                        + "\"mcversion\":\"1.12.2\"}]}"));

        SourceVersionResolver.Resolution resolution = SourceVersionResolver.inspect(jar);

        assertEquals("1.12.2", resolution.minecraftVersion());
        assertEquals(Set.of("abyssal"), resolution.modIds());
    }

    @Test
    @DisplayName("A ${mcversion} placeholder falls back to the pre-Flattening evidence")
    void placeholderFallsBackToEvidence(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory.resolve("placeholder.jar"), entries("mcmod.info",
                "[{\"modid\":\"aurorian\",\"version\":\"${version}\",\"mcversion\":\"${mcversion}\"}]"));

        SourceVersionResolver.Resolution resolution = SourceVersionResolver.inspect(jar);

        assertEquals("1.12.2", resolution.minecraftVersion());
        assertEquals(Set.of("aurorian"), resolution.modIds());
    }

    @Test
    @DisplayName("A 1.12 jar with no mcmod.info is read from its @Mod annotation")
    void legacyModAnnotationWithoutMcmodInfo(@TempDir Path directory) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("fixture/GlobalXp.class", legacyModClass("fixture/GlobalXp", "globalxp", "[1.12.2]"));
        Path withVersion = writeJar(directory.resolve("annotated.jar"), entries);

        assertEquals("1.12.2", SourceVersionResolver.resolve(withVersion));

        entries.put("fixture/GlobalXp.class", legacyModClass("fixture/GlobalXp", "globalxp", null));
        Path withoutVersion = writeJar(directory.resolve("annotated-bare.jar"), entries);
        SourceVersionResolver.Resolution bare = SourceVersionResolver.inspect(withoutVersion);
        assertEquals("1.12.2", bare.minecraftVersion(),
                "the 1.12-shaped @Mod is itself evidence when it names no version");
        assertEquals(Set.of("globalxp"), bare.modIds());
    }

    @Test
    @DisplayName("A jar with no metadata resolves to 1.12 only on MCP 1.12 class evidence")
    void mcpClassEvidence(@TempDir Path directory) throws Exception {
        Path legacy = writeJar(directory.resolve("legacy.jar"), Map.of("fixture/Uses.class",
                classReading("fixture/Uses", "net/minecraft/init/Items", "APPLE",
                        "Lnet/minecraft/item/Item;")));
        Path plain = writeJar(directory.resolve("plain.jar"), Map.of("fixture/Uses.class",
                classReading("fixture/Uses", "fixture/Other", "VALUE", "I")));

        assertEquals("1.12.2", SourceVersionResolver.resolve(legacy));
        SourceVersionResolver.Resolution unknown = SourceVersionResolver.inspect(plain);
        assertNull(unknown.minecraftVersion(), "no evidence means unknown, never a guess");
        assertEquals(MigrationContext.Loader.UNKNOWN, unknown.loader());
    }

    @Test
    @DisplayName("A stale neoforge.mods.toml beside Forge metadata does not hide the Forge version")
    void staleNeoForgeToml(@TempDir Path directory) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", bytes(forgeToml("mcreatormod", "[1.20.1,1.21)")));
        entries.put("META-INF/neoforge.mods.toml", bytes(forgeToml("mcreatormod", "[1.21.1,)")));
        entries.put("fixture/Main.class", legacyModClass("fixture/Main", "mcreatormod", null));
        Path jar = writeJar(directory.resolve("stale.jar"), entries);

        SourceVersionResolver.Resolution resolution = SourceVersionResolver.inspect(jar);

        assertEquals("1.20.1", resolution.minecraftVersion());
        assertEquals(MigrationContext.Loader.FORGE, resolution.loader());
    }

    @Test
    @DisplayName("A jar with a toml is never classified as 1.12, even with a leftover mcmod.info")
    void tomlRulesOutPreFlattening(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory.resolve("placeholder-toml.jar"), entries(
                "META-INF/mods.toml", forgeToml("modern", "${minecraft_version_range}"),
                "mcmod.info", "[{\"modid\":\"modern\",\"mcversion\":\"1.12.2\"}]"));

        assertNull(SourceVersionResolver.resolve(jar));
    }

    @Test
    @DisplayName("A nested jar held in memory resolves the same as one on disk")
    void nestedBytesResolveLikeAFile() throws Exception {
        byte[] nested = jar(entries("fabric.mod.json", fabricMetadata("lib", ">=1.18.2")));

        SourceVersionResolver.Resolution resolution = SourceVersionResolver.inspect(nested);

        assertEquals("1.18.2", resolution.minecraftVersion());
        assertEquals(Set.of("lib"), resolution.modIds());
    }

    @Test
    @DisplayName("Only a real version is kept from metadata")
    void numericVersionRefusesPlaceholders() {
        assertEquals("1.20.1", SourceVersionResolver.numericVersion(" 1.20.1 "));
        assertEquals("26.3", SourceVersionResolver.numericVersion("26.3"));
        assertNull(SourceVersionResolver.numericVersion("${mcversion}"));
        assertNull(SourceVersionResolver.numericVersion("*"));
        assertNull(SourceVersionResolver.numericVersion("1"));
        assertNull(SourceVersionResolver.numericVersion(null));
    }
}
