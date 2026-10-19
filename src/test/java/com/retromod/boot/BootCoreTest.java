/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-independent coverage of the bootstrap steps. The locators themselves only add the
 * extracted runtime through loader API, which the server runs cover.
 */
class BootCoreTest {

    @TempDir
    Path tmp;

    private static byte[] runtimeJar(String marker) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "retromod");
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new JarEntry("com/retromod/core/Marker.txt"));
            jar.write(marker.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Writes a bootstrap jar; {@code declaredSha} lets a test lie about the runtime hash. */
    private static Path bootJar(Path file, String version, String minecraft, String generation,
            byte[] runtime, String declaredSha) throws IOException {
        Files.createDirectories(file.getParent());
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Implementation-Title", "Retromod");
        main.putValue("Implementation-Version", version);
        main.putValue("Retromod-Target-MC", minecraft);
        main.putValue("Retromod-Loader", "neoforge");
        main.putValue("Retromod-Boot-Generation", generation);
        main.putValue("Retromod-Runtime-Sha256", declaredSha);
        try (OutputStream out = Files.newOutputStream(file);
             JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry(BootCore.RUNTIME_ENTRY));
            jar.write(runtime);
            jar.closeEntry();
        }
        return file;
    }

    private static Path bootJar(Path file, String version, String minecraft, String generation,
            byte[] runtime) throws Exception {
        return bootJar(file, version, minecraft, generation, runtime, sha256(runtime));
    }

    private Path gameDir() throws IOException {
        return Files.createDirectories(tmp.resolve("game/mods")).getParent();
    }

    private static List<String> listNames(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("Retromod versions order snapshots below candidates below the release")
    void versionsOrderLikeReleases() {
        assertTrue(BootCore.compareVersions("1.4.0-snapshot.2", "1.4.0-snapshot.10") < 0);
        assertTrue(BootCore.compareVersions("1.4.0-snapshot.10", "1.4.0-rc.1") < 0);
        assertTrue(BootCore.compareVersions("1.4.0-rc.1", "1.4.0") < 0);
        assertTrue(BootCore.compareVersions("1.3.4", "1.4.0-snapshot.1") < 0);
        assertTrue(BootCore.compareVersions("1.10.0", "1.9.9") > 0);
        assertEquals(0, BootCore.compareVersions("1.4.0", "1.4.0+26.3"));
    }

    @Test
    @DisplayName("with several Retromod files, the newest one for this Minecraft version wins")
    void choosesTheNewestJarForTheHost() {
        BootCore.BootJar old = new BootCore.BootJar(Path.of("a.jar"), "1.4.0-snapshot.1",
                "26.3", "neoforge", "BC", "0".repeat(64));
        BootCore.BootJar newer = new BootCore.BootJar(Path.of("b.jar"), "1.4.0-snapshot.2",
                "26.3", "neoforge", "BC", "1".repeat(64));
        BootCore.BootJar wrongHost = new BootCore.BootJar(Path.of("c.jar"), "1.5.0",
                "1.21.1", "neoforge", "BC", "2".repeat(64));

        assertEquals(newer, BootCore.choose(List.of(old, newer, wrongHost), "26.3"));
        assertEquals(wrongHost, BootCore.choose(List.of(old, newer, wrongHost), null),
                "without a known host version the newest file wins");
    }

    @Test
    @DisplayName("only top-level bootstrap jars of the right generation are found")
    void findsOnlyMatchingBootstrapJars() throws Exception {
        Path game = gameDir();
        Path mods = game.resolve("mods");
        bootJar(mods.resolve("retromod-a.jar"), "1.4.0", "26.3", "BC", runtimeJar("a"));
        bootJar(mods.resolve("retromod-forge.jar"), "1.4.0", "26.3", "A", runtimeJar("b"));
        bootJar(mods.resolve("nested/retromod-c.jar"), "1.4.0", "26.3", "BC", runtimeJar("c"));
        Files.write(mods.resolve("othermod.jar"), runtimeJar("plain mod"));
        Files.writeString(mods.resolve("broken.jar"), "not a jar");

        List<BootCore.BootJar> found = BootCore.findBootJars(mods, "BC");
        assertEquals(1, found.size());
        assertEquals("retromod-a.jar", found.get(0).path().getFileName().toString());
    }

    @Test
    @DisplayName("the runtime is extracted once into a folder that holds only that jar")
    void extractsTheRuntimeIntoItsOwnFolder() throws Exception {
        Path game = gameDir();
        byte[] runtime = runtimeJar("runtime v1");
        Path boot = bootJar(game.resolve("mods/retromod.jar"), "1.4.0-snapshot.2", "26.3",
                "BC", runtime);
        BootCore.BootJar jar = BootCore.readBootJar(boot).orElseThrow();

        Path extracted = BootCore.extractRuntime(game, jar);
        assertArrayEquals(runtime, Files.readAllBytes(extracted));
        assertEquals(List.of(extracted.getFileName().toString()),
                listNames(extracted.getParent()));
        assertEquals("retromod-1.4.0-snapshot.2+26.3.jar", extracted.getFileName().toString());
        assertTrue(extracted.startsWith(game.toRealPath().resolve("retromod-cache/runtime")));
        assertTrue(BootCore.displayPath(game, extracted).startsWith("retromod-cache/runtime/"),
                "log lines name a runtime inside the game directory relative to it");

        Files.writeString(extracted.getParent().resolve("stray.jar"), "would load as a mod");
        Files.writeString(extracted, "damaged");
        Path again = BootCore.extractRuntime(game, jar);
        assertEquals(extracted, again);
        assertArrayEquals(runtime, Files.readAllBytes(again), "a damaged copy is replaced");
        assertEquals(List.of(again.getFileName().toString()), listNames(again.getParent()),
                "the folder a loader scans holds nothing but the runtime");
    }

    @Test
    @DisplayName("a new Retromod build gets a new folder and the old one is removed")
    void newBuildReplacesTheOldRuntimeFolder() throws Exception {
        Path game = gameDir();
        Path boot = game.resolve("mods/retromod.jar");
        bootJar(boot, "1.4.0-snapshot.2", "26.3", "BC", runtimeJar("v1"));
        Path first = BootCore.extractRuntime(game, BootCore.readBootJar(boot).orElseThrow());

        bootJar(boot, "1.4.0-snapshot.2", "26.3", "BC", runtimeJar("v2, same version"));
        Path second = BootCore.extractRuntime(game, BootCore.readBootJar(boot).orElseThrow());

        assertNotEquals(first.getParent(), second.getParent());
        assertFalse(Files.exists(first.getParent()));
        assertEquals(List.of(second.getParent().getFileName().toString()),
                listNames(second.getParent().getParent()));
    }

    @Test
    @DisplayName("a runtime that does not match its manifest hash is never published")
    void damagedBootstrapIsRefused() throws Exception {
        Path game = gameDir();
        Path boot = bootJar(game.resolve("mods/retromod.jar"), "1.4.0", "26.3", "BC",
                runtimeJar("real"), "a".repeat(64));
        BootCore.BootJar jar = BootCore.readBootJar(boot).orElseThrow();

        IOException failure = assertThrows(IOException.class,
                () -> BootCore.extractRuntime(game, jar));
        assertTrue(failure.getMessage().contains("download it again"));
        Path runtimeRoot = game.resolve("retromod-cache/runtime");
        try (Stream<Path> files = Files.walk(runtimeRoot)) {
            assertTrue(files.noneMatch(f -> f.toString().endsWith(".jar")
                    || f.toString().endsWith(".tmp")));
        }
    }

    @Test
    @DisplayName("a link inside the game directory is refused; a linked game directory is not")
    void linksBelowTheGameDirectoryAreRefused() throws Exception {
        Path game = gameDir();
        Path boot = bootJar(game.resolve("mods/retromod.jar"), "1.4.0", "26.3", "BC",
                runtimeJar("r"));
        BootCore.BootJar jar = BootCore.readBootJar(boot).orElseThrow();
        Path linkedGame = tmp.resolve("linked-game");
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        try {
            Files.createSymbolicLink(linkedGame, game);
            Files.createSymbolicLink(game.resolve("retromod-cache"), elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable", e);
        }
        assertThrows(IOException.class, () -> BootCore.extractRuntime(linkedGame, jar));
        assertEquals(List.of(), listNames(elsewhere), "nothing is written through the link");

        Files.delete(game.resolve("retromod-cache"));
        Path extracted = BootCore.extractRuntime(linkedGame, jar);
        assertTrue(extracted.startsWith(game.toRealPath()));
    }

    @Test
    @DisplayName("a linked retromod-cache/ still loads Retromod, from a private temporary folder")
    void linkedCacheFolderFallsBackToATemporaryRuntime() throws Exception {
        Path game = gameDir();
        Path mods = game.resolve("mods");
        byte[] runtime = runtimeJar("runtime behind a linked cache");
        bootJar(mods.resolve("retromod.jar"), "1.4.0", "26.3", "BC", runtime);
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        try {
            Files.createSymbolicLink(game.resolve("retromod-cache"), elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("symbolic links are unavailable", e);
        }

        Optional<BootCore.Prepared> prepared = BootCore.prepare(game, mods, "BC", "26.3", null,
                new Properties(), BootLog.standardOutput());

        assertTrue(prepared.isPresent(), "rewrite mode must keep working, and in-place mode "
                + "must be able to say why it is off");
        Path extracted = prepared.get().runtimeJar();
        try {
            assertFalse(extracted.startsWith(game.toRealPath()),
                    "nothing is written through the link");
            assertArrayEquals(runtime, Files.readAllBytes(extracted));
            assertEquals(List.of(extracted.getFileName().toString()),
                    listNames(prepared.get().runtimeDirectory()),
                    "loaders before NeoForge 20.5 load every jar in the folder");
            assertEquals(List.of(), listNames(elsewhere));
            assertEquals(extracted.toAbsolutePath().normalize().toString(),
                    BootCore.displayPath(game, extracted),
                    "a runtime outside the game directory is logged by its full path");
        } finally {
            Files.deleteIfExists(extracted);
            Files.deleteIfExists(prepared.get().runtimeDirectory());
        }
    }

    @Test
    @DisplayName("only the first bootstrap in a launch loads Retromod, using the newest file")
    void firstBootstrapClaimsTheLaunch() throws Exception {
        Path game = gameDir();
        Path mods = game.resolve("mods");
        bootJar(mods.resolve("retromod-1.4.0-snapshot.1+26.3.jar"), "1.4.0-snapshot.1", "26.3",
                "BC", runtimeJar("old"));
        bootJar(mods.resolve("retromod-1.4.0-snapshot.2+26.3.jar"), "1.4.0-snapshot.2", "26.3",
                "BC", runtimeJar("new"));
        Properties launch = new Properties();

        Optional<BootCore.Prepared> first = BootCore.prepare(game, mods, "BC", "26.3", null,
                launch, BootLog.standardOutput());
        Optional<BootCore.Prepared> second = BootCore.prepare(game, mods, "BC", "26.3", null,
                launch, BootLog.standardOutput());

        assertTrue(first.isPresent());
        assertEquals("1.4.0-snapshot.2", first.get().source().version());
        assertEquals(1, first.get().passedOver().size());
        assertArrayEquals(runtimeJar("new"), Files.readAllBytes(first.get().runtimeJar()));
        assertTrue(second.isEmpty(), "a second Retromod file must not add a second runtime");
        assertNotNull(launch.getProperty(BootCore.OWNER_PROPERTY));
        assertEquals(List.of(mods.resolve("retromod-1.4.0-snapshot.2+26.3.jar"),
                        mods.resolve("retromod-1.4.0-snapshot.1+26.3.jar")),
                first.get().allBootstraps().stream().map(BootCore.BootJar::path).toList(),
                "every bootstrap is listed by the raw mods/ path the loader's scan compares");
    }

    @Test
    @DisplayName("no Retromod file means no runtime and no claim")
    void missingBootstrapLeavesTheLaunchAlone() throws IOException {
        Path game = gameDir();
        Properties launch = new Properties();
        assertTrue(BootCore.prepare(game, game.resolve("mods"), "BC", null, null, launch,
                BootLog.standardOutput()).isEmpty());
        assertNull(launch.getProperty(BootCore.OWNER_PROPERTY));
    }

    @Test
    @DisplayName("code-source URLs from every loader generation resolve to the jar file")
    void codeSourceUrlsResolve() throws Exception {
        Path jar = Files.writeString(tmp.resolve("retromod boot+26.3.jar"), "x");
        String encoded = jar.toUri().getRawPath();

        assertEquals(jar, BootCore.jarPathFromUrl(jar.toUri().toURL()));
        assertEquals(jar, BootCore.jarPathFromUrl(new URL("jar:" + jar.toUri() + "!/")));
        assertEquals(jar, BootCore.jarPathFromUrl(new URL(null, "union:" + encoded + "%2342!/",
                new java.net.URLStreamHandler() {
                    @Override
                    protected java.net.URLConnection openConnection(URL url) {
                        throw new UnsupportedOperationException();
                    }
                })));
        assertNull(BootCore.jarPathFromUrl(tmp.toUri().toURL()), "a folder is not a jar");
    }
}
