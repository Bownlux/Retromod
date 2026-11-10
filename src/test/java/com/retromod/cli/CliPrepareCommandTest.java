/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import com.retromod.inplace.TranslatedJarMarkers;
import com.retromod.loaderapi.CliHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code prepare} wrote translated copies to {@code mods/retromod-output/}, where no loader looks
 * (and where Quilt loads them beside the originals), then wrote Fabric overrides that let the old
 * originals load untranslated. It now installs each translated old mod in place of its original.
 */
class CliPrepareCommandTest {

    @TempDir
    Path directory;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
    }

    @Test
    @DisplayName("an old Fabric mod is replaced by its stamped translation, and the original is kept")
    void installsTranslatedFabricModAndMovesOriginal() throws Exception {
        Path game = game();
        Path old = fabricMod(game.resolve("mods/oldmod.jar"), "oldmod", "1.20.1");
        byte[] before = Files.readAllBytes(old);

        String output = CliHarness.prepareGame(game, "26.3");

        Path installed = game.resolve("mods/oldmod-retromod.jar");
        assertTrue(Files.isRegularFile(installed), output);
        Manifest manifest = manifest(installed);
        assertEquals("true", manifest.getMainAttributes().getValue(TranslatedJarMarkers.TRANSFORMED));
        assertEquals("26.3", manifest.getMainAttributes().getValue(TranslatedJarMarkers.TARGET_VERSION));
        assertFalse(Files.exists(old), "the untranslated original must leave mods/");
        assertArrayEquals(before, Files.readAllBytes(game.resolve("retromod-input/processed/oldmod.jar")));
        assertFalse(Files.exists(game.resolve("mods/retromod-output")), "no loader reads that folder");
        assertEquals(List.of("config", "mods", "retromod-input"), children(game),
                "the staging folder is removed: " + output);
        assertTrue(output.contains("is ready for Minecraft 26.3"), output);
    }

    @Test
    @DisplayName("a failed, a current, and a Retromod jar stay in mods/ as they were")
    void leavesFailedCurrentAndRetromodJarsAlone() throws Exception {
        Path game = game();
        Path broken = jar(game.resolve("mods/broken.jar"), Map.of(
                "fabric.mod.json", fabricMetadata("broken", "1.20.1"),
                "../escape.txt", "outside the jar"));
        Path current = fabricMod(game.resolve("mods/current.jar"), "current", "26.3");
        Path retromod = fabricMod(game.resolve("mods/retromod.jar"), "retromod", "1.20.1");
        Map<Path, byte[]> before = new LinkedHashMap<>();
        for (Path jar : List.of(broken, current, retromod)) before.put(jar, Files.readAllBytes(jar));

        String output = CliHarness.prepareGame(game, "26.3");

        for (Map.Entry<Path, byte[]> jar : before.entrySet()) {
            assertArrayEquals(jar.getValue(), Files.readAllBytes(jar.getKey()), jar.getKey() + "\n" + output);
        }
        assertEquals(List.of("broken.jar", "current.jar", "retromod.jar"), children(game.resolve("mods")));
        assertTrue(output.contains("broken.jar: could not be translated"), output);
        assertFalse(output.contains("is ready for Minecraft"), "a failed jar means the folder is not ready");
    }

    @Test
    @DisplayName("a current mod the batch fails on stays as it was and does not block the ready line")
    void currentModFailureIsNotAProblem() throws Exception {
        Path game = game();
        fabricMod(game.resolve("mods/oldmod.jar"), "oldmod", "1.20.1");
        Path current = unparseableFabricMod(game.resolve("mods/current.jar"), "current", "26.3");
        byte[] before = Files.readAllBytes(current);

        String output = CliHarness.prepareGame(game, "26.3");

        assertArrayEquals(before, Files.readAllBytes(current), output);
        assertTrue(output.contains("current.jar: built for Minecraft 26.3"), output);
        assertTrue(output.contains("The batch failed on it"), "the failure detail is still shown: " + output);
        assertFalse(output.contains("current.jar: could not be translated"), output);
        assertTrue(Files.isRegularFile(game.resolve("mods/oldmod-retromod.jar")), output);
        assertTrue(output.contains("is ready for Minecraft 26.3"),
                "only an old mod that failed keeps the folder from being ready: " + output);
    }

    @Test
    @DisplayName("Retromod's own jar stays as it was even when the batch fails on it")
    void retromodJarFailureIsNotAProblem() throws Exception {
        Path game = game();
        // Tagged for an older Minecraft, so only the Retromod check keeps it out of the problems.
        Path retromod = unparseableFabricMod(game.resolve("mods/retromod.jar"), "retromod", "1.20.1");
        byte[] before = Files.readAllBytes(retromod);

        String output = CliHarness.prepareGame(game, "26.3");

        assertArrayEquals(before, Files.readAllBytes(retromod), output);
        assertTrue(output.contains("retromod.jar: Retromod or a jar it already translated"), output);
        assertFalse(output.contains("retromod.jar: could not be translated"), output);
        assertTrue(output.contains("is ready for Minecraft 26.3"), output);
    }

    @Test
    @DisplayName("prepare writes no Fabric dependency overrides and keeps the user's own")
    void writesNoDependencyOverrides() throws Exception {
        Path game = game();
        fabricMod(game.resolve("mods/oldmod.jar"), "oldmod", "1.20.1");
        CliHarness.prepareGame(game, "26.3");
        assertFalse(Files.exists(game.resolve("config/fabric_loader_dependencies.json")),
                "an override lets Fabric load an old jar Retromod did not translate");

        Path other = Files.createDirectories(directory.resolve("other"));
        Files.createDirectories(other.resolve("mods"));
        Path overrides = Files.createDirectories(other.resolve("config")).resolve("fabric_loader_dependencies.json");
        Files.writeString(overrides, "{\"version\":1,\"overrides\":{\"mine\":{}}}");
        fabricMod(other.resolve("mods/oldmod.jar"), "oldmod", "1.20.1");
        CliHarness.prepareGame(other, "26.3");
        assertEquals("{\"version\":1,\"overrides\":{\"mine\":{}}}", Files.readString(overrides));
    }

    @Test
    @DisplayName("without --target or --mc-jar, prepare names the flag and changes nothing")
    void refusesWithoutExplicitTarget() throws Exception {
        Path game = game();
        Path old = fabricMod(game.resolve("mods/oldmod.jar"), "oldmod", "1.20.1");
        byte[] before = Files.readAllBytes(old);

        String output = CliHarness.prepareGame(game, null);

        assertTrue(output.contains("--target <version>"), output);
        assertEquals(List.of("oldmod.jar"), children(game.resolve("mods")));
        assertArrayEquals(before, Files.readAllBytes(old));
        assertEquals(List.of("config", "mods"), children(game), "no staging folder is left behind");
    }

    @Test
    @DisplayName("a Forge mod without --target-loader is left alone, with the flag named")
    void refusesForgeModsWithoutTargetLoader() throws Exception {
        Path game = game();
        Path old = forgeMod(game.resolve("mods/oldforge.jar"));
        byte[] before = Files.readAllBytes(old);

        String output = CliHarness.prepareGame(game, "26.3");

        assertTrue(output.contains("--target-loader forge or --target-loader neoforge"), output);
        assertEquals(List.of("oldforge.jar"), children(game.resolve("mods")));
        assertArrayEquals(before, Files.readAllBytes(old));
        assertFalse(Files.exists(game.resolve("retromod-input/processed/oldforge.jar")));
    }

    @Test
    @DisplayName("an installed Forge mod reads as Retromod output, so in-place loading keeps it")
    void installedForgeModIsTranslatedOutput() throws Exception {
        Path game = game();
        forgeMod(game.resolve("mods/Old Forge Mod 1.0.jar"));

        String output = CliHarness.prepareGame(game, "26.3", "--target-loader", "forge");

        Path installed = game.resolve("mods/Old_Forge_Mod_1.0-retromod.jar");
        assertTrue(Files.isRegularFile(installed), "Forge derives a module name from the file: " + output);
        assertTrue(TranslatedJarMarkers.isRetromodOutput(manifest(installed)),
                "without the mark, discovery reads the widened [1.20.1,) range as an old mod again");
        assertTrue(Files.isRegularFile(game.resolve("retromod-input/processed/Old Forge Mod 1.0.jar")));
    }

    // ---- fixtures ----

    private Path game() throws IOException {
        Path game = directory.resolve("game");
        Files.createDirectories(game.resolve("mods"));
        Files.createDirectories(game.resolve("config"));
        return game;
    }

    private static Path fabricMod(Path file, String id, String minecraft) throws IOException {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json", fabricMetadata(id, minecraft));
        entries.put(id + "/Main.class", plainClass(id + "/Main"));
        return jar(file, entries);
    }

    /** Readable metadata, then a cut-off class the transform cannot parse. */
    private static Path unparseableFabricMod(Path file, String id, String minecraft) throws IOException {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json", fabricMetadata(id, minecraft));
        entries.put(id + "/Main.class", new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0, 0, 0, 0x41, 0, 0x05});
        return jar(file, entries);
    }

    private static String fabricMetadata(String id, String minecraft) {
        return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\","
                + "\"depends\":{\"fabricloader\":\">=0.14.21\",\"minecraft\":\"" + minecraft + "\"}}";
    }

    private static Path forgeMod(Path file) throws IOException {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"oldforge\"\nversion=\"1.0\"\n[[dependencies.oldforge]]\n"
                + "modId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.20.1,1.21)\"\n");
        entries.put("oldforge/Main.class", plainClass("oldforge/Main"));
        return jar(file, entries);
    }

    private static byte[] plainClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Path jar(Path file, Map<String, ?> entries) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, ?> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                Object value = entry.getValue();
                out.write(value instanceof byte[] bytes ? bytes
                        : value.toString().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    private static Manifest manifest(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            assertNotNull(file.getManifest(), "the installed jar must carry a manifest");
            return file.getManifest();
        }
    }

    private static List<String> children(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
