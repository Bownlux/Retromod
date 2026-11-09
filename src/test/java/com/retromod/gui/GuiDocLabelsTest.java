/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every control the In-Game UI page names in bold is a label the game shows. */
class GuiDocLabelsTest {

    private static final Path GUI_DOC = Path.of("docs/gui.md");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");

    @Test
    void everyBoldLabelInTheGuiPageIsAnEnglishLabel() throws Exception {
        Set<String> shown = englishLabels();
        List<String> named = labelsNamedIn(Files.readString(GUI_DOC));
        assertFalse(named.isEmpty(), GUI_DOC + " names no controls in bold");

        for (String label : named) {
            assertTrue(shown.contains(label), GUI_DOC + " names a control the game does not show: '"
                    + label + "'. Use the label from en_us.json, or remove it from the page.");
        }
    }

    /** Each part of every bold UI path, such as Retromod and Choose Mods in "Retromod > Choose Mods…". */
    private static List<String> labelsNamedIn(String page) {
        List<String> labels = new ArrayList<>();
        Matcher bold = BOLD.matcher(page);
        while (bold.find()) {
            for (String part : bold.group(1).split(" > ")) labels.add(comparable(part));
        }
        return labels;
    }

    /** The en_us.json values as the player reads them. */
    private static Set<String> englishLabels() throws Exception {
        Set<String> labels = new TreeSet<>();
        String english = Files.readString(LangKeysTest.LANG.resolve("en_us.json"));
        for (var entry : JsonParser.parseString(english).getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            // A section sign starts a color code, which the player never sees as text.
            labels.add(comparable(value.getAsString().replaceAll("§.", "")));
        }
        return labels;
    }

    /** Trims spaces and a trailing ellipsis, so "Choose Mods..." in a page matches "Choose Mods…". */
    private static String comparable(String label) {
        String trimmed = label.strip();
        if (trimmed.endsWith("…")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.endsWith("...")) trimmed = trimmed.substring(0, trimmed.length() - 3);
        return trimmed.strip();
    }
}
