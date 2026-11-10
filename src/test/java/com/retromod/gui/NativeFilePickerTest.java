/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** What the picker keeps from tinyfd's and SDL's answers, read without native code. */
class NativeFilePickerTest {

    @Test
    void tinyfdAnswerKeepsOnlyTheJars() {
        List<File> jars = NativeFilePicker.jarsFromTinyfd("/mods/a.jar|/notes/notes.txt|/mods/B.JAR");

        assertEquals(List.of(new File("/mods/a.jar"), new File("/mods/B.JAR")), jars);
    }

    @Test
    void aPathWithSpacesSurvives() {
        assertEquals(List.of(new File("/My Mods/old mod 1.0.jar")),
                NativeFilePicker.jarsFromTinyfd("/My Mods/old mod 1.0.jar"));
    }

    @Test
    void anEmptyTinyfdAnswerIsACancel() {
        assertTrue(NativeFilePicker.jarsFromTinyfd(null).isEmpty(), "null is tinyfd's cancel");
        assertTrue(NativeFilePicker.jarsFromTinyfd("").isEmpty());
    }

    @Test
    void sdlListReaderStopsAtTheFirstNullPointer() {
        // A fake char** at 1000 with 8-byte pointers: two paths, the end marker, then stray memory.
        Map<Long, Long> pointers = Map.of(1000L, 5000L, 1008L, 6000L, 1016L, 0L, 1024L, 7000L);
        Map<Long, String> strings = Map.of(5000L, "/mods/a.jar", 6000L, "/mods/b.txt",
                7000L, "/past/the/end.jar");

        List<String> paths = NativeFilePicker.readPathList(1000L, 8, pointers::get, strings::get);

        assertEquals(List.of("/mods/a.jar", "/mods/b.txt"), paths);
        assertEquals(List.of(new File("/mods/a.jar")), NativeFilePicker.keepJars(paths));
    }

    @Test
    void anEmptySdlListIsACancel() {
        List<String> paths = NativeFilePicker.readPathList(1000L, 8, at -> 0L,
                at -> fail("an empty list has no strings to read"));

        assertTrue(paths.isEmpty());
    }

    @Test
    void anSdlListWithoutAnEndMarkerIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> NativeFilePicker.readPathList(1000L, 8, at -> 42L, at -> "/mods/a.jar"));
    }

    @Test
    void withoutTinyfdOrSdlThePickerReportsUnavailable() {
        // The unit test JVM has no LWJGL, like a host that ships neither dialog.
        List<String> reasons = new ArrayList<>();
        NativeFilePicker.open(task -> fail("no dialog may be started"), Path.of("."),
                new NativeFilePicker.Listener() {
            @Override
            public void chosen(List<File> jars) {
                fail("nothing can be chosen without a picker");
            }

            @Override
            public void unavailable(String reason) {
                reasons.add(reason);
            }
        });

        assertEquals(1, reasons.size(), "the fallback must run exactly once");
        assertTrue(reasons.get(0).contains("tinyfd"), reasons.get(0));
    }

    @Test
    void sdlWinsWhereTheHostRunsSdlVideo() {
        // 26.3 runs its window on SDL and ships no tinyfd.
        assertEquals(NativeFilePicker.Dialog.SDL, NativeFilePicker.choose(true, true, false));
        assertEquals(NativeFilePicker.Dialog.SDL, NativeFilePicker.choose(true, true, true),
                "SDL is preferred even where tinyfd ships too");
    }

    @Test
    void tinyfdServesAGlfwWindow() {
        // Up to 26.2 the window is GLFW's, so an SDL jar there has no video for its dialog.
        assertEquals(NativeFilePicker.Dialog.TINYFD, NativeFilePicker.choose(true, false, true));
        assertEquals(NativeFilePicker.Dialog.TINYFD, NativeFilePicker.choose(false, false, true));
    }

    @Test
    void withoutEitherDialogNothingOpens() {
        assertEquals(NativeFilePicker.Dialog.NONE, NativeFilePicker.choose(true, false, false));
        assertEquals(NativeFilePicker.Dialog.NONE, NativeFilePicker.choose(false, false, false));
    }
}
