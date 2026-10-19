/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiRuntime;
import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Rebuilds jars translated under another loader API runtime setting and swaps them in on exit. */
public final class FabricTranslationIdentity {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final long MAX_MANIFEST_BYTES = 2L * 1024 * 1024;

    /**
     * Where rebuilt jars wait, relative to the game folder. Fabric loaded every jar in
     * {@code mods/} before pre-launch, so a jar there is replaced only after the game exits.
     */
    public static final String PENDING_FOLDER = "retromod-input/pending";

    /** Rebuilt jar in the pending folder, mapped to the {@code mods/} jar it replaces on exit. */
    private static final Map<Path, Path> REPLACED_ON_EXIT = new ConcurrentHashMap<>();
    private static final AtomicBoolean EXIT_HOOK_ADDED = new AtomicBoolean();

    /** Translates one original jar into {@code outputDir} and returns the new jar, or null. */
    @FunctionalInterface
    public interface Retranslator {
        Path retranslate(Path original, Path outputDir) throws IOException;
    }

    /** A translated jar whose identity differs from the current one. */
    public record Mismatch(Path jar, String found, String expected) {}

    private FabricTranslationIdentity() {}

    /** Every translated jar in {@code modsDir} whose identity is not {@code expected}. */
    public static List<Mismatch> findMismatches(Path modsDir, String expected) {
        List<Mismatch> mismatches = new ArrayList<>();
        for (Path jar : listJars(modsDir)) {
            Manifest manifest = readManifest(jar);
            if (manifest == null) continue;
            var attributes = manifest.getMainAttributes();
            if (!"true".equals(attributes.getValue("Retromod-Transformed"))) continue;
            String found = attributes.getValue(LoaderApiRuntime.MANIFEST_ATTRIBUTE);
            if (!Objects.equals(found, expected)) {
                mismatches.add(new Mismatch(jar, found, expected));
            }
        }
        return mismatches;
    }

