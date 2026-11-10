/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Escape, rebuilds, dropped files, and override names of Retromod's screens, over stand-ins. */
class ScreenClassGeneratorTest {

    /** Stands in for Minecraft's Component. */
    public static final class StubText {
    }

    /** Stands in for Minecraft's Screen: it counts its own close and re-runs init on a rebuild. */
    public static class StubScreen {
        public int defaultCloses;
        public int defaultDrops;

        public StubScreen(StubText title) {
        }

        protected void init() {
        }

        public void onClose() {
            defaultCloses++;
        }

        /** Minecraft hands files dropped on the window to the open screen here. */
        public void onFilesDrop(List<Path> files) {
            defaultDrops++;
        }

        public void removed() {
        }

        /** What Screen.rebuildWidgets does after a resize or when the screen is shown again. */
        public void rebuild() {
            init();
        }
    }

    private static final class OneClassLoader extends ClassLoader {
        OneClassLoader() {
            super(ScreenClassGeneratorTest.class.getClassLoader());
        }

        Class<?> define(byte[] bytecode) {
            return defineClass("com.retromod.gui.RetromodGenScreen", bytecode, 0, bytecode.length);
        }
    }

    private static StubScreen newScreen() throws Exception {
        byte[] bytecode = ScreenClassGenerator.generateBytecode(StubScreen.class, StubText.class);
        Class<?> generated = new OneClassLoader().define(bytecode);
        return (StubScreen) generated.getConstructor(StubText.class).newInstance(new StubText());
    }

    /** Screen as Fabric runs it before 26.1, under intermediary names. */
    public static class IntermediaryScreen {
        public int defaultCloses;

        public IntermediaryScreen(StubText title) {
        }

        protected void method_25426() {
        }

        public void method_25419() {
            defaultCloses++;
        }

        public void method_29638(List<Path> files) {
        }

        /** What Screen.rebuildWidgets does, under its intermediary init name. */
        public void rebuild() {
            method_25426();
        }
    }

    /** A Screen with init and onClose but no drop hook. */
    public static class ScreenWithoutDrop {
        public ScreenWithoutDrop(StubText title) {
        }

        protected void init() {
        }

        public void onClose() {
        }
    }

    /** A Screen without the methods Retromod overrides. */
    public static class ScreenWithoutLifecycle {
        public ScreenWithoutLifecycle(StubText title) {
        }

        public void init(int width, int height) {
        }
    }

    @Test
    void generatedScreenOverridesIntermediaryNames() throws Exception {
        byte[] bytecode = ScreenClassGenerator.generateBytecode(IntermediaryScreen.class,
                StubText.class);
        Class<?> generated = new OneClassLoader().define(bytecode);

        assertEquals(generated, generated.getDeclaredMethod("method_25426").getDeclaringClass());
        assertEquals(generated, generated.getDeclaredMethod("method_25419").getDeclaringClass());
        assertThrows(NoSuchMethodException.class, () -> generated.getDeclaredMethod("init"),
                "a Mojang-named override would override nothing on an intermediary host");

        // Without a close callback, the override falls back to the intermediary super method.
        IntermediaryScreen screen = (IntermediaryScreen) generated.getConstructor(StubText.class)
                .newInstance(new StubText());
        AtomicInteger inits = new AtomicInteger();
        ScreenClassGenerator.register(screen, s -> inits.incrementAndGet(), null);
        screen.rebuild();
        screen.method_25419();

        assertEquals(1, inits.get(), "init must reach Retromod's widgets");
        assertEquals(1, screen.defaultCloses);
    }

    @Test
    void generatedScreenOverridesMojangNames() throws Exception {
        byte[] bytecode = ScreenClassGenerator.generateBytecode(StubScreen.class, StubText.class);
        Class<?> generated = new OneClassLoader().define(bytecode);

        assertEquals(generated, generated.getDeclaredMethod("init").getDeclaringClass());
        assertEquals(generated, generated.getDeclaredMethod("onClose").getDeclaringClass());
        assertEquals(generated, generated.getDeclaredMethod("onFilesDrop", List.class)
                .getDeclaringClass());
    }

