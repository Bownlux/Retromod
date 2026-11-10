/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.gui.RetromodScreen.ModResult;
import com.retromod.gui.RetromodScreen.ModResult.Status;
import com.retromod.inplace.InPlaceLaunch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The results screen says why mods failed, and asks only when closing the game helps. */
class RetromodScreenResultsTest {

    private static final String QUESTION = "Close Minecraft now?";

    @Test
    void failureReasonsAndInPlaceLinesAppear() {
        String text = RetromodScreen.resultsText(List.of(
                new ModResult("broken.jar", Status.FAILED, "ZipException: zip END header not found"),
                new ModResult("forge.jar", Status.UPDATED, RetromodScreen.inPlaceResultLine(
                        "forge.jar", InPlaceLaunch.ChosenOutcome.NEXT_LAUNCH), true),
                new ModResult("plain.jar", Status.UPDATED, "Updated as plain-retromod.jar")));

        assertTrue(text.contains("ZipException: zip END header not found"), text);
        assertTrue(text.contains("one more restart"), "the in-place line explains the restarts: " + text);
        assertFalse(text.contains("Updated as plain-retromod.jar"), "a plain update needs no detail");
    }

    @Test
    void theQuestionAppearsOnlyWhenAJarWaitsInMods() {
        List<ModResult> problemsOnly = List.of(
                new ModResult("broken.jar", Status.FAILED, "IOException: denied"));
        assertFalse(RetromodScreen.needsRestart(problemsOnly));
        assertFalse(RetromodScreen.resultsText(problemsOnly).contains(QUESTION),
                "closing the game loads nothing when no jar was added");

        List<ModResult> updated = List.of(new ModResult("a.jar", Status.UPDATED, "Updated as a.jar"));
        assertTrue(RetromodScreen.needsRestart(updated));
        assertTrue(RetromodScreen.resultsText(updated).endsWith(
                QUESTION + " Relaunch it from your launcher to load these mods."));

        List<ModResult> copied = List.of(new ModResult("b.jar", Status.UNCHANGED, "No changes needed"));
        assertTrue(RetromodScreen.needsRestart(copied), "an unchanged jar was still copied to mods/");
    }

    @Test
    void aLongListIsCapped() {
        List<ModResult> results = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            results.add(new ModResult("mod-" + i + ".jar", Status.FAILED, "reason " + i));
        }

        String text = RetromodScreen.resultsText(results);

        assertTrue(text.contains("Failed: 25"), "the count still covers every mod");
        assertTrue(text.contains("mod-10.jar") && !text.contains("mod-11.jar"), text);
        assertTrue(text.contains("and 15 more. logs/latest.log lists every result."), text);
    }

    @Test
    void resultsAndFallbackWaitForTheClientThread() {
        List<Runnable> queued = new ArrayList<>();
        Executor client = queued::add;
        RetromodScreen screen = new RetromodScreen(client, null);

        // Both run on picker threads, and only Minecraft's main thread may change screens.
        screen.showResults(List.of(new ModResult("a.jar", Status.UPDATED, "Updated as a.jar")));
        screen.showInputFolderInstead();

        assertEquals(2, queued.size(), "the results and the fallback screen must be queued");
    }
}
