/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.mapping.IntermediaryToMojangMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sinytra Connector Tier A adoptions (#93):
 *  1. runtime transform cache sidecar (version,loader,sha256) on the Forge/NeoForge path,
 *  2. runtime membership predicate {@code isTransformedMod} + a Forge/NeoForge manifest stamp,
 *  3. {@code comp_} record-component prefix in the string-remap regexes,
 *  4. {@code displayTest} for side-only mods (mods.toml + synthesized-from-mcmod.info),
 *  5. id/version JPMS normalization in the synthesized toml.
 */
class SinytraTierATest {

    // --- #3: comp_ record-component prefix in the string remapper ---

    @Test
    void remapStringHandlesCompPrefix() {
        // comp_XXXX record accessors are keyed like methods in the mapping; the string
        // remapper must rewrite them too (the bytecode path already did). comp_4025 -> name
        // is a real entry in intermediary-to-mojang.tsv.
        IntermediaryToMojangMapper mapper = IntermediaryToMojangMapper.getInstance();
        assertEquals("name", mapper.remapString("comp_4025"),
                "a comp_ record-component accessor is remapped via the method map");
        // surrounding text must be preserved, and method_ + comp_ in one string both remap
        assertEquals("get name here",
                mapper.remapString("get comp_4025 here"),
                "non-comp text around the token is preserved");
        // an unknown comp_ id passes through unchanged (never corrupted, never null)
        assertEquals("comp_999999", mapper.remapString("comp_999999"),
                "an unmapped comp_ id passes through verbatim");
    }

    // --- #4: displayTest for side-only mods (patched mods.toml) ---

    @Test
    void ensureDisplayTestAddsIgnoreAllForClientOnlyToml() {
        // A real mod-level side signal lives in the [[mods]] block, not a dependency block.
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n\n"
                + "[[mods]]\nmodId=\"foo\"\nversion=\"1.0\"\nside=\"CLIENT\"\n";
        String out = ForgeModTransformer.ensureDisplayTest(toml);
        assertTrue(out.contains("displayTest=\"IGNORE_ALL_VERSION\""),
                "client-only mod gets IGNORE_ALL_VERSION: " + out);
    }

    @Test
    void ensureDisplayTestAddsIgnoreServerForServerOnlyToml() {
        String toml = "[[mods]]\nmodId=\"foo\"\nversion=\"1.0\"\nside=\"SERVER\"\n";
        String out = ForgeModTransformer.ensureDisplayTest(toml);
        assertTrue(out.contains("displayTest=\"IGNORE_SERVER_VERSION\""),
                "server-only mod gets IGNORE_SERVER_VERSION: " + out);
    }

    @Test
    void ensureDisplayTestIgnoresDependencyScopedSide() {
        // side= inside a [[dependencies.*]] block describes where a DEPENDENCY is needed,
        // not the mod's own side, so a genuinely both-sided mod with a client-only
        // dependency must not get a displayTest (regression for the dependency-side misread).
        String depOnlySide = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n\n"
                + "[[mods]]\nmodId=\"foo\"\nversion=\"1.0\"\n\n"
                + "[[dependencies.foo]]\nmodId=\"minecraft\"\nside=\"CLIENT\"\n";
        assertFalse(ForgeModTransformer.ensureDisplayTest(depOnlySide).contains("displayTest"),
                "a dependency-scoped side must not trigger a displayTest");
    }

    @Test
    void ensureDisplayTestLeavesBothSidedAndAuthorSuppliedAlone() {
        String both = "[[mods]]\nmodId=\"foo\"\nside=\"BOTH\"\n";
        assertFalse(ForgeModTransformer.ensureDisplayTest(both).contains("displayTest"),
                "a both-sided mod must not get a displayTest");

        String authored = "[[mods]]\nmodId=\"foo\"\nside=\"CLIENT\"\ndisplayTest=\"MATCH_VERSION\"\n";
        assertTrue(ForgeModTransformer.ensureDisplayTest(authored).contains("MATCH_VERSION"),
                "an author-declared displayTest must be preserved");
        assertFalse(ForgeModTransformer.ensureDisplayTest(authored).contains("IGNORE_ALL_VERSION"),
                "we must not override an author displayTest");
    }

    // --- #4 + #5: displayTest + JPMS normalization in the synthesized toml ---

    @Test
    void generatedTomlNormalizesIdVersionAndAddsDisplayTest(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("META-INF"));
        Files.writeString(dir.resolve("mcmod.info"),
                "[{\"modid\":\"my-cool-mod\",\"name\":\"Cool\",\"version\":\"1.2+build3\","
                        + "\"clientSideOnly\":true}]");

        new ForgeModTransformer("26.1").generateTomlFromMcmodInfo(dir);

        String c = Files.readString(dir.resolve("META-INF/mods.toml"));
        assertTrue(c.contains("modId=\"my_cool_mod\""), "modid '-' -> '_' for JPMS: " + c);
        assertTrue(c.contains("version=\"1.2_build3\""), "version '+' -> '_' for JPMS: " + c);
        assertTrue(c.contains("displayTest=\"IGNORE_ALL_VERSION\""),
                "clientSideOnly mcmod.info -> IGNORE_ALL_VERSION: " + c);
        // dependency blocks must use the sanitized id too
        assertTrue(c.contains("[[dependencies.my_cool_mod]]"), "deps use sanitized id: " + c);
    }

    @Test
    void normalizeModIdHandlesReservedWordAndLeadingDigit() {
        assertEquals("package_", ForgeModTransformer.normalizeModId("package"),
                "a reserved Java keyword modid gets a suffix");
        assertEquals("_1mod", ForgeModTransformer.normalizeModId("1mod"),
                "a leading-digit modid is prefixed so the module segment is valid");
        assertEquals("a_b_c", ForgeModTransformer.normalizeModId("a.b-c"),
                "illegal chars map to '_'");
    }

    @Test
    void normalizeVersionFallsBackWhenNotStartingWithDigit() {
        assertEquals("1.0.0", ForgeModTransformer.normalizeVersion("v2.3"),
                "a non-digit-leading version falls back to a valid literal");
        assertEquals("2.3.0_meta", ForgeModTransformer.normalizeVersion("2.3.0+meta"),
                "'+' build metadata becomes '_'");
    }

    // --- #1 + #2: membership predicate + manifest stamp ---

    @Test
    void isTransformedModReadsStampedManifest(@TempDir Path dir) throws Exception {
        Path stamped = dir.resolve("stamped.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Retromod-Transformed", "true");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(stamped), mf)) {
            // empty jar with only the manifest
        }
        assertTrue(ForgeModTransformer.isTransformedMod(stamped),
                "a stamped jar is recognized as Retromod-transformed");
        assertTrue(Retromod.isTransformedMod(stamped),
                "the Retromod delegate agrees");

        Path native_ = dir.resolve("native.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(native_), new Manifest())) {
        }
        assertFalse(ForgeModTransformer.isTransformedMod(native_),
                "a native (unstamped) jar is not flagged (pitfalls #14/#46)");
        assertFalse(ForgeModTransformer.isTransformedMod(dir.resolve("missing.jar")),
                "a missing jar returns false, never throws");
    }

    // --- #1: runtime transform cache round-trip + stamp end-to-end ---

    @Test
    void transformModWritesCacheSidecarAndReusesIt(@TempDir Path work) throws Exception {
        Path source = work.resolve("source-mod.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(source))) {
            jos.putNextEntry(new java.util.zip.ZipEntry("META-INF/mods.toml"));
            jos.write(("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n\n"
                    + "[[mods]]\nmodId=\"cachemod\"\nversion=\"1.0.0\"\n\n"
                    + "[[dependencies.cachemod]]\nmodId=\"minecraft\"\n"
                    + "versionRange=\"[1.20.1,)\"\nside=\"BOTH\"\n").getBytes());
            jos.closeEntry();
        }

        Path outDir = work.resolve("out");
        Files.createDirectories(outDir);
        ForgeModTransformer t = new ForgeModTransformer("26.1");

        Path out1 = t.transformMod(source, outDir);
        assertTrue(out1 != null && Files.exists(out1), "first transform produces an output");
        Path sidecar = out1.resolveSibling(out1.getFileName().toString() + ".retromod-cache");
        assertTrue(Files.exists(sidecar), "a cache sidecar is written next to the output");
        assertTrue(ForgeModTransformer.isTransformedMod(out1),
                "the transformed output carries the Retromod-Transformed stamp");

        // Second run with the same source + version + loader must reuse the cache and
        // return the same output without rebuilding it (the sidecar timestamp is unchanged).
        long stampBefore = Files.getLastModifiedTime(out1).toMillis();
        Path out2 = t.transformMod(source, outDir);
        assertEquals(out1, out2, "the cached output path is returned unchanged");
        assertEquals(stampBefore, Files.getLastModifiedTime(out2).toMillis(),
                "an up-to-date cache means the output jar is not rewritten");
    }

    // --- #1: cache invalidation when the source changes (a stale cache must not be reused) ---

    @Test
    void transformModInvalidatesCacheWhenSourceChanges(@TempDir Path work) throws Exception {
        Path source = work.resolve("source-mod.jar");
        writeCacheModJar(source, "1.0.0");

        Path outDir = work.resolve("out");
        Files.createDirectories(outDir);
        ForgeModTransformer t = new ForgeModTransformer("26.1");

        Path out1 = t.transformMod(source, outDir);
        assertTrue(out1 != null && Files.exists(out1), "first transform produces an output");
        Path sidecar = out1.resolveSibling(out1.getFileName().toString() + ".retromod-cache");
        String keyBefore = Files.readString(sidecar).trim();

        // Rewrite the SAME source path with different bytes: a new sha256, hence a new cache key.
        writeCacheModJar(source, "2.0.0");

        Path out2 = t.transformMod(source, outDir);
        assertTrue(out2 != null && Files.exists(out2), "the changed source is re-transformed");
        String keyAfter = Files.readString(
                out2.resolveSibling(out2.getFileName().toString() + ".retromod-cache")).trim();
        assertNotEquals(keyBefore, keyAfter,
                "a changed source must produce a fresh cache key (no stale reuse)");
    }

    /** Write a minimal Forge test-mod jar whose bytes vary by {@code version}. */
    private static void writeCacheModJar(Path jar, String version) throws Exception {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new java.util.zip.ZipEntry("META-INF/mods.toml"));
            jos.write(("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n\n"
                    + "[[mods]]\nmodId=\"cachemod\"\nversion=\"" + version + "\"\n\n"
                    + "[[dependencies.cachemod]]\nmodId=\"minecraft\"\n"
                    + "versionRange=\"[1.20.1,)\"\nside=\"BOTH\"\n").getBytes());
            jos.closeEntry();
        }
    }
}
