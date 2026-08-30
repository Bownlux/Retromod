/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackTransformerSecurityTest {

    @Test
    void folderAndZipResourcePacksProduceTheSameModernMetadata(@TempDir Path root)
            throws Exception {
        Path folder = root.resolve("folder-pack");
        Files.createDirectories(folder.resolve("assets/example"));
        Files.writeString(folder.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":46,\"description\":\"same\"},\"language\":{}}");
        Files.writeString(folder.resolve("assets/example/value.txt"), "value");

        Path zip = root.resolve("zip-pack.zip");
        writeZip(zip, Map.of(
            "pack.mcmeta",
            "{\"pack\":{\"pack_format\":46,\"description\":\"same\"},\"language\":{}}",
            "assets/example/value.txt", "value"));

        Path output = root.resolve("out");
        Files.createDirectories(output);
        ResourcePackTransformer transformer = new ResourcePackTransformer("26.2");
        Path folderResult = transformer.transformPack(folder, output);
        Path zipResult = transformer.transformPack(zip, output);

        assertEquals(readMetadata(folderResult), readMetadata(zipResult));
        JsonObject metadata = readMetadata(folderResult).getAsJsonObject("pack");
        assertEquals(88, metadata.getAsJsonArray("min_format").get(0).getAsInt());
        assertEquals(0, metadata.getAsJsonArray("min_format").get(1).getAsInt());
        assertTrue(readMetadata(folderResult).has("language"));
    }

    @Test
    void dataPackWritesMinorFormatAndKeepsDataFiles(@TempDir Path root) throws Exception {
        Path pack = root.resolve("data-pack");
        Path dataFile = pack.resolve("data/example/functions/test.mcfunction");
        Files.createDirectories(dataFile.getParent());
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":61,\"description\":\"data\"}}");
        Files.writeString(dataFile, "say retained");

        Path output = root.resolve("out");
        Files.createDirectories(output);
        Path result = new DataPackTransformer("1.21.11").transformPack(pack, output);

        JsonObject metadata = readMetadata(result).getAsJsonObject("pack");
        assertEquals(94, metadata.getAsJsonArray("max_format").get(0).getAsInt());
        assertEquals(1, metadata.getAsJsonArray("max_format").get(1).getAsInt());
        try (ZipFile transformed = new ZipFile(result.toFile())) {
            assertTrue(transformed.getEntry("data/example/functions/test.mcfunction") != null);
        }
    }

    @Test
    void transformedEmptyDataPackKeepsItsDataDirectory(@TempDir Path root) throws Exception {
        Path pack = root.resolve("empty-data-pack");
        Files.createDirectories(pack.resolve("data"));
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":61,\"description\":\"empty\"}}");

        Path output = root.resolve("out");
        Files.createDirectories(output);
        Path result = new DataPackTransformer("26.2").transformPack(pack, output);

        assertTrue(DataPackTransformer.isDataPack(result));
        try (ZipFile transformed = new ZipFile(result.toFile())) {
            assertTrue(transformed.getEntry("data/") != null);
        }
    }

    @Test
    void archiveWithDataFileIsNotADataPack(@TempDir Path root) throws Exception {
        Path pack = root.resolve("data-file.zip");
        writeZip(pack, Map.of(
            "pack.mcmeta", "{\"pack\":{\"pack_format\":61,\"description\":\"bad\"}}",
            "data", "not a directory"));

        assertFalse(DataPackTransformer.isDataPack(pack));
    }

    @Test
    void archiveEntriesCannotCollideByCaseOrUnicodeNormalization(@TempDir Path root)
            throws Exception {
        Path caseCollision = root.resolve("case-collision.zip");
        writeZip(caseCollision, Map.of(
            "assets/example/Value.txt", "first",
            "assets/example/value.txt", "second"));
        IOException caseFailure = assertThrows(IOException.class,
            () -> PackArchive.extractZip(caseCollision, root.resolve("case-output"),
                "Resource pack"));
        assertTrue(caseFailure.getMessage().contains("same portable path"));

        Path unicodeCollision = root.resolve("unicode-collision.zip");
        writeZip(unicodeCollision, Map.of(
            "assets/example/caf\u00e9.txt", "composed",
            "assets/example/cafe\u0301.txt", "decomposed"));
        IOException unicodeFailure = assertThrows(IOException.class,
            () -> PackArchive.extractZip(unicodeCollision, root.resolve("unicode-output"),
                "Resource pack"));
        assertTrue(unicodeFailure.getMessage().contains("same portable path"));
    }

    @Test
    void dataPackMetadataEraChangesAfterFormat81(@TempDir Path root) throws Exception {
        Path source = root.resolve("source");
        Path dataFile = source.resolve("data/example/functions/test.mcfunction");
        Files.createDirectories(dataFile.getParent());
        Files.writeString(source.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":61,\"description\":\"data\"}}");
        Files.writeString(dataFile, "say retained");

        assertLegacyDataMetadata(transformDataPack(source, root.resolve("out-1.21.5"), "1.21.5"), 71);
        assertLegacyDataMetadata(transformDataPack(source, root.resolve("out-1.21.8"), "1.21.8"), 81);

        JsonObject modern = readMetadata(
            transformDataPack(source, root.resolve("out-1.21.9"), "1.21.9"))
            .getAsJsonObject("pack");
        assertFalse(modern.has("pack_format"));
        assertEquals(88, modern.getAsJsonArray("min_format").get(0).getAsInt());
        assertEquals(0, modern.getAsJsonArray("min_format").get(1).getAsInt());
    }

    @Test
    void malformedMetadataFailsInsteadOfLookingCompatible(@TempDir Path root) throws Exception {
        Path pack = root.resolve("bad-pack");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("pack.mcmeta"), "not json");

        ResourcePackTransformer transformer = new ResourcePackTransformer("26.2");
        assertThrows(IOException.class, () -> transformer.needsTransformation(pack));
        assertThrows(IOException.class, () -> transformer.transformPack(pack, root.resolve("out")));
    }

    @Test
    void packMetadataRejectsMalformedUtf8(@TempDir Path root) throws Exception {
        Path pack = root.resolve("bad-utf8.zip");
        writeRawZip(pack, Map.of(
            "pack.mcmeta", malformedUtf8Json(
                "{\"pack\":{\"pack_format\":46,\"description\":\"~\"}}")));

        IOException failure = assertThrows(IOException.class,
            () -> PackMetadata.read(pack));

        assertTrue(failure.getMessage().contains("not valid UTF-8"));
    }

    @Test
    void metadataReadsAreBoundedForFoldersAndZips(@TempDir Path root) throws Exception {
        String oversized = " ".repeat((int) PackArchive.MAX_METADATA_BYTES + 1);
        Path folder = root.resolve("folder");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("pack.mcmeta"), oversized);

        Path zip = root.resolve("pack.zip");
        writeZip(zip, Map.of("pack.mcmeta", oversized));

        assertThrows(IOException.class, () -> PackMetadata.read(folder));
        assertThrows(IOException.class, () -> PackMetadata.read(zip));
    }

    @Test
    void deeplyNestedMetadataIsRefusedBeforeParsing(@TempDir Path root) throws Exception {
        Path folder = root.resolve("deep-pack");
        Files.createDirectories(folder);
        String deeplyNested = "{".repeat(10_000) + "}".repeat(10_000);
        Files.writeString(folder.resolve("pack.mcmeta"), deeplyNested);

        assertThrows(IOException.class, () -> PackMetadata.read(folder));
    }

    @Test
    void preFlatteningTextureMovesAreBlockScopedAndKeepAnimationMetadata(@TempDir Path root)
            throws Exception {
        Path pack = root.resolve("legacy-pack");
        Path oldBlocks = pack.resolve("assets/minecraft/textures/blocks");
        Path currentBlocks = pack.resolve("assets/minecraft/textures/block");
        Path oldItems = pack.resolve("assets/minecraft/textures/items");
        Files.createDirectories(oldBlocks);
        Files.createDirectories(currentBlocks);
        Files.createDirectories(oldItems);
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":3,\"description\":\"legacy\"}}");
        Files.writeString(oldBlocks.resolve("grass_side.png"), "block");
        Files.writeString(oldBlocks.resolve("grass_side.png.mcmeta"), "animation");
        Files.writeString(oldBlocks.resolve("redstone_torch_on.png"), "torch");
        Files.writeString(oldBlocks.resolve("furnace_front_on.png"), "furnace");
        Files.writeString(oldBlocks.resolve("comparator_on.png"), "comparator");
        Files.writeString(oldBlocks.resolve("repeater_on.png"), "repeater");
        Files.writeString(currentBlocks.resolve("existing.png"), "existing");
        Files.writeString(oldItems.resolve("grass_side.png"), "item");

        Path output = root.resolve("out");
        Files.createDirectories(output);
        Path result = new ResourcePackTransformer("1.21.8").transformPack(pack, output);

        try (ZipFile transformed = new ZipFile(result.toFile())) {
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/grass_block_side.png") != null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/grass_block_side.png.mcmeta") != null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/existing.png") != null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/redstone_torch.png") != null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/redstone_torch_lit.png") == null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/furnace_front_on.png") != null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/comparator_on.png") != null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/repeater_on.png") != null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/item/grass_side.png") != null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/item/grass_block_side.png") == null);
        }
    }

    @Test
    void legacyResourcePackReceivesCurrentItemDefinitions(@TempDir Path root)
            throws Exception {
        Path pack = root.resolve("legacy-items");
        Path model = pack.resolve("assets/example/models/item/widget.json");
        Files.createDirectories(model.getParent());
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":3,\"description\":\"legacy\"}}");
        Files.writeString(model, "{\"parent\":\"minecraft:item/generated\"}");

        Path output = root.resolve("out");
        Files.createDirectories(output);
        Path result = new ResourcePackTransformer("1.21.8").transformPack(pack, output);

        try (ZipFile transformed = new ZipFile(result.toFile())) {
            String definition = readZipEntry(
                transformed, "assets/example/items/widget.json");
            assertTrue(definition.contains("\"model\": \"example:item/widget\""));
        }
    }

    @Test
    void resourcePackAppliesTextureMigrationsAfterTheFlattening(@TempDir Path root)
            throws Exception {
        Path pack = root.resolve("versioned-textures");
        Path armor = pack.resolve(
            "assets/minecraft/textures/models/armor/diamond_layer_1.png");
        Path chicken = pack.resolve("assets/minecraft/textures/entity/chicken.png");
        Files.createDirectories(armor.getParent());
        Files.createDirectories(chicken.getParent());
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":34,\"description\":\"legacy\"}}");
        Files.writeString(armor, "armor");
        Files.writeString(chicken, "chicken");

        Path output = root.resolve("out");
        Files.createDirectories(output);
        Path result = new ResourcePackTransformer("26.2").transformPack(pack, output);

        try (ZipFile transformed = new ZipFile(result.toFile())) {
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/entity/equipment/humanoid/diamond.png") != null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/entity/chicken/chicken_temperate.png") != null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/models/armor/diamond_layer_1.png") == null);
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/entity/chicken.png") == null);
        }
    }

    @Test
    void legacyPrimaryFormatControlsTextureMigrationAcrossASupportedRange(
            @TempDir Path root) throws Exception {
        Path pack = root.resolve("ranged-resource-pack");
        Path blocks = pack.resolve("assets/minecraft/textures/blocks");
        Files.createDirectories(blocks);
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":3,\"supported_formats\":[3,64],"
                + "\"description\":\"legacy\"}}");
        Files.writeString(blocks.resolve("grass_side.png"), "texture");

        Path output = root.resolve("out");
        Files.createDirectories(output);
        Path result = new ResourcePackTransformer("26.2").transformPack(pack, output);

        try (ZipFile transformed = new ZipFile(result.toFile())) {
            assertTrue(transformed.getEntry(
                "assets/minecraft/textures/block/grass_block_side.png") != null);
        }
    }

    @Test
    void legacyPrimaryFormatControlsDataMigrationAcrossASupportedRange(
            @TempDir Path root) throws Exception {
        Path pack = root.resolve("ranged-data-pack");
        Path loot = pack.resolve("data/example/loot_tables/test.json");
        Files.createDirectories(loot.getParent());
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":9,\"supported_formats\":[9,81],"
                + "\"description\":\"legacy\"}}");
        Files.writeString(loot,
            "{\"table\":\"minecraft:entities/zombie_pigman\"}");

        Path output = root.resolve("out");
        Files.createDirectories(output);
        Path result = new DataPackTransformer("26.2").transformPack(pack, output);

        try (ZipFile transformed = new ZipFile(result.toFile())) {
            String content = readZipEntry(
                transformed, "data/example/loot_tables/test.json");
            assertTrue(content.contains("minecraft:entities/zombified_piglin"));
            assertFalse(content.contains("minecraft:entities/zombie_pigman\""));
        }
    }

    @Test
    void normalizedDuplicateEntriesAreRejected(@TempDir Path root) throws Exception {
        Path zip = root.resolve("duplicate.zip");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("pack.mcmeta", "{\"pack\":{\"pack_format\":46,\"description\":\"x\"}}");
        entries.put("assets/example/value.txt", "one");
        entries.put("assets/example//value.txt", "two");
        writeZip(zip, entries);

        assertThrows(IOException.class, () -> PackMetadata.read(zip));
    }

    @Test
    void traversalEntriesAreRejectedBeforeExtraction(@TempDir Path root) throws Exception {
        Path zip = root.resolve("traversal.zip");
        writeZip(zip, Map.of(
            "pack.mcmeta", "{\"pack\":{\"pack_format\":46,\"description\":\"x\"}}",
            "../outside.txt", "blocked"));

        assertThrows(IOException.class,
            () -> new ResourcePackTransformer("26.2").transformPack(zip, root.resolve("out")));
        assertFalse(Files.exists(root.resolve("outside.txt")));
    }

    @Test
    void symlinkEntriesFailWithoutLeavingAnOutput(@TempDir Path root) throws Exception {
        Path external = root.resolve("external.txt");
        Files.writeString(external, "outside");
        Path pack = root.resolve("pack");
        Files.createDirectories(pack.resolve("assets/example"));
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":46,\"description\":\"x\"}}");
        Path link = pack.resolve("assets/example/link.txt");
        try {
            Files.createSymbolicLink(link, external);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("Symbolic links are not available: " + e.getMessage());
        }

        Path output = root.resolve("out");
        Files.createDirectories(output);
        assertThrows(IOException.class,
            () -> new ResourcePackTransformer("26.2").transformPack(pack, output));
        assertFalse(Files.exists(output.resolve("pack-retromod.zip")));
    }

    @Test
    void archiveEntryLimitFailsClosed() {
        assertThrows(IOException.class,
            () -> PackArchive.validateEntryCount(PackArchive.MAX_ARCHIVE_ENTRIES + 1));
    }

    @Test
    void directoryWalkStopsAtItsEntryLimit(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("one"), "1");
        Files.writeString(root.resolve("two"), "2");
        Files.writeString(root.resolve("three"), "3");

        assertThrows(IOException.class, () -> PackArchive.collectBoundedPaths(root, 2));
    }

    @Test
    void stagedDataPackWithoutDataTreeStaysVisible(@TempDir Path gameDirectory)
            throws Exception {
        Path staged = gameDirectory.resolve("retromod-input/datapacks/not-data.zip");
        Files.createDirectories(staged.getParent());
        writeZip(staged, Map.of(
            "pack.mcmeta", "{\"pack\":{\"pack_format\":61,\"description\":\"x\"}}",
            "assets/example/value.txt", "resource only"));

        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();
        manager.processAll();

        assertTrue(Files.exists(staged));
        assertFalse(Files.exists(gameDirectory.resolve(
            "retromod-input/datapacks/processed/not-data.zip")));
    }

    @Test
    void reservedDataPackOutputLeavesSourceAndInstructionsUntouched(
            @TempDir Path gameDirectory) throws Exception {
        ResourceManager manager = new ResourceManager("1.21.8", gameDirectory);
        manager.ensureFolders();

        Path staged = gameDirectory.resolve("retromod-input/datapacks/INSTRUCTIONS.txt");
        writeZip(staged, Map.of(
            "pack.mcmeta", "{\"pack\":{\"pack_format\":81,\"description\":\"x\"}}",
            "data/example/functions/test.mcfunction", "say staged"));
        byte[] sourceBefore = Files.readAllBytes(staged);

        Path instructions = gameDirectory.resolve("retromod-output/datapacks/INSTRUCTIONS.txt");
        Files.createDirectories(instructions.getParent());
        Files.writeString(instructions, "existing managed instructions");

        manager.processAll();

        assertArrayEquals(sourceBefore, Files.readAllBytes(staged));
        assertEquals("existing managed instructions", Files.readString(instructions));
        assertFalse(Files.exists(gameDirectory.resolve(
            "retromod-input/datapacks/processed/INSTRUCTIONS.txt")));
        assertNoPackTransactionDirectories(gameDirectory);
    }

    @Test
    void internalWorkflowPrefixesCannotBecomePackNames(@TempDir Path gameDirectory)
            throws Exception {
        ResourceManager manager = new ResourceManager("1.21.8", gameDirectory);
        manager.ensureFolders();
        Path input = gameDirectory.resolve("retromod-input/resourcepacks");
        for (String name : new String[] {
                ".retromod-pack-publish-user.zip", ".retromod-pack-txn-user.zip"}) {
            writeZip(input.resolve(name), Map.of(
                "pack.mcmeta",
                "{\"pack\":{\"pack_format\":64,\"description\":\"reserved\"}}",
                "assets/example/value.txt", name));
        }

        manager.processAll();

        for (String name : new String[] {
                ".retromod-pack-publish-user.zip", ".retromod-pack-txn-user.zip"}) {
            assertTrue(Files.exists(input.resolve(name)));
            assertFalse(Files.exists(gameDirectory.resolve("resourcepacks").resolve(name)));
            assertFalse(Files.exists(input.resolve("processed").resolve(name)));
        }
        assertNoPackTransactionDirectories(gameDirectory);
    }

    @Test
    void unreadableStagedPackIsNotArchived(@TempDir Path gameDirectory) throws Exception {
        Path input = gameDirectory.resolve("retromod-input/resourcepacks/bad-pack");
        Files.createDirectories(input);
        Files.writeString(input.resolve("pack.mcmeta"), "{bad");

        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();
        manager.processAll();

        assertTrue(Files.exists(input), "the source must remain available after a failed transform");
        Path processed = gameDirectory.resolve("retromod-input/resourcepacks/processed");
        assertFalse(Files.exists(processed.resolve("bad-pack")));
        assertFalse(Files.exists(processed.resolve("bad-pack.done")));
    }

    @Test
    void packNamesDoNotCollideWithRemovedWorkflowMarkers(@TempDir Path gameDirectory)
            throws Exception {
        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();
        Path input = gameDirectory.resolve("retromod-input/resourcepacks");
        Files.writeString(input.resolve("processed/foo.done"), "old marker for foo");
        for (String name : new String[] {"foo", "foo.done"}) {
            Path pack = input.resolve(name);
            Files.createDirectories(pack);
            Files.writeString(pack.resolve("pack.mcmeta"),
                "{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0],"
                    + "\"description\":\"current\"}}");
        }

        manager.processAll();

        assertFalse(Files.exists(input.resolve("foo")));
        assertFalse(Files.exists(input.resolve("foo.done")));
        assertTrue(Files.isDirectory(input.resolve("processed/foo")));
        assertTrue(Files.isDirectory(input.resolve("processed/foo.done")));
        assertTrue(Files.isDirectory(gameDirectory.resolve("resourcepacks/foo")));
        assertTrue(Files.isDirectory(gameDirectory.resolve("resourcepacks/foo.done")));
    }

    @Test
    void sameRunOutputCollisionLeavesBothInputsAndPriorOutputUntouched(
            @TempDir Path gameDirectory) throws Exception {
        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();
        Path input = gameDirectory.resolve("retromod-input/resourcepacks");
        Path legacy = input.resolve("foo.zip");
        Path nativePack = input.resolve("foo-retromod.zip");
        writeZip(legacy, Map.of("pack.mcmeta",
            "{\"pack\":{\"pack_format\":46,\"description\":\"legacy\"}}"));
        writeZip(nativePack, Map.of("pack.mcmeta",
            "{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0],"
                + "\"description\":\"native\"}}"));
        Path installed = gameDirectory.resolve("resourcepacks/foo-retromod.zip");
        Files.createDirectories(installed.getParent());
        writeZip(installed, Map.of("pack.mcmeta",
            "{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0],"
                + "\"description\":\"installed\"}}"));
        byte[] legacyBefore = Files.readAllBytes(legacy);
        byte[] nativeBefore = Files.readAllBytes(nativePack);
        byte[] installedBefore = Files.readAllBytes(installed);

        manager.processAll();

        assertArrayEquals(legacyBefore, Files.readAllBytes(legacy));
        assertArrayEquals(nativeBefore, Files.readAllBytes(nativePack));
        assertArrayEquals(installedBefore, Files.readAllBytes(installed));
        assertFalse(Files.exists(input.resolve("processed/foo.zip")));
        assertFalse(Files.exists(input.resolve("processed/foo-retromod.zip")));
        assertNoPackTransactionDirectories(gameDirectory);
    }

    @Test
    void compatibleStagedPackReplacesSameNamedOutput(@TempDir Path gameDirectory)
            throws Exception {
        Path staged = gameDirectory.resolve("retromod-input/resourcepacks/current.zip");
        Files.createDirectories(staged.getParent());
        writeZip(staged, Map.of(
            "pack.mcmeta",
            "{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0],"
                + "\"description\":\"new\"}}",
            "new.txt", "new"));
        Path installed = gameDirectory.resolve("resourcepacks/current.zip");
        Files.createDirectories(installed.getParent());
        writeZip(installed, Map.of(
            "pack.mcmeta",
            "{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0],"
                + "\"description\":\"old\"}}",
            "old.txt", "old"));
        Path staleMarker = gameDirectory.resolve(
            "retromod-input/resourcepacks/processed/current.zip.done");
        Files.createDirectories(staleMarker.getParent());
        Files.writeString(staleMarker, "older input");

        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();
        manager.processAll();

        try (ZipFile result = new ZipFile(installed.toFile())) {
            assertTrue(result.getEntry("new.txt") != null);
            assertTrue(result.getEntry("old.txt") == null);
        }
        assertFalse(Files.exists(staged));
        assertTrue(Files.exists(gameDirectory.resolve(
            "retromod-input/resourcepacks/processed/current.zip")));
    }

    @Test
    void resourcePackArchiveFailureKeepsInputAndPriorOutput(@TempDir Path gameDirectory)
            throws Exception {
        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();

        Path staged = gameDirectory.resolve("retromod-input/resourcepacks/current.zip");
        writeZip(staged, Map.of(
            "pack.mcmeta",
            "{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0],"
                + "\"description\":\"staged\"}}",
            "staged.txt", "staged"));
        Path installed = gameDirectory.resolve("resourcepacks/current.zip");
        Files.createDirectories(installed.getParent());
        writeZip(installed, Map.of(
            "pack.mcmeta",
            "{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0],"
                + "\"description\":\"installed\"}}",
            "installed.txt", "installed"));

        Path processed = gameDirectory.resolve(
            "retromod-input/resourcepacks/processed/current.zip");
        Files.createDirectory(processed);
        Files.writeString(processed.resolve("keep.txt"), "keep");
        byte[] stagedBefore = Files.readAllBytes(staged);
        byte[] installedBefore = Files.readAllBytes(installed);

        manager.processAll();

        assertArrayEquals(stagedBefore, Files.readAllBytes(staged));
        assertArrayEquals(installedBefore, Files.readAllBytes(installed));
        assertEquals("keep", Files.readString(processed.resolve("keep.txt")));
        assertFalse(Files.exists(processed.resolveSibling("current.zip.done")));
        assertNoPackTransactionDirectories(gameDirectory);
    }

    @Test
    void dataPackPublicationFailureRollsBackArchiveAndPriorOutput(@TempDir Path gameDirectory)
            throws Exception {
        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();

        Path staged = gameDirectory.resolve("retromod-input/datapacks/current.zip");
        writeZip(staged, Map.of(
            "pack.mcmeta",
            "{\"pack\":{\"min_format\":[107,1],\"max_format\":[107,1],"
                + "\"description\":\"staged\"}}",
            "data/example/functions/test.mcfunction", "say staged"));
        Path installed = gameDirectory.resolve(
            "retromod-output/datapacks/current-retromod.zip");
        Files.createDirectories(installed.getParent());
        Path priorTarget = gameDirectory.resolve("prior-output.bin");
        Files.writeString(priorTarget, "installed bytes");
        try {
            Files.createSymbolicLink(installed, priorTarget);
        } catch (UnsupportedOperationException | IOException error) {
            Assumptions.abort("Symbolic links are not available: " + error.getMessage());
        }

        Path processed = gameDirectory.resolve(
            "retromod-input/datapacks/processed/current.zip");
        writeZip(processed, Map.of("old.txt", "old processed input"));

        byte[] stagedBefore = Files.readAllBytes(staged);
        byte[] processedBefore = Files.readAllBytes(processed);

        manager.processAll();

        assertArrayEquals(stagedBefore, Files.readAllBytes(staged));
        assertTrue(Files.isSymbolicLink(installed));
        assertEquals("installed bytes", Files.readString(priorTarget));
        assertArrayEquals(processedBefore, Files.readAllBytes(processed));
        assertNoPackTransactionDirectories(gameDirectory);
    }

    @Test
    void brokenReadmeLinkIsNotFollowed(@TempDir Path root) throws Exception {
        Path gameDirectory = Files.createDirectory(root.resolve("game"));
        Path input = gameDirectory.resolve("retromod-input/resourcepacks");
        Files.createDirectories(input);
        Path missingTarget = root.resolve("outside-readme.txt");
        Path readme = input.resolve("README.txt");
        try {
            Files.createSymbolicLink(readme, missingTarget);
        } catch (UnsupportedOperationException | IOException error) {
            Assumptions.abort("Symbolic links are not available: " + error.getMessage());
        }

        new ResourceManager("26.2", gameDirectory).ensureFolders();

        assertTrue(Files.isSymbolicLink(readme));
        assertFalse(Files.exists(missingTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void brokenInstructionsLinkIsNotFollowed(@TempDir Path root) throws Exception {
        Path gameDirectory = Files.createDirectory(root.resolve("game"));
        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();
        Path output = gameDirectory.resolve("retromod-output/datapacks");
        Files.createDirectories(output);
        Path missingTarget = root.resolve("outside-instructions.txt");
        Path instructions = output.resolve("INSTRUCTIONS.txt");
        try {
            Files.createSymbolicLink(instructions, missingTarget);
        } catch (UnsupportedOperationException | IOException error) {
            Assumptions.abort("Symbolic links are not available: " + error.getMessage());
        }

        manager.processAll();

        assertTrue(Files.isSymbolicLink(instructions));
        assertFalse(Files.exists(missingTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void newerPackIsNotSilentlyCopiedToAnOlderTarget(@TempDir Path root) throws Exception {
        Path pack = root.resolve("new-pack");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0],\"description\":\"x\"}}");

        assertThrows(IOException.class,
            () -> new ResourcePackTransformer("1.21.8").needsTransformation(pack));
    }

    @Test
    void symlinkedPackInputRootIsRefused(@TempDir Path root) throws Exception {
        Path gameDirectory = Files.createDirectory(root.resolve("game"));
        Path external = Files.createDirectory(root.resolve("external-input"));
        createDirectorySymlink(gameDirectory.resolve("retromod-input"), external);

        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();
        manager.processAll();

        assertFalse(Files.exists(external.resolve("resourcepacks")));
        assertFalse(Files.exists(external.resolve("datapacks")));
    }

    @Test
    void symlinkedPackOutputRootLeavesInputStaged(@TempDir Path root) throws Exception {
        Path gameDirectory = Files.createDirectory(root.resolve("game"));
        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();
        Path staged = gameDirectory.resolve("retromod-input/resourcepacks/current.zip");
        writeZip(staged, Map.of(
            "pack.mcmeta",
            "{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0],"
                + "\"description\":\"current\"}}"));
        Path external = Files.createDirectory(root.resolve("external-output"));
        createDirectorySymlink(gameDirectory.resolve("resourcepacks"), external);

        manager.processAll();

        assertTrue(Files.exists(staged));
        assertEquals(0, directoryEntryCount(external));
    }

    @Test
    void symlinkedProcessedRootLeavesInputAndOutputUntouched(@TempDir Path root)
            throws Exception {
        Path gameDirectory = Files.createDirectory(root.resolve("game"));
        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();
        Path processed = gameDirectory.resolve("retromod-input/resourcepacks/processed");
        Files.delete(processed);
        Path external = Files.createDirectory(root.resolve("external-processed"));
        createDirectorySymlink(processed, external);
        Path staged = gameDirectory.resolve("retromod-input/resourcepacks/current.zip");
        writeZip(staged, Map.of(
            "pack.mcmeta",
            "{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0],"
                + "\"description\":\"current\"}}"));

        manager.processAll();

        assertTrue(Files.exists(staged));
        assertFalse(Files.exists(gameDirectory.resolve("resourcepacks/current.zip")));
        assertEquals(0, directoryEntryCount(external));
    }

    @Test
    void lootTableRenameOnlyChangesTheExactIdentifier(@TempDir Path root) throws Exception {
        Path pack = root.resolve("legacy-data-pack");
        Path loot = pack.resolve("data/example/loot_tables/test.json");
        Files.createDirectories(loot.getParent());
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":9,\"description\":\"legacy\"}}");
        Files.writeString(loot,
            "{\"exact\":\"minecraft:entities/zombie_pigman\","
                + "\"lookalike\":\"minecraft:entities/zombie_pigman_variant\"}");

        Path output = root.resolve("out");
        Files.createDirectories(output);
        Path result = new DataPackTransformer("1.21.8").transformPack(pack, output);

        try (ZipFile transformed = new ZipFile(result.toFile());
             var input = transformed.getInputStream(
                 transformed.getEntry("data/example/loot_tables/test.json"))) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("\"minecraft:entities/zombified_piglin\""));
            assertTrue(content.contains("\"minecraft:entities/zombie_pigman_variant\""));
        }
    }

    @Test
    void recipeSchemasArePreservedWhenNoSafeConversionIsKnown(@TempDir Path root)
            throws Exception {
        Path pack = root.resolve("legacy-recipes");
        Path recipes = pack.resolve("data/example/recipes");
        Files.createDirectories(recipes);
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":15,\"description\":\"legacy\"}}");

        Map<String, String> recipeTypes = Map.of(
            "armor.json", "minecraft:crafting_special_armordye",
            "clone.json", "minecraft:crafting_special_mapcloning",
            "extend.json", "minecraft:crafting_special_mapextending");
        for (Map.Entry<String, String> recipe : recipeTypes.entrySet()) {
            Files.writeString(recipes.resolve(recipe.getKey()),
                "{\"type\":\"" + recipe.getValue() + "\"}");
        }

        Path output = root.resolve("out");
        Files.createDirectories(output);
        Path result = new DataPackTransformer("26.2").transformPack(pack, output);

        try (ZipFile transformed = new ZipFile(result.toFile())) {
            for (Map.Entry<String, String> recipe : recipeTypes.entrySet()) {
                ZipEntry entry = transformed.getEntry(
                    "data/example/recipes/" + recipe.getKey());
                try (var input = transformed.getInputStream(entry)) {
                    String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(content.contains("\"type\":\"" + recipe.getValue() + "\""));
                }
            }
        }
    }

    @Test
    void currentFormatStagedDataPackStillReceivesContentMigrations(
            @TempDir Path gameDirectory) throws Exception {
        Path staged = gameDirectory.resolve("retromod-input/datapacks/current.zip");
        Files.createDirectories(staged.getParent());
        writeZip(staged, Map.of(
            "pack.mcmeta",
            "{\"pack\":{\"min_format\":[107,1],\"max_format\":[107,1],"
                + "\"description\":\"current\"}}",
            "data/example/worldgen/template_pool/commented.json",
            "{\n// old comment\n\"elements\": [],\n}",
            "data/example/tags/entity_type/potions.json",
            "{\"values\":[\"minecraft:potion\",]}"));

        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();
        manager.processAll();

        Path output = gameDirectory.resolve(
            "retromod-output/datapacks/current-retromod.zip");
        try (ZipFile transformed = new ZipFile(output.toFile())) {
            String worldgen = readZipEntry(transformed,
                "data/example/worldgen/template_pool/commented.json");
            assertFalse(worldgen.contains("// old comment"));
            assertFalse(worldgen.contains(",\n}"));

            String potionTag = readZipEntry(transformed,
                "data/example/tags/entity_type/potions.json");
            assertTrue(potionTag.contains("minecraft:splash_potion"));
            assertTrue(potionTag.contains("minecraft:lingering_potion"));
            assertFalse(potionTag.contains("\"minecraft:potion\""));
        }
        assertFalse(Files.exists(staged));
        assertTrue(Files.exists(gameDirectory.resolve(
            "retromod-input/datapacks/processed/current.zip")));
    }

    @Test
    void malformedFinalJsonLeavesStagedDataPackUnpublished(
            @TempDir Path gameDirectory) throws Exception {
        Path staged = gameDirectory.resolve("retromod-input/datapacks/broken.zip");
        Files.createDirectories(staged.getParent());
        writeZip(staged, Map.of(
            "pack.mcmeta",
            "{\"pack\":{\"min_format\":[107,1],\"max_format\":[107,1],"
                + "\"description\":\"broken\"}}",
            "data/example/loot_tables/broken.json",
            "{\"pools\":[{\"rolls\":1}]} trailing"));
        byte[] stagedBefore = Files.readAllBytes(staged);

        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();
        manager.processAll();

        assertArrayEquals(stagedBefore, Files.readAllBytes(staged));
        assertFalse(Files.exists(gameDirectory.resolve(
            "retromod-output/datapacks/broken-retromod.zip")));
        assertFalse(Files.exists(gameDirectory.resolve(
            "retromod-input/datapacks/processed/broken.zip")));
        assertNoPackTransactionDirectories(gameDirectory);
    }

    @Test
    void malformedUtf8FinalJsonLeavesStagedDataPackUnpublished(
            @TempDir Path gameDirectory) throws Exception {
        Path staged = gameDirectory.resolve("retromod-input/datapacks/bad-utf8");
        Path dataFile = staged.resolve("data/example/loot_tables/bad.json");
        Files.createDirectories(dataFile.getParent());
        Files.writeString(staged.resolve("pack.mcmeta"),
            "{\"pack\":{\"min_format\":[107,1],\"max_format\":[107,1],"
                + "\"description\":\"bad utf8\"}}");
        Files.write(dataFile, malformedUtf8Json("{\"value\":\"~\"}"));
        byte[] metadataBefore = Files.readAllBytes(staged.resolve("pack.mcmeta"));
        byte[] dataBefore = Files.readAllBytes(dataFile);

        ResourceManager manager = new ResourceManager("26.2", gameDirectory);
        manager.ensureFolders();
        manager.processAll();

        assertArrayEquals(metadataBefore, Files.readAllBytes(staged.resolve("pack.mcmeta")));
        assertArrayEquals(dataBefore, Files.readAllBytes(dataFile));
        assertFalse(Files.exists(gameDirectory.resolve(
            "retromod-output/datapacks/bad-utf8-retromod.zip")));
        assertFalse(Files.exists(gameDirectory.resolve(
            "retromod-input/datapacks/processed/bad-utf8")));
        assertNoPackTransactionDirectories(gameDirectory);
    }

    @Test
    void deeplyNestedFinalDataPackJsonIsRejected(@TempDir Path root) throws Exception {
        Path pack = root.resolve("deep-data-pack");
        Path dataFile = pack.resolve("data/example/predicates/deep.json");
        Files.createDirectories(dataFile.getParent());
        Files.writeString(pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":61,\"description\":\"deep\"}}");
        Files.writeString(dataFile,
            "[".repeat(257) + "0" + "]".repeat(257));

        Path output = Files.createDirectory(root.resolve("out"));
        IOException failure = assertThrows(IOException.class,
            () -> new DataPackTransformer("26.2").transformPack(pack, output));

        assertTrue(failure.getMessage().contains("nesting exceeds 256 levels"));
        assertEquals(0, directoryEntryCount(output));
    }

    private static void writeZip(Path path, Map<String, String> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static void writeRawZip(Path path, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private static byte[] malformedUtf8Json(String jsonWithMarker) {
        byte[] bytes = jsonWithMarker.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '~') {
                bytes[index] = (byte) 0x80;
                return bytes;
            }
        }
        throw new IllegalArgumentException("Malformed UTF-8 fixture has no marker");
    }

    private static void createDirectorySymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException error) {
            Assumptions.abort("Symbolic links are not available: " + error.getMessage());
        }
    }

    private static long directoryEntryCount(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.count();
        }
    }

    private static void assertNoPackTransactionDirectories(Path gameDirectory)
            throws IOException {
        for (Path directory : new Path[]{
                gameDirectory.resolve("resourcepacks"),
                gameDirectory.resolve("retromod-output/datapacks"),
                gameDirectory.resolve("retromod-input/resourcepacks/processed"),
                gameDirectory.resolve("retromod-input/datapacks/processed")}) {
            if (!Files.isDirectory(directory)) continue;
            try (var entries = Files.list(directory)) {
                assertFalse(entries.anyMatch(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith(".retromod-pack-publish-")
                        || name.startsWith(".retromod-pack-txn-");
                }), "pack transaction staging was not cleaned: " + directory);
            }
        }
    }

    private static JsonObject readMetadata(Path zipPath) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry("pack.mcmeta"))) {
                return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            }
        }
    }

    private static String readZipEntry(ZipFile zip, String name) throws IOException {
        try (var input = zip.getInputStream(zip.getEntry(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path transformDataPack(Path source, Path output, String target) throws IOException {
        Files.createDirectories(output);
        return new DataPackTransformer(target).transformPack(source, output);
    }

    private static void assertLegacyDataMetadata(Path result, int expectedFormat) throws IOException {
        JsonObject metadata = readMetadata(result).getAsJsonObject("pack");
        assertEquals(expectedFormat, metadata.get("pack_format").getAsInt());
        assertFalse(metadata.has("min_format"));
        assertFalse(metadata.has("max_format"));
    }
}
