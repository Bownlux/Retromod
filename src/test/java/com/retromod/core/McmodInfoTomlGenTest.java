/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * #79 (snapshot.7): a pre-1.13 Forge mod ships only {@code mcmod.info}; modern Forge/NeoForge needs
 * a {@code mods.toml} or it never scans the mod, so the class-move + ctor bridges never run. This
 * verifies {@code ForgeModTransformer.generateTomlFromMcmodInfo} synthesizes a valid toml.
 */
class McmodInfoTomlGenTest {

    @Test
    void generatesModsTomlFromMcmodInfo(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("META-INF"));
        Files.writeString(dir.resolve("mcmod.info"),
                "[{\"modid\": \"naturescompass\", \"name\": \"Nature's Compass\", "
                        + "\"version\": \"1.8.5\", \"description\": \"Locate biomes.\", "
                        + "\"mcversion\": \"1.12.2\"}]");

        new ForgeModTransformer("26.1").generateTomlFromMcmodInfo(dir);

        Path toml = dir.resolve("META-INF/mods.toml");
        assertTrue(Files.exists(toml), "a mods.toml should be synthesized from mcmod.info");
        String c = Files.readString(toml);
        assertTrue(c.contains("modLoader=\"javafml\""), "valid modLoader: " + c);
        assertTrue(c.contains("modId=\"naturescompass\""), "carries the mod id: " + c);
        assertTrue(c.contains("version=\"1.8.5\""), "carries the version: " + c);
        assertTrue(c.contains("[[dependencies.naturescompass]]"), "has a dependencies block: " + c);
        assertTrue(c.contains("modId=\"minecraft\""), "depends on minecraft: " + c);
        assertTrue(c.contains("versionRange=\"[1,)\""), "minecraft range relaxed: " + c);
    }

    @Test
    void emitsAModsBlockPerEntryForAMultiModJar(@TempDir Path dir) throws Exception {
        // #115: a 1.12.2 jar can declare several modids in one mcmod.info (The Betweenlands ships
        // "thebetweenlands" + "mclib"). The pre-#115 first-entry-only toml left "mclib" undeclared.
        Files.createDirectories(dir.resolve("META-INF"));
        Files.writeString(dir.resolve("mcmod.info"),
                "[{\"modid\": \"thebetweenlands\", \"name\": \"The Betweenlands\", \"version\": \"3.9.9\"},"
                        + "{\"modid\": \"mclib\", \"name\": \"MCLib\", \"version\": \"20.2\"}]");

        new ForgeModTransformer("26.1").generateTomlFromMcmodInfo(dir);

        String c = Files.readString(dir.resolve("META-INF/mods.toml"));
        assertTrue(c.contains("modId=\"thebetweenlands\""), "first modid declared: " + c);
        assertTrue(c.contains("modId=\"mclib\""), "SECOND modid must also be declared: " + c);
        assertTrue(c.contains("version=\"3.9.9\""), "first version: " + c);
        assertTrue(c.contains("version=\"20.2\""), "second version: " + c);
        assertEquals(2, countOccurrences(c, "[[mods]]"), "one [[mods]] block per entry: " + c);
        assertTrue(c.contains("[[dependencies.thebetweenlands]]"), "first deps block: " + c);
        assertTrue(c.contains("[[dependencies.mclib]]"), "second deps block: " + c);
    }

    @Test
    void handlesV2ModListWrapper(@TempDir Path dir) throws Exception {
        // The mcmod.info v2 form wraps entries in {"modListVersion":2,"modList":[...]}.
        Files.createDirectories(dir.resolve("META-INF"));
        Files.writeString(dir.resolve("mcmod.info"),
                "{\"modListVersion\": 2, \"modList\": ["
                        + "{\"modid\": \"aaa\", \"version\": \"1\"},"
                        + "{\"modid\": \"bbb\", \"version\": \"2\"}]}");

        new ForgeModTransformer("26.1").generateTomlFromMcmodInfo(dir);

        String c = Files.readString(dir.resolve("META-INF/mods.toml"));
        assertTrue(c.contains("modId=\"aaa\""), "v2 wrapper: first modid: " + c);
        assertTrue(c.contains("modId=\"bbb\""), "v2 wrapper: second modid: " + c);
        assertEquals(2, countOccurrences(c, "[[mods]]"), "v2 wrapper: two [[mods]] blocks: " + c);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    @Test
    void noopWhenModsTomlAlreadyPresent(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("META-INF"));
        Files.writeString(dir.resolve("mcmod.info"), "[{\"modid\":\"x\",\"version\":\"1\"}]");
        Files.writeString(dir.resolve("META-INF/mods.toml"), "modLoader=\"javafml\"\n# original");

        new ForgeModTransformer("26.1").generateTomlFromMcmodInfo(dir);

        // an existing toml must be left untouched (a 1.13+ mod owns its metadata)
        assertTrue(Files.readString(dir.resolve("META-INF/mods.toml")).contains("# original"),
                "existing mods.toml must not be overwritten");
    }

    @Test
    void noopWhenNoMcmodInfo(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("META-INF"));
        new ForgeModTransformer("26.1").generateTomlFromMcmodInfo(dir);
        assertFalse(Files.exists(dir.resolve("META-INF/mods.toml")), "nothing to generate without mcmod.info");
    }

    @Test
    void maliciousVersionCannotInjectToml(@TempDir Path dir) throws Exception {
        // Security (rc.1 review): the gson parse path DECODES JSON escapes into real quotes and
        // newlines, so a crafted "version" must not break out of the quoted TOML value to inject a
        // spoofed [[mods]] block (mod-identity forgery) or a displayTest that suppresses FML's
        // version handshake. The unescaped-version path was a diff-introduced regression (#115);
        // the old regex reader was immune because [^"]+ stopped at the first quote.
        Files.createDirectories(dir.resolve("META-INF"));
        Files.writeString(dir.resolve("mcmod.info"),
                "[{\"modid\":\"evil\",\"name\":\"Evil\","
                        + "\"version\":\"1.0\\\"\\ndisplayTest=\\\"IGNORE_ALL_VERSION\\\"\\n\\n"
                        + "[[mods]]\\nmodId=\\\"jei\"}]");

        new ForgeModTransformer("26.1").generateTomlFromMcmodInfo(dir);

        String c = Files.readString(dir.resolve("META-INF/mods.toml"));
        assertEquals(1, countOccurrences(c, "[[mods]]"), "an injected second [[mods]] block must not appear: " + c);
        assertFalse(c.contains("modId=\"jei\""), "must not let a crafted version spoof another modid: " + c);
        assertFalse(c.contains("IGNORE_ALL_VERSION"), "must not inject a version-handshake bypass: " + c);
        assertTrue(c.contains("version=\"1.0.0\""), "an unsafe version falls back to the literal: " + c);
    }

    @Test
    void normalizeVersionRejectsTomlBreakingValues() {
        // Legit versions pass through; anything with a TOML/JPMS-illegal char falls back to 1.0.0.
        assertEquals("1.8.5", ForgeModTransformer.normalizeVersion("1.8.5"));
        assertEquals("1.2.3-beta", ForgeModTransformer.normalizeVersion("1.2.3-beta"));
        assertEquals("1.0.0", ForgeModTransformer.normalizeVersion("1.0\"\ndisplayTest=\"x"), "quote+newline rejected");
        assertEquals("1.0.0", ForgeModTransformer.normalizeVersion("1 0"), "space rejected");
        assertEquals("1.0.0", ForgeModTransformer.normalizeVersion("beta"), "non-digit lead rejected");
        assertEquals("1.0.0", ForgeModTransformer.normalizeVersion(""), "empty rejected");
    }

    /** A value-shaped {@code @Mod} class; {@code attached} adds the 1.12.2 bridge's attach call. */
    private static byte[] modClass(String name, String modId, boolean attached) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        AnnotationVisitor mod = cw.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
        mod.visit("value", modId);
        mod.visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        if (attached) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/retromod/embedded/globalxp_1/com/retromod/shim/forge/embedded/LegacyForgeEvents",
                    "attach", "(Ljava/lang/Object;)V", false);
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void generatesModsTomlFromTheModAnnotationWhenMcmodInfoIsMissing(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("bl4ckscor3/mod/globalxp"));
        Files.write(dir.resolve("bl4ckscor3/mod/globalxp/GlobalXP.class"),
                modClass("bl4ckscor3/mod/globalxp/GlobalXP", "globalxp", true));

        new ForgeModTransformer("1.21.1").generateTomlFromMcmodInfo(dir);

        Path toml = dir.resolve("META-INF/mods.toml");
        assertTrue(Files.exists(toml), "1.12.2 allowed a jar without mcmod.info; it still needs a toml");
        String c = Files.readString(toml);
        assertTrue(c.contains("modId=\"globalxp\""), "carries the @Mod id: " + c);
        assertTrue(c.contains("[[dependencies.globalxp]]"), "has a dependencies block: " + c);
    }

    @Test
    void leavesAModernModWithoutMetadataAlone(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("modern"));
        Files.write(dir.resolve("modern/Modern.class"), modClass("modern/Modern", "modern", false));

        new ForgeModTransformer("1.21.1").generateTomlFromMcmodInfo(dir);

        assertFalse(Files.exists(dir.resolve("META-INF/mods.toml")),
                "only @Mod classes the 1.12.2 bridge upgraded may get a generated toml");
    }
}
