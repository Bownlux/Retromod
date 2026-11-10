/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.resource.PathPackResources;
import org.slf4j.LoggerFactory;

/**
 * Forge's folder pack, which Forge 26.2 removed, built with its old constructor and extended with a
 * {@code resolve} override the way Create and Tacz do. The summary checks build the packs during
 * construction; the pack finder event repeats the read where mods build their packs, so that result
 * is in the log.
 */
final class LegacyPathPacks {

    private static final String MOD_ID = "retromod_test_mod_forge";
    private static final String META = "{\"pack\":{\"pack_format\":15,\"description\":\"Retromod legacy path pack\"}}";

    private LegacyPathPacks() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(LegacyPathPacks::onAddPackFinders);
        } catch (Throwable t) {
            log("An AddPackFindersEvent listener for old folder packs registers (construction)",
                    "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** {@code new PathPackResources(id, true, folder)} reads its pack.mcmeta and reports that folder. */
    static boolean builtPackReads() throws IOException {
        Path folder = writePack(Files.createTempDirectory("retromod-legacy-pack"));
        try {
            PathPackResources pack = new PathPackResources(MOD_ID + "_legacy_plain", true, folder);
            return read(pack.getRootResource("pack.mcmeta")).contains("Retromod") && folder.equals(pack.getSource());
        } finally {
            deleteTree(folder);
        }
    }

    /** A subclass that reads from a folder inside its source finds its files through its own resolve. */
    static boolean subclassReadsThroughResolve() throws IOException {
        Path outer = Files.createTempDirectory("retromod-legacy-pack");
        try {
            writePack(outer.resolve("inner"));
            NestedPack pack = new NestedPack(MOD_ID + "_legacy_nested", outer, "inner");
            return read(pack.getRootResource("pack.mcmeta")).contains("Retromod")
                    && pack.getResource(PackType.SERVER_DATA, new ResourceLocation(MOD_ID, "legacy_marker.json")) != null
                    && pack.getNamespaces(PackType.SERVER_DATA).contains(MOD_ID)
                    && outer.equals(pack.getSource());
        } finally {
            deleteTree(outer);
        }
    }

    // Mods build their packs while the host collects pack finders, once per pack type.
    private static void onAddPackFinders(AddPackFindersEvent event) {
        String type = event.getPackType() == PackType.SERVER_DATA ? "data" : "resources";
        String result;
        try {
            result = builtPackReads() && subclassReadsThroughResolve() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        log("Old folder packs built in AddPackFindersEvent read pack.mcmeta (" + type + ")", result);
    }

    private static Path writePack(Path folder) throws IOException {
        Path data = folder.resolve("data").resolve(MOD_ID);
        Files.createDirectories(data);
        Files.writeString(folder.resolve("pack.mcmeta"), META);
        Files.writeString(data.resolve("legacy_marker.json"), "{}");
        return folder;
    }

    // Each check runs once per pack type, so its folder goes away when the check is done.
    // A folder that cannot be removed stays in the temp directory and does not change the result.
    private static void deleteTree(Path folder) {
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    private static String read(IoSupplier<InputStream> supplier) throws IOException {
        if (supplier == null) return "";
        try (InputStream in = supplier.get()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void log(String description, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", description, result);
    }

    /** Like Create's ModFilePackResources: the files live in a folder inside the pack's source. */
    static final class NestedPack extends PathPackResources {
        private final String inner;

        NestedPack(String packId, Path outer, String inner) {
            super(packId, true, outer);
            this.inner = inner;
        }

        @Override
        protected Path resolve(String... paths) {
            Path path = getSource().resolve(inner);
            for (String segment : paths) {
                path = path.resolve(segment);
            }
            return path;
        }
    }
}