    /** The original of a translated {@code <name>-retromod.jar}, if a processed folder has it. */
    public static Path originalFor(Path translatedJar, List<Path> processedDirs) {
        String name = translatedJar.getFileName().toString();
        if (!name.endsWith("-retromod.jar")) return null;
        String originalName = name.substring(0, name.length() - "-retromod.jar".length()) + ".jar";
        for (Path dir : processedDirs) {
            Path candidate = dir.resolve(originalName);
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
        return null;
    }

    /**
     * Brings {@code modsDir} to the {@code expected} identity without touching a jar this launch
     * loaded. First it settles what an earlier launch left in {@code pendingDir}, then it rebuilds
     * each other mismatched jar from its original into {@code pendingDir}. Every rebuilt jar is
     * scheduled to replace its {@code mods/} jar when the game exits.
     *
     * @return the file names that need the usual restart: rebuilt this launch, or installed now
     *         because the jar they replace was not loaded
     */
    public static List<String> retranslateMismatched(Path modsDir, Path pendingDir,
            List<Path> processedDirs, String expected, Retranslator retranslator) {
        List<String> restart = new ArrayList<>(settlePending(modsDir, pendingDir, expected));
        for (Mismatch mismatch : findMismatches(modsDir, expected)) {
            String jarName = mismatch.jar().getFileName().toString();
            if (REPLACED_ON_EXIT.containsValue(mismatch.jar().toAbsolutePath().normalize())) continue;
            Path original = originalFor(mismatch.jar(), processedDirs);
            if (original == null) {
                // The jar still runs: helpers ship in every build, so this is advice, not an error.
                LOGGER.info("{} was translated with loader API runtime {}, but this launch uses {}. "
                        + "Its original is not in retromod-input/processed/, so Retromod cannot "
                        + "translate it again. Put the original jar in retromod-input/ to update it.",
                        jarName, describe(mismatch.found()), describe(expected));
                continue;
            }
            LOGGER.info("Translating {} again: it was built with loader API runtime {}, and this "
                    + "launch uses {}. The new copy replaces it when the game closes.",
                    jarName, describe(mismatch.found()), describe(expected));
            if (stageRebuild(mismatch.jar(), original, pendingDir, retranslator)) {
                restart.add(jarName);
            }
        }
        return restart;
    }

    /** Whether the {@code mods/} jar named {@code jarFileName} is replaced when the game exits. */
    public static boolean isReplacedOnExit(String jarFileName) {
        return REPLACED_ON_EXIT.values().stream()
                .anyMatch(target -> target.getFileName().toString().equals(jarFileName));
    }

    /** Adds the exit hook that installs the scheduled jars, once per process. */
    public static void installScheduledOnExit() {
        if (REPLACED_ON_EXIT.isEmpty() || !EXIT_HOOK_ADDED.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(FabricTranslationIdentity::installScheduled,
                "Retromod rebuilt jars"));
    }

    /**
     * Moves each scheduled jar over the one it replaces. A jar the game still holds open, which
     * Windows refuses to replace, is hidden so the next launch skips it and installs the new copy.
     */
    static void installScheduled() {
        for (Map.Entry<Path, Path> entry : List.copyOf(REPLACED_ON_EXIT.entrySet())) {
            Path staged = entry.getKey();
            Path target = entry.getValue();
            REPLACED_ON_EXIT.remove(staged);
            try {
                moveReplacing(staged, target);
            } catch (IOException e) {
                hideUntilNextLaunch(target, e);
            }
        }
    }

    /** Translates {@code original} into the pending folder and schedules it to replace {@code jar}. */
    private static boolean stageRebuild(Path jar, Path original, Path pendingDir,
            Retranslator retranslator) {
        String jarName = jar.getFileName().toString();
        Path workDir = null;
        try {
            // A work folder beside mods/ keeps the health checker's game folder right.
            Path gameDir = jar.toAbsolutePath().getParent().getParent();
            workDir = Files.createTempDirectory(gameDir, ".retromod-rebuild-");
            Path output = retranslator.retranslate(original, workDir);
            if (output == null || !output.getFileName().toString().equals(jarName)) {
                LOGGER.warn("Retromod could not translate {} again; keeping the current jar. Put "
                        + "its original in retromod-input/ to update it.", jarName);
                return false;
            }
            Files.createDirectories(pendingDir);
            ZipSecurity.validateNotSymlink(pendingDir);
            Path staged = pendingDir.resolve(jarName);
            moveReplacing(output, staged);
            ModHealthChecker.relocateTransformedPath(output, jar);
            REPLACED_ON_EXIT.put(staged.toAbsolutePath().normalize(), jar.toAbsolutePath().normalize());
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Retromod could not translate {} again ({}); keeping the current jar",
                    jarName, e.getMessage());
            return false;
        } finally {
            if (workDir != null) {
                ModHealthChecker.forgetTransformedPathsUnder(workDir);
                deleteRecursively(workDir);
            }
        }
    }

    /**
     * Settles the jars an earlier launch left in {@code pendingDir}. A jar that no longer matches
     * the setting is dropped; one whose {@code mods/} jar is gone or hidden is installed now; one
     * whose {@code mods/} jar this launch loaded waits for the game to exit again.
     *
     * @return the file names installed now, which need a restart
     */
    private static List<String> settlePending(Path modsDir, Path pendingDir, String expected) {
        List<String> installed = new ArrayList<>();
        for (Path staged : listJars(pendingDir)) {
            if (REPLACED_ON_EXIT.containsKey(staged.toAbsolutePath().normalize())) continue;
            String jarName = staged.getFileName().toString();
            Path target = modsDir.resolve(jarName);
            if (!Objects.equals(identityOf(staged), expected)) {
                deleteQuietly(staged);
                continue;
            }
            boolean loaded = Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !isHidden(target);
            try {
                if (!loaded) {
                    // A hidden jar is the one an earlier exit could not replace; clear the flag first.
                    unhide(target);
                    moveReplacing(staged, target);
                    LOGGER.info("Installed the rebuilt {} from {}/", jarName, PENDING_FOLDER);
                    installed.add(jarName);
                } else if (Objects.equals(identityOf(target), expected)) {
                    deleteQuietly(staged);
                } else {
                    REPLACED_ON_EXIT.put(staged.toAbsolutePath().normalize(),
                            target.toAbsolutePath().normalize());
                    LOGGER.warn("mods/{} still runs the build from before the loader API runtime "
                            + "setting changed. Retromod replaces it with {}/{} when the game "
                            + "closes. If this message comes back after a restart, close the game, "
                            + "delete mods/{}, and start it again.", jarName, PENDING_FOLDER,
                            jarName, jarName);
                }
            } catch (IOException e) {
                LOGGER.warn("Retromod could not install the rebuilt {} ({}). Close the game and "
                        + "move {}/{} into mods/.", jarName, e.getMessage(), PENDING_FOLDER, jarName);
            }
        }
        installed.addAll(restoreOrphanedHiddenJars(modsDir, pendingDir));
        return installed;
    }

