/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The opener hands one URI to Minecraft's openUri on every host shape, and never uses AWT. */
class McOpenerTest {

    private static final URI LINK = URI.create("https://github.com/Bownlux/Retromod/issues");

    /** Every call a stand-in receives, as "method name: argument". */
    static final List<String> CALLS = new ArrayList<>();

    static void record(String method, Object argument) {
        CALLS.add(method + ": " + argument);
    }

    /** 26.3: a static Blaze3D.openUri beside openPath, which calls it. */
    public static final class Blaze3d263 {
        public static void openUri(URI uri) {
            record("Blaze3D.openUri", uri);
        }

        public static void openPath(Path path) {
            record("Blaze3D.openPath", path);
        }
    }

    /** 26.3's platform enum, which opens nothing. */
    public enum Os263 { MAC }

    public static final class Util263 {
        public static Os263 getPlatform() {
            return Os263.MAC;
        }
    }

    /** 26.1 and 26.2 have a Blaze3D class that opens nothing. */
    public static final class Blaze3dWithoutOpener {
    }

    /** Util.OS from 1.21 to 26.2, with a constant body like the real enum. */
    public enum MojangOs {
        WINDOWS {
        };

        public void openUri(URI uri) {
            record("openUri(URI)", uri);
        }

        public void openUri(String uri) {
            record("openUri(String)", uri);
        }

        public void openFile(File file) {
            record("openFile", file);
        }

        public void openPath(Path path) {
            record("openPath", path);
        }
    }

    public static final class MojangUtil {
        public static MojangOs getPlatform() {
            return MojangOs.WINDOWS;
        }
    }

    /** Util.OS on 1.20.1: openUrl instead of openPath. */
    public enum MojangOs1201 {
        LINUX;

        public void openUri(URI uri) {
            record("openUri(URI)", uri);
        }

        public void openUrl(URL url) {
            record("openUrl", url);
        }

        public void openFile(File file) {
            record("openFile", file);
        }
    }

    public static final class MojangUtil1201 {
        public static MojangOs1201 getPlatform() {
            return MojangOs1201.LINUX;
        }
    }

    /** Fabric before 26.1 runs intermediary names: class_156.method_668 and class_158.method_673. */
    public enum IntermediaryOs {
        LINUX;

        public void method_673(URI uri) {
            record("method_673", uri);
        }

        public void method_672(File file) {
            record("method_672", file);
        }

        public void method_670(String uri) {
            record("method_670", uri);
        }
    }

    public static final class IntermediaryUtil {
        public static IntermediaryOs method_668() {
            return IntermediaryOs.LINUX;
        }
    }

    /** Forge 1.20 to 1.20.4 runs SRG names. */
    public enum SrgOs {
        OSX;

        public void m_137648_(URI uri) {
            record("m_137648_", uri);
        }

        public void m_137644_(File file) {
            record("m_137644_", file);
        }
    }

    public static final class SrgUtil {
        public static SrgOs m_137581_() {
            return SrgOs.OSX;
        }
    }

    /** A Fabric dev run uses Yarn names, where every overload is called open. */
    public enum YarnOs {
        LINUX;

        public void open(URI uri) {
            record("open(URI)", uri);
        }

        public void open(File file) {
            record("open(File)", file);
        }

        public void open(String uri) {
            record("open(String)", uri);
        }
    }

    public static final class YarnUtil {
        public static YarnOs getOperatingSystem() {
            return YarnOs.LINUX;
        }
    }

    public static final class UtilWithoutPlatform {
    }

    @BeforeEach
    @AfterEach
    void reset() {
        CALLS.clear();
        McOpener.forgetClientForTesting();
    }

    @Test
    void blaze3dOpenUriWinsOn26_3() {
        assertTrue(McOpener.openWith(LINK, "", Blaze3d263.class, Util263.class));

        assertEquals(List.of("Blaze3D.openUri: " + LINK), CALLS,
                "openUri is what the sandbox routes; openPath would only call it");
    }

