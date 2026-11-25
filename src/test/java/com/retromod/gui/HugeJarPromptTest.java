/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.ArchiveAdmission;
import com.retromod.util.ArchiveCheck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The title-screen question names the jar, its size, and the check result, with a warning when it failed. */
class HugeJarPromptTest {

    @AfterEach
    void reset() {
        HugeJarPrompt.resetForTesting();
    }

    private static ArchiveAdmission.Decision held(String name, String problem) {
        ArchiveCheck.Result check = new ArchiveCheck.Result(problem, 41_233, 31L << 30, 12L << 30, false);
        return new ArchiveAdmission.Decision(Path.of("game", "retromod-input", name),
                ArchiveAdmission.Tier.HUGE, ArchiveAdmission.Verdict.HOLD, 31L << 30, 12L << 30, check,
                "ab".repeat(32), null, "held");
    }

    private static JsonObject english() throws IOException {
        return JsonParser.parseString(Files.readString(LangKeysTest.LANG.resolve("en_us.json"))).getAsJsonObject();
    }

    private static int slots(String text) {
        return text.split("%s", -1).length - 1;
    }

    @Test
    @DisplayName("a passing jar's question fills every slot with its name, sizes, entries, and folder")
    void passingJarQuestion() throws IOException {
        ArchiveAdmission.Decision decision = held("Pixelmon.jar", null);
        Object[] arguments = HugeJarPrompt.messageArguments(decision);

        assertEquals("retromod.huge_jar.message", HugeJarPrompt.messageKey(decision));
        assertEquals(slots(english().get(HugeJarPrompt.messageKey(decision)).getAsString()), arguments.length);
        assertEquals(List.of("Pixelmon.jar", "31.0 GB", "12.0 GB", "41,233", "retromod-input/"),
                List.of(arguments).stream().map(String::valueOf).toList());
    }

    @Test
    @DisplayName("a failed check gets the warning title and text, which say it may be a zip bomb")
    void failedCheckQuestionWarns() throws IOException {
        ArchiveAdmission.Decision decision = held("odd.jar", "entries a and b overlap in the file");

        assertEquals("retromod.huge_jar.unverified_title", HugeJarPrompt.titleKey(decision));
        String text = english().get(HugeJarPrompt.messageKey(decision)).getAsString();
        assertTrue(text.contains("zip bomb") && text.contains("trust"), text);
        assertEquals(slots(text), HugeJarPrompt.messageArguments(decision).length);
    }

    @Test
    @DisplayName("each held jar is asked about once, in the order it was found")
    void eachJarIsAskedOnce() {
        ArchiveAdmission.Decision first = held("first.jar", null);
        ArchiveAdmission.Decision second = held("second.jar", "duplicate");
        HugeJarPrompt.useHeldForTesting(() -> List.of(first, second));

        assertSame(first, HugeJarPrompt.nextToAsk());
        // Without a running client the screen cannot open, but the jar still counts as asked.
        HugeJarPrompt.maybeShow(null);
        assertSame(second, HugeJarPrompt.nextToAsk());
        HugeJarPrompt.maybeShow(null);
        assertNull(HugeJarPrompt.nextToAsk());
        assertFalse(HugeJarPrompt.maybeShow(null), "nothing is left to ask");
    }
}
