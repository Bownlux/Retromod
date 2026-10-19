/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/** Finds the Retromod bootstrap jar in mods/ and unpacks the runtime it carries. Uses only the JDK. */
public final class BootCore {

    // Forge and NeoForge never construct a @Mod from a jar that declares a mod locator service
    // (#90, #102). So players install a small bootstrap that declares the locator, and Retromod
    // itself travels inside it at this path. The boot package stays out of the runtime jar:
    // modlauncher would split it across layers, and NeoForge 21.9+ ClassLoadingGuardian rejects
    // game-content packages loaded by a parent of the game class loader.
    public static final String RUNTIME_ENTRY = "META-INF/retromod/runtime.jar";

    /** Claimed by the first bootstrap that runs, so a second Retromod file does nothing. */
    public static final String OWNER_PROPERTY = "retromod.boot.owner";

    // Manifest attributes that mark and describe a bootstrap jar.
    static final String TITLE_ATTRIBUTE = "Implementation-Title";
    static final String VERSION_ATTRIBUTE = "Implementation-Version";
    static final String TARGET_ATTRIBUTE = "Retromod-Target-MC";
    static final String LOADER_ATTRIBUTE = "Retromod-Loader";
    static final String GENERATION_ATTRIBUTE = "Retromod-Boot-Generation";
    static final String RUNTIME_SHA_ATTRIBUTE = "Retromod-Runtime-Sha256";

    // The runtime unpacks into retromod-cache/runtime/, where the loader picks it up as an
    // ordinary mod file.
    static final String CACHE_ROOT = "retromod-cache";
    static final String RUNTIME_FOLDER = "runtime";

    /** Far above any real Retromod jar; stops a damaged entry from filling the disk. */
    private static final long MAX_RUNTIME_BYTES = 256L * 1024 * 1024;

    private BootCore() {}

    /** A Retromod bootstrap jar found on disk. */
    public record BootJar(Path path, String version, String targetMinecraft, String loader,
            String generation, String runtimeSha256) {

        /** A short label for log lines. */
        public String describe() {
            return path.getFileName() + " (Retromod " + version + " for " + loader + " "
                    + targetMinecraft + ")";
        }
    }

    /** The runtime jar ready to hand to the loader, and the files that were passed over. */
    public record Prepared(BootJar source, Path runtimeJar, Path runtimeDirectory,
            List<BootJar> passedOver) {

        /** Every Retromod bootstrap found for this launch, the chosen one first. */
        public List<BootJar> allBootstraps() {
            List<BootJar> all = new ArrayList<>();
            all.add(source);
            all.addAll(passedOver);
            return all;
        }
    }