    @Test
    void theUriOverloadServes26_2AndOlderMojangHosts() {
        assertTrue(McOpener.openWith(LINK, "", Blaze3dWithoutOpener.class, MojangUtil.class));
        assertTrue(McOpener.openWith(LINK, "", null, MojangUtil1201.class));

        assertEquals(List.of("openUri(URI): " + LINK, "openUri(URI): " + LINK), CALLS,
                "never the String, File, Path, or URL overload");
    }

    @Test
    void intermediarySrgAndYarnNamesReachTheUriOverload() {
        assertTrue(McOpener.openWith(LINK, "", null, IntermediaryUtil.class));
        assertTrue(McOpener.openWith(LINK, "", null, SrgUtil.class));
        assertTrue(McOpener.openWith(LINK, "", null, YarnUtil.class));

        assertEquals(List.of("method_673: " + LINK, "m_137648_: " + LINK, "open(URI): " + LINK),
                CALLS);
    }

    @Test
    void aHostWithoutAnOpenerReportsFalse() {
        assertFalse(McOpener.openWith(LINK, "", Blaze3dWithoutOpener.class, Util263.class),
                "26.3's platform enum alone opens nothing");
        assertFalse(McOpener.openWith(LINK, "", null, UtilWithoutPlatform.class));
        assertFalse(McOpener.openWith(LINK, "", null, null));
        assertTrue(CALLS.isEmpty());
    }

    @Test
    void aFolderBecomesAFileUriWithoutAHost(@TempDir Path folder) {
        URI uri = McOpener.folderUri(folder.resolve("x").resolve(".."));

        assertEquals("file", uri.getScheme());
        assertNull(uri.getHost(), "a sandbox opens only file URIs without a host");
        assertTrue(uri.getPath().endsWith("/"), "an existing folder ends in a slash: " + uri);
        assertEquals(folder.toAbsolutePath().normalize(), Path.of(uri));
    }

    @Test
    void onlyWebLinksAreOpened() {
        String issue = "https://github.com/Bownlux/Retromod/issues/new?title=Bug%20Report";
        assertEquals(URI.create(issue), McOpener.webUri(issue), "a link passes through unchanged");
        assertNotNull(McOpener.webUri("http://example.com/"));
        assertNull(McOpener.webUri("file:///etc/"));
        assertNull(McOpener.webUri("javascript:alert(1)"));
        assertNull(McOpener.webUri("not a link"));
    }

    @Test
    void beforeTheClientRunsItOnlyLogsWhatToOpen(@TempDir Path folder) {
        String log = captureErr(() -> {
            assertFalse(McOpener.openFolder(folder));
            assertFalse(McOpener.openLink(LINK.toString()));
        });

        assertTrue(log.contains("Open this folder in your file manager: "
                + folder.toAbsolutePath().normalize()), log);
        assertTrue(log.contains("Open this link in your browser: " + LINK), log);
        assertTrue(log.contains("only in a running Minecraft client"), log);
    }

    @Test
    void aRunningClientWithoutMinecraftClassesStillLogsThePath(@TempDir Path folder) {
        // The test JVM has no Minecraft, like a host whose opener Retromod does not know.
        McOpener.markClientRunning();

        String log = captureErr(() -> assertFalse(McOpener.openFolder(folder)));

        assertTrue(log.contains("Open this folder in your file manager: "
                + folder.toAbsolutePath().normalize()), log);
    }

    @Test
    void noRuntimeClassUsesAwtDesktop() throws Exception {
        // Launcher sandboxes route only Minecraft's openUri, never AWT's Desktop.
        Path classes = Path.of(McOpener.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                if (contains(file, "java/awt/Desktop")) offenders.add(classes.relativize(file).toString());
            }
        }

        assertTrue(offenders.isEmpty(), "classes that use AWT Desktop: " + offenders);
    }

    private static boolean contains(Path classFile, String text) throws IOException {
        return new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1).contains(text);
    }

    /** slf4j-simple looks up System.err on every write, so swapping it captures the log. */
    private static String captureErr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
