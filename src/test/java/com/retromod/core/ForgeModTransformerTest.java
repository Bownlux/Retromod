/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@code mods.toml} to {@code neoforge.mods.toml} promotion that lets modern
 * NeoForge accept 1.20.1 (Neo)Forge mods (#42). The file rename and host gate need a
 * NeoForge runtime; the {@code loaderVersion} relax is pure and testable here.
 */
class ForgeModTransformerTest {

    @Test
    @DisplayName("#221: legacy descriptor semicolon is removed from class-only AT targets")
    void normalizesLegacyAccessTransformerClassTarget() {
        String in = "public net.minecraft.world.level.storage.loot.LootContext; # Loot Context\n"
                + "public net.minecraft.world.entity.LivingEntity "
                + "m_21244_(Lnet/minecraft/world/entity/EquipmentSlot;)"
                + "Lnet/minecraft/world/item/ItemStack; # member descriptors stay intact\n";

        String out = ForgeModTransformer.normalizeAccessTransformer(in);

        assertTrue(out.startsWith(
                "public net.minecraft.world.level.storage.loot.LootContext # Loot Context"), out);
        assertTrue(out.contains("EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;"),
                "valid member descriptor semicolons must remain: " + out);
    }

    @Test
    @DisplayName("#221: runtime transform sanitizes an author-provided AccessTransformer")
    void transformModSanitizesAuthorAccessTransformer(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("mna-at-fixture.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(src))) {
            writeEntry(jos, "META-INF/mods.toml",
                    "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                  + "[[mods]]\nmodId=\"mna_fixture\"\n"
                  + "[[dependencies.mna_fixture]]\nmodId=\"minecraft\"\n"
                  + "versionRange=\"[1.20.1,1.21)\"\n");
            writeEntry(jos, "META-INF/accesstransformer.cfg",
                    "public net.minecraft.world.level.storage.loot.LootContext; # Loot Context\n");
        }

        Path out = new ForgeModTransformer("26.1")
                .transformMod(src, Files.createDirectory(tmp.resolve("out")));

        assertNotNull(out, "transformMod should produce an output jar");
        assertEquals(
                "public net.minecraft.world.level.storage.loot.LootContext # Loot Context\n",
                readEntry(out, "META-INF/accesstransformer.cfg"));
    }

    @Test
    @DisplayName("#273: the in-game path relaxes a sibling range and loaderVersion off 26.x too")
    void transformModRelaxesSiblingRangeBelow26(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("rsd-fixture.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(src))) {
            writeEntry(jos, "META-INF/mods.toml",
                    "modLoader=\"javafml\"\nloaderVersion=\"[47,48)\"\nlicense=\"MIT\"\n"
                  + "[[mods]]\nmodId=\"rsd_fixture\"\n"
                  + "[[dependencies.rsd_fixture]]\nmodId=\"farmersdelight\"\n"
                  + "type=\"required\"\nversionRange=\"[1.20.1-1.2.4,)\"\n"
                  + "[[dependencies.rsd_fixture]]\nmodId=\"minecraft\"\n"
                  + "versionRange=\"[1.20.1,1.21)\"\n");
        }

        Path out = new ForgeModTransformer("1.21.1")
                .transformMod(src, Files.createDirectory(tmp.resolve("out")));

        assertNotNull(out);
        String toml = readEntry(out, "META-INF/mods.toml");
        if (toml == null) toml = readEntry(out, "META-INF/neoforge.mods.toml");
        assertTrue(toml.contains("versionRange = \"[0,)\""),
                "Farmer's Delight 1.3.4 sorts below 1.20.1-1.2.4: " + toml);
        assertTrue(toml.contains("\"optional\""), toml);
        assertTrue(toml.contains("loaderVersion=\"[1,)\""), toml);
    }

    @Test
    @DisplayName("#283: an old mandatory-only dependency is marked optional for NeoForge")
    void transformModMarksMandatoryOnlyDependencyOptional(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("mandatory-fixture.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(src))) {
            writeEntry(jos, "META-INF/mods.toml",
                    "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                  + "[[mods]]\nmodId=\"mand_fixture\"\n"
                  + "[[dependencies.mand_fixture]]\nmodId=\"geckoanimfix\"\n"
                  + "mandatory=false\nversionRange=\"[1.0,)\"\n"
                  + "[[dependencies.mand_fixture]]\nmodId=\"curios\"\n"
                  + "mandatory=true\ntype=\"required\"\nversionRange=\"[5.0,)\"\n"
                  + "[[dependencies.mand_fixture]]\nmodId=\"minecraft\"\n"
                  + "versionRange=\"[1.20.1,1.21)\"\n");
        }

        Path out = new ForgeModTransformer("1.21.1")
                .transformMod(src, Files.createDirectory(tmp.resolve("out")));

        String toml = readEntry(out, "META-INF/mods.toml");
        if (toml == null) toml = readEntry(out, "META-INF/neoforge.mods.toml");
        String gecko = toml.substring(toml.indexOf("geckoanimfix"), toml.indexOf("curios"));
        assertTrue(gecko.contains("type = \"optional\""),
                "NeoForge ignores mandatory and requires an untyped dependency: " + gecko);
        String curios = toml.substring(toml.indexOf("curios"), toml.indexOf("\"minecraft\""));
        assertEquals(1, curios.split("type\\s*=", -1).length - 1,
                "a block that already has a type must not get a duplicate key: " + curios);
    }

    @Test
    @DisplayName("#308: a bundled Forge MixinExtras is removed from jar-in-jar, other libraries stay")
    void stripsBundledForgeMixinExtras(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("META-INF/jarjar"));
        Files.createDirectories(dir.resolve("META-INF/jars"));
        Files.writeString(dir.resolve("META-INF/jars/mixinextras-forge-0.4.1.jar"), "jar");
        Files.writeString(dir.resolve("META-INF/jarjar/geckolib-4.8.jar"), "jar");
        Files.writeString(dir.resolve("outside.jar"), "keep");
        Files.writeString(dir.resolve("META-INF/jarjar/metadata.json"), """
                {"jars": [
                  {"identifier": {"group": "io.github.llamalad7", "artifact": "mixinextras-forge"},
                   "version": {"range": "[0.4.1,)", "artifactVersion": "0.4.1"},
                   "path": "META-INF/jars/mixinextras-forge-0.4.1.jar"},
                  {"identifier": {"group": "software.bernie.geckolib", "artifact": "geckolib"},
                   "version": {"range": "[4.8,)", "artifactVersion": "4.8"},
                   "path": "META-INF/jarjar/geckolib-4.8.jar"}
                ]}
                """);

        int removed = ForgeModTransformer.stripJarInJarArtifact(
                dir, "io.github.llamalad7", "mixinextras-forge");

        assertEquals(1, removed);
        assertFalse(Files.exists(dir.resolve("META-INF/jars/mixinextras-forge-0.4.1.jar")),
                "the jar the entry named must be deleted, even outside META-INF/jarjar/");
        assertTrue(Files.exists(dir.resolve("META-INF/jarjar/geckolib-4.8.jar")));
        String metadata = Files.readString(dir.resolve("META-INF/jarjar/metadata.json"));
        assertFalse(metadata.contains("mixinextras"), metadata);
        assertTrue(metadata.contains("geckolib"), "other bundled libraries must stay: " + metadata);
        assertTrue(Files.exists(dir.resolve("outside.jar")));
    }

    @Test
    @DisplayName("#308: a jar-in-jar path that escapes the mod is not deleted")
    void jarInJarPathCannotEscape(@TempDir Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve("mod"));
        Files.createDirectories(dir.resolve("META-INF/jarjar"));
        Path victim = Files.writeString(root.resolve("victim.jar"), "keep");
        Files.writeString(dir.resolve("META-INF/jarjar/metadata.json"), """
                {"jars": [{"identifier": {"group": "io.github.llamalad7", "artifact": "mixinextras-forge"},
                           "path": "../victim.jar"}]}
                """);

        assertEquals(1, ForgeModTransformer.stripJarInJarArtifact(
                dir, "io.github.llamalad7", "mixinextras-forge"));
        assertTrue(Files.exists(victim), "a path outside the extracted mod must never be deleted");
    }

    @Test
    @DisplayName("an incompatible dependency keeps its range whichever line comes first")
    void transformModKeepsIncompatibleRange(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("incompatible-fixture.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(src))) {
            writeEntry(jos, "META-INF/mods.toml",
                    "modLoader=\"javafml\"\nloaderVersion='[47,48)'\nlicense=\"MIT\"\n"
                  + "[[mods]]\nmodId=\"inc_fixture\"\n"
                  + "[[dependencies.inc_fixture]]\nmodId=\"embeddium\"\n"
                  + "versionRange=\"[,0.3.20)\"\ntype=\"incompatible\"\n"
                  + "[[dependencies.inc_fixture]]\nmodId=\"minecraft\"\n"
                  + "versionRange=\"[1.20.1,1.21)\"\n");
        }

        Path out = new ForgeModTransformer("1.21.1")
                .transformMod(src, Files.createDirectory(tmp.resolve("out")));

        String toml = readEntry(out, "META-INF/mods.toml");
        if (toml == null) toml = readEntry(out, "META-INF/neoforge.mods.toml");
        assertTrue(toml.contains("versionRange=\"[,0.3.20)\""),
                "widening would make every Embeddium incompatible: " + toml);
        assertTrue(toml.contains("loaderVersion=\"[1,)\""),
                "a single-quoted loaderVersion is relaxed too: " + toml);
    }

    @Test
    @DisplayName("#221: runtime transform sanitizes an AccessTransformer inside Jar-in-Jar")
    void transformModSanitizesNestedAccessTransformer(@TempDir Path tmp) throws Exception {
        byte[] nested = jarBytes("META-INF/accesstransformer.cfg",
                "public net.minecraft.world.level.storage.loot.LootContext; # nested\n");
        Path src = tmp.resolve("outer-with-nested-at.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(src))) {
            writeEntry(output, "META-INF/mods.toml",
                    "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                  + "[[mods]]\nmodId=\"outer_fixture\"\n"
                  + "[[dependencies.outer_fixture]]\nmodId=\"minecraft\"\n"
                  + "versionRange=\"[1.20.1,1.21)\"\n");
            output.putNextEntry(new JarEntry("META-INF/jarjar/nested.jar"));
            output.write(nested);
            output.closeEntry();
        }

        Path transformed = new ForgeModTransformer("26.1")
                .transformMod(src, Files.createDirectory(tmp.resolve("out")));

        assertNotNull(transformed);
        byte[] nestedOutput;
        try (JarFile outer = new JarFile(transformed.toFile())) {
            try (var input = outer.getInputStream(
                    outer.getJarEntry("META-INF/jarjar/nested.jar"))) {
                nestedOutput = input.readAllBytes();
            }
        }
        Path nestedPath = tmp.resolve("nested-output.jar");
        Files.write(nestedPath, nestedOutput);
        assertEquals(
                "public net.minecraft.world.level.storage.loot.LootContext # nested\n",
                readEntry(nestedPath, "META-INF/accesstransformer.cfg"));
    }

    @Test
    @DisplayName("runtime transform embeds generated helpers into a Jar-in-Jar under its parent path")
    void transformModEmbedsSyntheticsIntoNestedJarUnderParentKey(@TempDir Path tmp) throws Exception {
        String oldHelper = "legacy/api/Helper";
        String synthetic = "com/retromod/generated/forge/Helper";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            transformer.registerClassRedirect(oldHelper, synthetic);
            transformer.registerSyntheticClass(synthetic, emptyClass(synthetic));
            java.io.ByteArrayOutputStream nested = new java.io.ByteArrayOutputStream();
            try (JarOutputStream output = new JarOutputStream(nested)) {
                output.putNextEntry(new JarEntry("lib/UsesHelper.class"));
                output.write(classWithFieldOf("lib/UsesHelper", oldHelper));
                output.closeEntry();
            }
            Path src = tmp.resolve("outer-with-nested-lib.jar");
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(src))) {
                writeEntry(output, "META-INF/mods.toml",
                        "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                      + "[[mods]]\nmodId=\"outer_fixture\"\n"
                      + "[[dependencies.outer_fixture]]\nmodId=\"minecraft\"\n"
                      + "versionRange=\"[1.20.1,1.21)\"\n");
                output.putNextEntry(new JarEntry("META-INF/jarjar/library.jar"));
                output.write(nested.toByteArray());
                output.closeEntry();
            }

            Path transformed = new ForgeModTransformer("26.1")
                    .transformMod(src, Files.createDirectory(tmp.resolve("out")));

            Path nestedPath = tmp.resolve("nested-output.jar");
            try (JarFile outer = new JarFile(transformed.toFile());
                 var input = outer.getInputStream(outer.getJarEntry("META-INF/jarjar/library.jar"))) {
                Files.write(nestedPath, input.readAllBytes());
            }
            String relocated = SyntheticEmbedder.embeddedBase(
                    "outer-with-nested-lib.jar!/META-INF/jarjar/library.jar") + synthetic;
            try (JarFile library = new JarFile(nestedPath.toFile())) {
                assertNotNull(library.getJarEntry(relocated + ".class"),
                        "a bundled library loads as its own module, so it needs its own helper copy "
                                + "under a package keyed by its parent path");
                assertNull(library.getJarEntry(synthetic + ".class"),
                        "the shared helper name must not leak into the nested jar");
            }
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    private static byte[] classWithFieldOf(String name, String fieldType) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                name, null, "java/lang/Object", null);
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE, "helper", "L" + fieldType + ";",
                null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        return classWithFieldOf(name, "java/lang/Object");
    }

    @Test
    @DisplayName("refmap discovery accepts JSON resources but not nested jar filenames")
    void refmapDiscoveryRequiresJson() {
        assertTrue(ForgeModTransformer.isRefmapResource("mappings/compat-refmap.json"));
        assertTrue(ForgeModTransformer.isRefmapResource("MAPPINGS/COMPAT-REFMAP.JSON"));
        assertFalse(ForgeModTransformer.isRefmapResource(
                "META-INF/jarjar/refmap-library.jar"));
    }

    @Test
    @DisplayName("#42: top-level loaderVersion is relaxed to [1,)")
    void relaxesLoaderVersion() {
        String in = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n";
        String out = ForgeModTransformer.relaxLoaderVersion(in);
        assertTrue(out.contains("loaderVersion=\"[1,)\""), out);
        assertFalse(out.contains("[47,)"), "old Forge loaderVersion must be gone");
        assertTrue(out.contains("modLoader=\"javafml\""), "other fields untouched");
        assertTrue(out.contains("license=\"MIT\""), "other fields untouched");
    }

    @Test
    @DisplayName("#42: spaced form works and dependency versionRanges are left alone")
    void spacedFormAndDependenciesUntouched() {
        String in = "loaderVersion = \"[2,)\"\n"
                + "[[dependencies.foo]]\n"
                + "modId=\"minecraft\"\n"
                + "versionRange=\"[1.20.1]\"\n";
        String out = ForgeModTransformer.relaxLoaderVersion(in);
        assertTrue(out.contains("loaderVersion = \"[1,)\""), out);
        // only the top-level loaderVersion changes, not deps
        assertTrue(out.contains("versionRange=\"[1.20.1]\""), "dependency versionRange must be untouched");
    }

    @Test
    @DisplayName("No loaderVersion line → content unchanged")
    void noLoaderVersionIsNoop() {
        String in = "modLoader=\"javafml\"\nlicense=\"MIT\"\n";
        assertEquals(in, ForgeModTransformer.relaxLoaderVersion(in));
    }

    @Test
    @DisplayName("#42: a `forge` dependency is repointed at `neoforge` (which exists on NeoForge)")
    void pointsForgeDepAtNeoForge() {
        // a forge dep plus a minecraft dep
        String in = "[[dependencies.twigs]]\n"
                + "modId=\"forge\"\n"
                + "versionRange=\"[47,)\"\n"
                + "[[dependencies.twigs]]\n"
                + "modId = \"minecraft\"\n"
                + "versionRange=\"[1.21.1,)\"\n";
        String out = ForgeModTransformer.pointForgeDependencyAtNeoForge(in);
        assertTrue(out.contains("modId=\"neoforge\""), "forge dep must be repointed at neoforge: " + out);
        assertFalse(out.contains("modId=\"forge\""), "no `forge` dependency should remain");
        assertFalse(out.contains("[47,)"), "Forge's loader version must not constrain NeoForge");
        assertTrue(out.contains("versionRange=\"[0,)\""),
                "the promoted NeoForge dependency should accept the current loader: " + out);
        // the minecraft dependency and its versionRange stay untouched
        assertTrue(out.contains("modId = \"minecraft\""), "minecraft dep untouched");
        assertTrue(out.contains("versionRange=\"[1.21.1,)\""), "minecraft versionRange untouched");
    }

    @Test
    @DisplayName("#62: a missing license is added as a root-table key (before [[mods]])")
    void addsMissingLicense() {
        String in = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n\n[[mods]]\nmodId=\"survivalisland\"\n";
        String out = ForgeModTransformer.ensureLicense(in);
        assertTrue(out.contains("license="), "license must be added: " + out);
        // must stay in the root table, before the [[mods]] header
        assertTrue(out.indexOf("license=") < out.indexOf("[[mods]]"),
                "license must precede the [[mods]] table: " + out);
        assertTrue(out.contains("modId=\"survivalisland\""), "existing content preserved");
    }

    @Test
    @DisplayName("#62: an existing license is left untouched")
    void keepsExistingLicense() {
        String in = "modLoader=\"javafml\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"x\"\n";
        assertEquals(in, ForgeModTransformer.ensureLicense(in),
                "must not modify a toml that already declares a license");
    }

    /**
     * NeoForge/Forge runtime path: {@link ForgeModTransformer#transformMod} must strip {@code //}
     * comments from a mod's bundled worldgen JSON, since 26.1's strict gson rejects them
     * (FatalStartupException on Philips Ruins). Checks the {@code migrateTree} wiring is reached
     * by the runtime path, not just the offline CLI (#42).
     */
    @Test
    @DisplayName("runtime transformMod strips lenient-JSON comments from bundled worldgen data (26.x)")
    void transformModMigratesBundledWorldgenData(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("structuremod-1.21.1.jar");
        String commented = "{\n  // a comment 26.1 strict gson rejects\n  \"processors\": []\n}";
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(src))) {
            writeEntry(jos, "META-INF/neoforge.mods.toml",
                    "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                  + "[[mods]]\nmodId=\"smod\"\n"
                  + "[[dependencies.smod]]\nmodId=\"minecraft\"\nversionRange=\"[1.21.1,)\"\n");
            writeEntry(jos, "data/smod/worldgen/processor_list/x.json", commented);
        }

        Path outDir = Files.createDirectory(tmp.resolve("out"));
        Path out = new ForgeModTransformer("26.1").transformMod(src, outDir);

        assertNotNull(out, "transformMod should produce an output jar");
        String migrated = readEntry(out, "data/smod/worldgen/processor_list/x.json");
        assertNotNull(migrated, "the bundled data file must survive the transform");
        assertFalse(migrated.contains("//"), "the // comment must be stripped by the runtime path: " + migrated);
        // and it must now parse under a strict (26.1-style) reader
        var r = new com.google.gson.stream.JsonReader(new java.io.StringReader(migrated));
        r.setLenient(false);
        assertDoesNotThrow(() -> com.google.gson.JsonParser.parseReader(r),
                "migrated worldgen JSON must be strict-parseable");
    }

    private static void writeEntry(JarOutputStream jos, String name, String content) throws Exception {
        jos.putNextEntry(new JarEntry(name));
        jos.write(content.getBytes(StandardCharsets.UTF_8));
        jos.closeEntry();
    }

    private static byte[] jarBytes(String name, String content) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            writeEntry(output, name, content);
        }
        return bytes.toByteArray();
    }

    private static String readEntry(Path jar, String name) throws Exception {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry e = jf.getJarEntry(name);
            if (e == null) return null;
            try (var is = jf.getInputStream(e)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    @DisplayName("#62: license is added even when there is no table header")
    void addsLicenseWithNoTableHeader() {
        String in = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n";
        String out = ForgeModTransformer.ensureLicense(in);
        assertTrue(out.contains("license="), "license must be added even with no [table]: " + out);
    }
}