    /** Finds, picks, and unpacks the Retromod runtime for one locator, or returns empty. */
    public static Optional<Prepared> prepare(Path gameDir, Path modsDir, String generation,
            String hostMinecraft, Class<?> anchor, Properties properties, BootLog log) {
        Objects.requireNonNull(gameDir, "gameDir");
        Objects.requireNonNull(generation, "generation");
        // Only jars built for this locator's SPI generation count.
        List<BootJar> candidates = new ArrayList<>();
        if (modsDir != null) candidates.addAll(findBootJars(modsDir, generation));
        // When mods/ has no match, fall back to the jar the anchor class was loaded from.
        if (candidates.isEmpty() && anchor != null) {
            Path own = codeSourceJar(anchor);
            if (own != null) {
                readBootJar(own).filter(jar -> generation.equals(jar.generation()))
                        .ifPresent(candidates::add);
            }
        }
        if (candidates.isEmpty()) {
            log.error("Retromod could not find its own file in " + modsDir + ". Retromod is "
                    + "inactive for this launch. Reinstall the Retromod jar for this loader "
                    + "into mods/.");
            return Optional.empty();
        }

        // Claim ownership in the properties (normally the system properties), so when another
        // bootstrap already ran, this one does nothing.
        BootJar chosen = choose(candidates, hostMinecraft);
        String claim = chosen.path() + "|" + chosen.version();
        Object previous = properties.putIfAbsent(OWNER_PROPERTY, claim);
        if (previous != null) {
            log.info("Another Retromod bootstrap already loaded Retromod (" + previous
                    + "); this one does nothing.");
            return Optional.empty();
        }

        // Warn about extra Retromod files in mods/.
        List<BootJar> passedOver = new ArrayList<>(candidates);
        passedOver.remove(chosen);
        if (!passedOver.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (BootJar other : passedOver) {
                if (names.length() > 0) names.append(", ");
                names.append(other.path().getFileName());
            }
            log.warn("Found more than one Retromod file in mods/. Using "
                    + chosen.describe() + ". Delete the others: " + names + ".");
        }
        // Warn when the jar targets another Minecraft. hostMinecraft is null when the loader
        // does not report it.
        if (hostMinecraft != null && !hostMinecraft.equals(chosen.targetMinecraft())) {
            log.warn("This Retromod file is built for Minecraft " + chosen.targetMinecraft()
                    + ", but the game is Minecraft " + hostMinecraft + ". Install the Retromod "
                    + "jar for " + hostMinecraft + ".");
        }

        // Unpack into retromod-cache/runtime/. A damaged runtime stops here; any other
        // failure falls back to a temporary folder.
        Path runtime;
        try {
            runtime = extractRuntime(gameDir, chosen);
        } catch (DamagedRuntimeException e) {
            log.error("Retromod could not unpack its runtime: " + e.getMessage()
                    + ". Retromod is inactive for this launch.");
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            // Retromod sitting in mods/ always loaded before the bootstrap existed, whatever
            // retromod-cache/ was. The rewrite mode and the in-place report both need it.
            try {
                runtime = extractRuntimeToTemporaryFolder(chosen);
                log.warn("Retromod could not unpack its runtime into " + CACHE_ROOT + "/"
                        + RUNTIME_FOLDER + "/: " + e.getMessage() + ". It runs from "
                        + runtime.getParent() + " for this launch.");
            } catch (IOException | RuntimeException fallback) {
                log.error("Retromod could not unpack its runtime from "
                        + chosen.path().getFileName() + " into " + CACHE_ROOT + "/"
                        + RUNTIME_FOLDER + "/ (" + e.getMessage() + ") or into a temporary "
                        + "folder (" + fallback.getMessage() + "). Retromod is inactive for "
                        + "this launch. Check that both are writable and have free space, "
                        + "then restart.");
                return Optional.empty();
            }
        }
        return Optional.of(new Prepared(chosen, runtime, runtime.getParent(),
                List.copyOf(passedOver)));
    }

