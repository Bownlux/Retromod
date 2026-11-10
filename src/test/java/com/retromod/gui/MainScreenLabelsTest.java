/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Retromod screen's labels name what their buttons do, in every language. */
class MainScreenLabelsTest {

    private static final Path LANG = Path.of("src/main/resources/assets/retromod/lang");

    @Test
    void openFolderLabelNamesTheFolderItOpens() throws Exception {
        List<Path> files;
        try (Stream<Path> listing = Files.list(LANG)) {
            files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertTrue(files.contains(LANG.resolve("en_us.json")), "en_us.json is missing from " + LANG);

        // The button opens retromod-input/, not mods/, so every label has to say so.
        for (Path file : files) {
            JsonObject lang = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertTrue(lang.has("retromod.main.open_folder"),
                    file.getFileName() + " has no retromod.main.open_folder");
            String label = lang.get("retromod.main.open_folder").getAsString();
            assertTrue(label.contains("retromod-input"),
                    file.getFileName() + " labels the folder button '" + label
                            + "', which does not name retromod-input/");
        }
    }
}