    @Test
    void generatedScreenTakesDropsUnderTheIntermediaryName() throws Exception {
        byte[] bytecode = ScreenClassGenerator.generateBytecode(IntermediaryScreen.class,
                StubText.class);
        Class<?> generated = new OneClassLoader().define(bytecode);

        assertEquals(generated, generated.getDeclaredMethod("method_29638", List.class)
                .getDeclaringClass());
    }

    @Test
    void droppedFilesReachTheDropCallbackWithTheScreen() throws Exception {
        StubScreen screen = newScreen();
        List<Object> screens = new ArrayList<>();
        List<Path> received = new ArrayList<>();
        ScreenClassGenerator.register(screen, s -> { }, null, (s, files) -> {
            screens.add(s);
            received.addAll(files);
        });

        screen.onFilesDrop(List.of(Path.of("/mods/old.jar"), Path.of("/mods/older.jar")));

        assertEquals(List.of(Path.of("/mods/old.jar"), Path.of("/mods/older.jar")), received);
        assertSame(screen, screens.get(0), "the callback gets the screen instead of capturing it");
        assertEquals(0, screen.defaultDrops, "the callback took the files");
    }

    @Test
    void withoutADropCallbackMinecraftsOwnHandlingRuns() throws Exception {
        StubScreen screen = newScreen();
        ScreenClassGenerator.register(screen, s -> { }, null);

        screen.onFilesDrop(List.of(Path.of("/mods/old.jar")));

        assertEquals(1, screen.defaultDrops);
    }

    @Test
    void aScreenWithoutTheDropHookStillOpens() throws Exception {
        byte[] bytecode = ScreenClassGenerator.generateBytecode(ScreenWithoutDrop.class,
                StubText.class);
        assertNotNull(bytecode, "dropping is optional; init and onClose are what a screen needs");
        Class<?> generated = new OneClassLoader().define(bytecode);
        assertThrows(NoSuchMethodException.class,
                () -> generated.getDeclaredMethod("onFilesDrop", List.class));
    }

    @Test
    void screenWithoutInitOrOnCloseGetsNoClass() {
        // A class whose overrides match nothing would open as a blank screen.
        assertNull(ScreenClassGenerator.generateBytecode(ScreenWithoutLifecycle.class,
                StubText.class));
    }

    @Test
    void escapeRunsTheCloseCallbackInsteadOfTheDefaultClose() throws Exception {
        StubScreen screen = newScreen();
        AtomicInteger closes = new AtomicInteger();
        ScreenClassGenerator.register(screen, s -> { }, closes::incrementAndGet);

        screen.onClose();

        assertEquals(1, closes.get(), "Escape must run the screen's close callback");
        assertEquals(0, screen.defaultCloses, "the callback already chose where Escape goes");
    }

    @Test
    void escapeWithoutACloseCallbackFallsBackToTheDefaultClose() throws Exception {
        StubScreen screen = newScreen();
        ScreenClassGenerator.register(screen, s -> { }, null);

        screen.onClose();

        assertEquals(1, screen.defaultCloses, "without a callback, Minecraft's own close must run");
    }

    @Test
    void aFailingCloseCallbackFallsBackToTheDefaultClose() throws Exception {
        StubScreen screen = newScreen();
        ScreenClassGenerator.register(screen, s -> { }, () -> {
            throw new IllegalStateException("broken close");
        });

        screen.onClose();

        assertEquals(1, screen.defaultCloses, "a broken callback must not trap the player");
    }

    @Test
    void aScreenStillBuildsItsWidgetsAfterEscape() throws Exception {
        StubScreen screen = newScreen();
        AtomicInteger inits = new AtomicInteger();
        ScreenClassGenerator.register(screen, s -> inits.incrementAndGet(), () -> { });

        screen.rebuild();
        screen.onClose();
        // A resize rebuilds the widgets of the screen that is still shown.
        screen.rebuild();

        assertEquals(2, inits.get(), "the init callback must survive Escape");
    }

    @Test
    void aScreenShownAgainAfterRemovedStillBuildsItsWidgets() throws Exception {
        StubScreen screen = newScreen();
        AtomicInteger inits = new AtomicInteger();
        ScreenClassGenerator.register(screen, s -> inits.incrementAndGet(), () -> { });

        // Retromod screen, then settings, then Done: the same instance is removed and shown again.
        screen.rebuild();
        screen.removed();
        screen.rebuild();

        assertEquals(2, inits.get(), "a screen shown again must get its buttons back");
    }
}