    /** Top-level jars in {@code modsDir} that are Retromod bootstraps for {@code generation}. */
    public static List<BootJar> findBootJars(Path modsDir, String generation) {
        List<BootJar> found = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) return found;
        try (Stream<Path> files = Files.list(modsDir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith(".")
                        || !name.toLowerCase(Locale.ROOT).endsWith(".jar")
                        || !Files.isRegularFile(file)) {
                    continue;
                }
                readBootJar(file).filter(jar -> generation.equals(jar.generation()))
                        .ifPresent(found::add);
            }
        } catch (IOException e) {
            // An unreadable mods folder leaves only the code-source fallback.
        }
        return found;
    }

    /** Reads the bootstrap manifest of one jar, or empty when it is not a Retromod bootstrap. */
    public static Optional<BootJar> readBootJar(Path file) {
        try (JarFile jar = new JarFile(file.toFile(), false)) {
            // A bootstrap carries the runtime entry, the Retromod title, and all three required
            // attributes.
            Manifest manifest = jar.getManifest();
            if (manifest == null || jar.getEntry(RUNTIME_ENTRY) == null) return Optional.empty();
            Attributes main = manifest.getMainAttributes();
            if (!"Retromod".equals(main.getValue(TITLE_ATTRIBUTE))) return Optional.empty();
            String generation = main.getValue(GENERATION_ATTRIBUTE);
            String version = main.getValue(VERSION_ATTRIBUTE);
            String runtimeSha = main.getValue(RUNTIME_SHA_ATTRIBUTE);
            if (generation == null || version == null || runtimeSha == null) {
                return Optional.empty();
            }
            return Optional.of(new BootJar(file, version.trim(),
                    String.valueOf(main.getValue(TARGET_ATTRIBUTE)).trim(),
                    String.valueOf(main.getValue(LOADER_ATTRIBUTE)).trim(), generation.trim(),
                    runtimeSha.trim().toLowerCase(Locale.ROOT)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Picks the jar to load, making the same choice in every bootstrap instance. */
    public static BootJar choose(List<BootJar> candidates, String hostMinecraft) {
        // A jar built for the running Minecraft wins, then the newest Retromod version, then the
        // first file name. A NeoForge 21.9+ host loads all early service jars into one class
        // loader, where the class from whichever jar comes first wins. Choosing by manifest
        // instead of by "own" jar keeps the newest file in charge either way.
        Comparator<BootJar> order = Comparator
                .comparing((BootJar jar) -> hostMinecraft == null
                        || !hostMinecraft.equals(jar.targetMinecraft()))
                .thenComparing(BootJar::version, (a, b) -> compareVersions(b, a))
                .thenComparing(jar -> jar.path().getFileName().toString());
        return candidates.stream().min(order).orElseThrow();
    }

    /** Orders Retromod versions such as 1.4.0-snapshot.2, 1.4.0-rc.1, and 1.4.0. */
    public static int compareVersions(String left, String right) {
        String[] a = splitVersion(left);
        String[] b = splitVersion(right);
        int core = compareDotted(a[0], b[0]);
        if (core != 0) return core;
        // A release sorts above its snapshots and candidates.
        if (a[1].isEmpty() || b[1].isEmpty()) {
            return a[1].isEmpty() == b[1].isEmpty() ? 0 : (a[1].isEmpty() ? 1 : -1);
        }
        // Two pre-releases compare by label first, then by the number after it.
        String[] preA = a[1].split("\\.", 2);
        String[] preB = b[1].split("\\.", 2);
        int rank = Integer.compare(preReleaseRank(preA[0]), preReleaseRank(preB[0]));
        if (rank != 0) return rank;
        return compareDotted(preA.length > 1 ? preA[1] : "0", preB.length > 1 ? preB[1] : "0");
    }

    /** Splits a version into its core and pre-release parts, dropping any + build suffix. */
    private static String[] splitVersion(String version) {
        String text = version == null ? "" : version.trim();
        int plus = text.indexOf('+');
        if (plus >= 0) text = text.substring(0, plus);
        int dash = text.indexOf('-');
        return dash < 0 ? new String[] {text, ""}
                : new String[] {text.substring(0, dash), text.substring(dash + 1)};
    }

    /** Ranks pre-release labels. An unknown label ranks with alpha. */
    private static int preReleaseRank(String label) {
        return switch (label.toLowerCase(Locale.ROOT)) {
            case "snapshot" -> 0;
            case "alpha" -> 1;
            case "beta" -> 2;
            case "rc" -> 3;
            default -> 1;
        };
    }

    /** Compares dotted parts as numbers, or as text when a part is not a number. */
    private static int compareDotted(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        // A missing part counts as 0, so 1.4 equals 1.4.0.
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "0";
            String y = i < b.length ? b[i] : "0";
            int result;
            try {
                result = Long.compare(Long.parseLong(x), Long.parseLong(y));
            } catch (NumberFormatException e) {
                result = x.compareTo(y);
            }
            if (result != 0) return result;
        }
        return 0;
    }

    /** Unpacks the runtime into its own folder under retromod-cache/runtime/ and returns it. */
    public static Path extractRuntime(Path gameDir, BootJar source) throws IOException {
        if (!isSha256(source.runtimeSha256())) {
            throw new IOException("the bootstrap manifest has no valid runtime hash");
        }
        // The folder name carries the version and the runtime's hash, so it only ever holds this
        // one jar. Forge and NeoForge before 20.5 load every jar in the folder they are given,
        // so a second file there would load as well.
        Path game = gameDir.toRealPath();
        Path runtimeRoot = game.resolve(CACHE_ROOT).resolve(RUNTIME_FOLDER);
        String label = safeName(source.loader() + "-" + source.targetMinecraft() + "-"
                + source.version());
        Path folder = runtimeRoot.resolve(label + "-" + source.runtimeSha256().substring(0, 12));
        Path target = folder.resolve(safeName("retromod-" + source.version() + "+"
                + source.targetMinecraft()) + ".jar");

        // Refuse links below the game directory, both before and after creating the folder.
        requireRealDirectoriesBelow(game, folder);
        Files.createDirectories(folder);
        requireRealDirectoriesBelow(game, folder);

        // Reuse a jar that already matches, then clear everything else from this folder and
        // remove older runtime folders.
        if (!isCurrentRuntime(target, source.runtimeSha256())) {
            writeRuntime(source, folder, target);
        }
        clearFolderExcept(folder, target);
        removeOtherRuntimes(runtimeRoot, folder);
        return target;
    }

    /** Unpacks the runtime into a new temporary folder when the game directory cannot hold it. */
    static Path extractRuntimeToTemporaryFolder(BootJar source) throws IOException {
        if (!isSha256(source.runtimeSha256())) {
            throw new IOException("the bootstrap manifest has no valid runtime hash");
        }
        // The folder is new for every launch and private to this user where the file system
        // supports it. So nothing else can place a jar beside the runtime for a loader that
        // loads the whole folder.
        Path folder = Files.createTempDirectory("retromod-runtime-");
        Path target = folder.resolve(safeName("retromod-" + source.version() + "+"
                + source.targetMinecraft()) + ".jar");
        try {
            writeRuntime(source, folder, target);
        } catch (IOException | RuntimeException e) {
            try {
                deleteTree(folder);
            } catch (IOException ignored) {
                // A leftover empty temporary folder is harmless.
            }
            throw e;
        }
        // Removed when the game exits, where the system allows deleting a jar that was loaded.
        // Deleted in reverse order of registration: the jar first, then its folder.
        folder.toFile().deleteOnExit();
        target.toFile().deleteOnExit();
        return target;
    }

    /** A path for log lines: relative to the game directory when inside it, otherwise absolute. */
    public static String displayPath(Path gameDir, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        try {
            Path game = gameDir.toRealPath();
            if (absolute.startsWith(game)) return game.relativize(absolute).toString();
        } catch (IOException | RuntimeException e) {
            // Fall back to the absolute path, which always names the file.
        }
        return absolute.toString();
    }

    /** The runtime inside a bootstrap does not match the hash its manifest declares. */
    static final class DamagedRuntimeException extends IOException {
        DamagedRuntimeException(String message) {
            super(message);
        }
    }

    /** True when the jar already at the target matches the expected hash. */
    private static boolean isCurrentRuntime(Path target, String expectedSha) throws IOException {
        // A link in the jar's place is removed, so a real file gets written instead.
        if (Files.isSymbolicLink(target)) {
            Files.delete(target);
            return false;
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return false;
        try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            return expectedSha.equals(sha256(input, MAX_RUNTIME_BYTES));
        }
    }

    /** Copies the runtime out of the bootstrap, checks its hash, and moves it into place. */
    private static void writeRuntime(BootJar source, Path folder, Path target)
            throws IOException {
        // Write to a staged file first, so a half-written jar never sits at the target.
        Path staged = Files.createTempFile(folder, ".runtime-", ".tmp");
        try {
            String actual;
            try (JarFile jar = new JarFile(source.path().toFile(), false)) {
                JarEntry entry = jar.getJarEntry(RUNTIME_ENTRY);
                if (entry == null) throw new IOException("the runtime entry is missing");
                try (InputStream input = jar.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(staged)) {
                    actual = copyHashing(input, output, MAX_RUNTIME_BYTES);
                }
            }
            // A mismatch means the bootstrap file is damaged, which another folder cannot fix.
            if (!actual.equals(source.runtimeSha256())) {
                throw new DamagedRuntimeException("the runtime inside "
                        + source.path().getFileName()
                        + " does not match its manifest hash. The file may be damaged; "
                        + "download it again");
            }
            // Move it into place atomically where the file system supports that.
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** Deletes everything in the runtime folder except the runtime jar. */
    private static void clearFolderExcept(Path folder, Path keep) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            for (Path child : children) {
                if (child.equals(keep)) continue;
                deleteTree(child);
            }
        }
    }

    /** Older runtime folders are best-effort cleanup; Windows may still hold one open. */
    private static void removeOtherRuntimes(Path runtimeRoot, Path keep) {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(runtimeRoot)) {
            for (Path child : children) {
                if (child.equals(keep)) continue;
                try {
                    deleteTree(child);
                } catch (IOException ignored) {
                    // Retried on a later launch.
                }
            }
        } catch (IOException ignored) {
            // Retried on a later launch.
        }
    }

    /** The jar a class was loaded from: a file:, jar:, or modlauncher union: code source. */
    static Path codeSourceJar(Class<?> anchor) {
        try {
            var codeSource = anchor.getProtectionDomain().getCodeSource();
            if (codeSource == null) return null;
            return jarPathFromUrl(codeSource.getLocation());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The jar file a code-source URL names, or null when it names no regular file. */
    static Path jarPathFromUrl(URL url) {
        if (url == null) return null;
        try {
            String text = url.toString();
            if (text.startsWith("union:")) {
                // securejarhandler writes union:/abs/path.jar%23<n>!/, already percent-encoded.
                // Cut the suffix to get the file path.
                String path = text.substring("union:".length());
                int marker = path.indexOf("%23");
                if (marker >= 0) path = path.substring(0, marker);
                int bang = path.indexOf('!');
                if (bang >= 0) path = path.substring(0, bang);
                Path file = Path.of(URI.create("file:" + path));
                return Files.isRegularFile(file) ? file : null;
            }
            // A jar: URL wraps the file URL in front of the '!'.
            URI uri = url.toURI();
            if ("jar".equalsIgnoreCase(uri.getScheme())) {
                String nested = uri.getRawSchemeSpecificPart();
                int bang = nested.indexOf('!');
                if (bang >= 0) nested = nested.substring(0, bang);
                uri = URI.create(nested);
            }
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
            Path file = Path.of(uri);
            return Files.isRegularFile(file) ? file : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Keeps names within the characters every file system and module-name rule accepts. */
    static String safeName(String text) {
        String cleaned = text.replaceAll("[^A-Za-z0-9._+-]", "_");
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /** The SHA-256 of a stream, failing once it passes the size limit. */
    static String sha256(InputStream input, long limit) throws IOException {
        return copyHashing(input, OutputStream.nullOutputStream(), limit);
    }

    /** Copies input to output and returns its SHA-256, failing once it passes the size limit. */
    private static String copyHashing(InputStream input, OutputStream output, long limit)
            throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("the runtime jar is unexpectedly large");
            digest.update(buffer, 0, read);
            output.write(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Requires every existing step from root (exclusive) down to path to be a real folder. */
    static void requireRealDirectoriesBelow(Path root, Path path) throws IOException {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(absoluteRoot) || absolute.equals(absoluteRoot)) {
            throw new IOException(path + " is not inside " + root);
        }
        // Links above the game directory are the player's choice. Links below it would let
        // something else decide where Retromod's code is written. The check stops at the first
        // step that does not exist yet.
        Path current = absoluteRoot;
        for (Path component : absoluteRoot.relativize(absolute)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException(current + " is a link; Retromod only uses real folders "
                        + "inside the game directory");
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(current + " is not a folder");
            }
        }
    }

    /** Deletes a file, or a folder and everything in it, without following a link. */
    static void deleteTree(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
