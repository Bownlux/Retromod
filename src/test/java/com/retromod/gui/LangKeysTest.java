/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.security.SignatureVerifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every language carries the English keys and format slots, and every key the GUI asks for exists. */
class LangKeysTest {

    static final Path LANG = Path.of("src/main/resources/assets/retromod/lang");
    private static final Path GUI_SOURCES = Path.of("src/main/java/com/retromod/gui");
    private static final List<String> LANGUAGES = List.of("de_de", "en_us", "es_es", "fr_fr",
            "it_it", "ja_jp", "ko_kr", "pl_pl", "pt_br", "ru_ru", "zh_cn");
    private static final Pattern LANG_KEY_LITERAL =
            Pattern.compile("\"(retromod\\.[a-z_]+\\.[a-z_.]*[a-z_])\"");

    @Test
    void everyLanguageHasExactlyTheEnglishKeys() throws IOException {
        Map<String, JsonObject> files = loadAll();
        assertEquals(new TreeSet<>(LANGUAGES), files.keySet(), "the lang folder lists other files");
        TreeSet<String> english = new TreeSet<>(files.get("en_us").keySet());

        for (var file : files.entrySet()) {
            assertEquals(english, new TreeSet<>(file.getValue().keySet()),
                    file.getKey() + ".json must have exactly the keys of en_us.json");
        }
    }

    @Test
    void everyTranslationKeepsTheEnglishFormatSlots() throws IOException {
        Map<String, JsonObject> files = loadAll();
        JsonObject english = files.get("en_us");

        for (var file : files.entrySet()) {
            for (String key : english.keySet()) {
                if (!file.getValue().has(key)) continue;
                assertEquals(slots(english.get(key).getAsString()),
                        slots(file.getValue().get(key).getAsString()),
                        file.getKey() + ".json has a different number of %s slots in " + key);
            }
        }
    }

    @Test
    void everyKeyTheGuiRequestsExists() throws IOException {
        JsonObject english = loadAll().get("en_us");

        for (String key : keysTheGuiRequests()) {
            assertTrue(english.has(key),
                    "the GUI asks for " + key + ", which en_us.json does not define");
        }
    }

    /** The lang keys Retromod's screens and prompts can show. */
    static TreeSet<String> keysTheGuiRequests() throws IOException {
        TreeSet<String> keys = new TreeSet<>();
        // Keys written out in the GUI sources, such as retromod.main.title.
        try (Stream<Path> sources = Files.list(GUI_SOURCES)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher literal = LANG_KEY_LITERAL.matcher(Files.readString(source));
                while (literal.find()) keys.add(literal.group(1));
            }
        }
        // Toggle labels are built from config keys, and each loader family shows a different set.
        Properties forgeFamily = new Properties();
        forgeFamily.setProperty("retromod.boot.owner", "retromod.jar");
        for (Properties loader : List.of(new Properties(), forgeFamily)) {
            for (String key : ConfigScreenFactory.visibleToggleKeys(loader)) {
                keys.add("retromod.settings." + key);
            }
        }
        // Keys chosen at runtime.
        for (SignatureVerifier.Status status : SignatureVerifier.Status.values()) {
            keys.add(ConfigScreenFactory.statusKey(status));
        }
        keys.add(RestartPrompt.messageKey(true));
        keys.add(RestartPrompt.messageKey(false));
        return keys;
    }

    private static Map<String, JsonObject> loadAll() throws IOException {
        Map<String, JsonObject> files = new TreeMap<>();
        try (Stream<Path> listing = Files.list(LANG)) {
            for (Path file : listing.filter(p -> p.toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString().replace(".json", "");
                JsonElement parsed = JsonParser.parseString(Files.readString(file));
                assertTrue(parsed.isJsonObject(), name + ".json is not a JSON object");
                // Minecraft reads every value as a string, so one nested value breaks the whole file.
                for (var entry : parsed.getAsJsonObject().entrySet()) {
                    assertTrue(entry.getValue().isJsonPrimitive()
                                    && entry.getValue().getAsJsonPrimitive().isString(),
                            name + ".json: " + entry.getKey() + " is not a string");
                }
                files.put(name, parsed.getAsJsonObject());
            }
        }
        return files;
    }

    private static int slots(String text) {
        return text.split("%s", -1).length - 1;
    }
}