    /**
     * Shows a translated jar an earlier exit hid again when its rebuilt copy is gone, so the mod
     * loads after the restart instead of vanishing.
     */
    private static List<String> restoreOrphanedHiddenJars(Path modsDir, Path pendingDir) {
        List<String> restored = new ArrayList<>();
        if (!isWindows()) return restored;
        for (Path jar : listJars(modsDir)) {
            String jarName = jar.getFileName().toString();
            if (!isHidden(jar) || jarName.startsWith(".") || Files.exists(pendingDir.resolve(jarName))) {
                continue;
            }
            Manifest manifest = readManifest(jar);
            if (manifest == null
                    || !"true".equals(manifest.getMainAttributes().getValue("Retromod-Transformed"))) {
                continue;
            }
            unhide(jar);
            LOGGER.warn("{} was hidden for a rebuild whose copy is gone from {}/. It loads again "
                    + "after a restart.", jarName, PENDING_FOLDER);
            restored.add(jarName);
        }
        return restored;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    private static void hideUntilNextLaunch(Path target, IOException cause) {
        // Only Windows locks an open jar, and only there does Fabric read the DOS hidden flag.
        DosFileAttributeView dos = isWindows()
                ? Files.getFileAttributeView(target, DosFileAttributeView.class) : null;
        try {
            if (dos == null) throw cause;
            dos.setHidden(true);
            LOGGER.info("{} is still open, so the next launch skips it and installs the rebuilt "
                    + "copy from {}/", target.getFileName(), PENDING_FOLDER);
        } catch (IOException e) {
            LOGGER.warn("Retromod could not replace {} ({}). Close the game and move {}/{} into "
                    + "mods/.", target.getFileName(), e.getMessage(), PENDING_FOLDER,
                    target.getFileName());
        }
    }

    /** Whether Fabric skips {@code jar} as hidden: a dot name, or the DOS hidden flag. */
    private static boolean isHidden(Path jar) {
        try {
            return Files.isHidden(jar);
        } catch (IOException e) {
            return false;
        }
    }

    private static void unhide(Path jar) {
        if (!Files.exists(jar, LinkOption.NOFOLLOW_LINKS)) return;
        DosFileAttributeView dos = Files.getFileAttributeView(jar, DosFileAttributeView.class);
        if (dos == null) return;
        try {
            dos.setHidden(false);
        } catch (IOException e) {
            LOGGER.debug("Could not clear the hidden flag of {}: {}", jar, e.getMessage());
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Path> listJars(Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOGGER.debug("Could not list {} for the translation identity check: {}",
                    dir, e.getMessage());
            return List.of();
        }
    }

    /** The loader API runtime identity in {@code jar}'s manifest, or null when it has none. */
    private static String identityOf(Path jar) {
        Manifest manifest = readManifest(jar);
        return manifest == null ? null
                : manifest.getMainAttributes().getValue(LoaderApiRuntime.MANIFEST_ATTRIBUTE);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("Could not delete {}: {}", file, e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(FabricTranslationIdentity::deleteQuietly);
        } catch (IOException e) {
            LOGGER.debug("Could not delete {}: {}", dir, e.getMessage());
        }
    }

    private static String describe(String identity) {
        return identity == null ? "off" : identity;
    }

    private static Manifest readManifest(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(JarFile.MANIFEST_NAME);
            if (entry == null) return null;
            try (InputStream input = file.getInputStream(entry)) {
                return new Manifest(new ByteArrayInputStream(
                        ZipSecurity.safeReadAllBytes(input, MAX_MANIFEST_BYTES)));
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Forgets every scheduled jar, for tests. */
    static void forgetScheduledForTesting() {
        REPLACED_ON_EXIT.clear();
    }
}
