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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every text Retromod's screens show was translated from the English pinned here. */
class ScreenTranslationsTest {

    // The en_us text each translation was last checked against. When the English changes, every
    // other lang file needs that key updated or deleted, so Minecraft falls back to English.
    private static final Map<String, String> CHECKED_ENGLISH = Map.ofEntries(
            entry("retromod.main.title", "Retromod"),
            entry("retromod.main.subtitle", "Choose old mod jars to update for this Minecraft version"),
            entry("retromod.main.add_mods", "Choose Mods…"),
            entry("retromod.main.open_folder", "Open retromod-input/"),
            entry("retromod.main.inplace_status", "%s from cache, %s after restart, %s failed"),
            entry("retromod.settings.title", "Retromod Settings"),
            entry("retromod.settings.done", "Done"),
            entry("retromod.settings.on", "§aOn"),
            entry("retromod.settings.off", "§cOff"),
            entry("retromod.settings.next_launch", "Takes effect on the next launch"),
            entry("retromod.settings.use_aot", "Cache transformed mods"),
            entry("retromod.settings.polyfills_enabled", "Replace removed APIs"),
            entry("retromod.settings.verify_transforms", "Check transformed mods"),
            entry("retromod.settings.write_translated_jars", "Write translated jars to mods/"),
            entry("retromod.settings.status.verified", "§aVerified build§r"),
            entry("retromod.settings.status.modified", "§eModified / unofficial build§r"),
            entry("retromod.settings.status.impostor", "§cNot Retromod (manifest mismatch)§r"),
            entry("retromod.settings.status.unknown", "§7Authenticity unknown§r"),
            entry("retromod.restart.converted", "Mods converted for this Minecraft version: %s\n"
                    + "Restart Minecraft to load the converted mods.\n\n"
                    + "Click Yes to close Minecraft, then relaunch from your launcher."),
            entry("retromod.restart.cache", "Mods translated for this Minecraft version: %s\n"
                    + "Restart Minecraft to load the translated mods. Your files in mods/ were not changed.\n\n"
                    + "Click Yes to close Minecraft, then relaunch from your launcher."));

    @Test
    void englishMatchesTheTextTranslationsWereCheckedAgainst() throws Exception {
        JsonObject english = JsonParser.parseString(
                Files.readString(LangKeysTest.LANG.resolve("en_us.json"))).getAsJsonObject();

        for (var checked : CHECKED_ENGLISH.entrySet()) {
            String key = checked.getKey();
            String current = english.has(key) ? english.get(key).getAsString() : null;
            assertEquals(checked.getValue(), current, "en_us.json changed " + key + " from '"
                    + checked.getValue() + "' to '" + current + "'. Update or delete " + key
                    + " in every other lang file, then update CHECKED_ENGLISH.");
        }
    }

    @Test
    void noLanguageKeepsTheOldDropTextOrJargonLabels() throws Exception {
        // The screen ignores dropped files, and the settings rows no longer use these terms.
        List<String> dropWords = List.of("drop", "suelta", "déposez", "trascina",
                "ドロップ", "끌어", "upuść", "solte",
                "перетащите", "拖入");
        try (Stream<Path> listing = Files.list(LangKeysTest.LANG)) {
            for (Path file : listing.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject lang = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                String subtitle = text(lang, "retromod.main.subtitle");
                for (String word : dropWords) {
                    assertFalse(subtitle.contains(word), file.getFileName()
                            + " still tells players to drop jars: " + subtitle);
                }
                assertFalse(text(lang, "retromod.settings.use_aot").contains("aot"),
                        file.getFileName() + " still labels use_aot as AOT compilation");
                assertFalse(text(lang, "retromod.settings.polyfills_enabled").contains("polyfill"),
                        file.getFileName() + " still labels polyfills_enabled as Polyfills");
            }
        }
    }

    private static String text(JsonObject lang, String key) {
        return lang.has(key) ? lang.get(key).getAsString().toLowerCase(Locale.ROOT) : "";
    }

    @Test
    void everyKeyTheScreensShowIsChecked() throws Exception {
        // A new row, or a later use of an unused key, must not reach players unchecked.
        for (String key : LangKeysTest.keysTheGuiRequests()) {
            assertTrue(CHECKED_ENGLISH.containsKey(key), key + " is shown by Retromod's screens but "
                    + "has no checked English. Check its translations, then add it to CHECKED_ENGLISH.");
        }
    }
}
