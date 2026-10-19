/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.retromod.aot.AotCacheStamp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Looks into Retromod's translated mod cache from outside the game: list what it holds, copy
 * translations out for sharing, and clear it.
 *
 * <p>Reading never takes the lock or rewrites the index. A running game owns both, and these
 * tools must not change what it serves. Export hands out only {@link CacheEntry.Status#OK}
 * entries whose file still hashes to the recorded value, from a cache whose index belongs to
 * its stamped folder. A leftover or planted file in the cache is never copied out as a mod.
 */
public final class CacheTools {

    /** Default export folder below the game directory. */
    public static final String EXPORT_FOLDER = "retromod-output/mods";

    /** Manifest key {@code ForgeModTransformer} writes with the original's file name. */
    static final String ORIGINAL_JAR = "Retromod-Original-Jar";

    private static final Duration CLEAR_LOCK_WAIT = Duration.ofSeconds(2);

    /** One host's cache, {@code retromod-cache/translated/<loader>/<mc>/}. */
    public record Host(String loader, String minecraft) {
        public Host {
            Objects.requireNonNull(loader, "loader");
            Objects.requireNonNull(minecraft, "minecraft");
        }

        /** For example "NeoForge 26.3". */
        public String displayName() {
            return loaderName(loader) + " " + minecraft;
        }
    }

    /**
     * What one host's cache holds.
     *
     * @param consistent true when the index belongs to the stamped folder; otherwise the next
     *                   launch rebuilds the cache and nothing in it is exported
     */
    public record Report(Host host, boolean consistent, List<CacheEntry> entries) {
        public long count(CacheEntry.Status status) {
            return entries.stream().filter(entry -> entry.status() == status).count();
        }
    }

    /** A cache entry left out of an export, with a reason a player can act on. */
    public record Skipped(String sourceName, String reason) {}

    /** What an export copied and what it left out. */
    public record ExportResult(Host host, Path outputDir, List<Path> exported,
            List<Skipped> skipped, List<String> unmatched) {}

    /**
     * A jar in {@code mods/} that rewrite mode wrote, and its original when one of Retromod's
     * backup folders still has it.
     */
    public record RewriteOutput(Path jar, Optional<Path> original) {}

    /** What {@link #clear} removed, or that a game held the cache. */
    public record ClearResult(boolean busy, List<Path> removed) {}

    private CacheTools() {}

    /** Host caches below {@code gameDir}, sorted. Empty when there is no cache. */
    public static List<Host> hosts(Path gameDir) throws IOException {
        Path translated = gameDir.toRealPath().resolve(TranslatedModCache.CACHE_ROOT)
                .resolve(TranslatedModCache.TRANSLATED);
        List<Host> hosts = new ArrayList<>();
        if (!Files.isDirectory(translated, LinkOption.NOFOLLOW_LINKS)) return hosts;
        TranslatedModCache.requireRealDirectoriesBelow(gameDir.toRealPath(), translated);
        try (DirectoryStream<Path> loaders = Files.newDirectoryStream(translated)) {
            for (Path loaderDir : loaders) {
                if (!Files.isDirectory(loaderDir, LinkOption.NOFOLLOW_LINKS)) continue;
                try (DirectoryStream<Path> versions = Files.newDirectoryStream(loaderDir)) {
                    for (Path versionDir : versions) {
                        if (!Files.isDirectory(versionDir, LinkOption.NOFOLLOW_LINKS)) continue;
                        Host host = new Host(loaderDir.getFileName().toString(),
                                versionDir.getFileName().toString());
                        try {
                            TranslatedModCache.hostPaths(gameDir, host.loader(),
                                    host.minecraft());
                            hosts.add(host);
                        } catch (IOException | IllegalArgumentException ignored) {
                            // Not a folder Retromod writes; leave it out.
                        }
                    }
                }
            }
        }
        hosts.sort(Comparator.comparing(Host::loader).thenComparing(Host::minecraft));
        return hosts;
    }

    /**
     * Picks one host cache. With no loader and version given, there must be exactly one.
     *
     * @throws IOException with a message that lists the choices when none or several match
     */
    public static Host chooseHost(Path gameDir, String loader, String minecraft)
            throws IOException {
        List<Host> all = hosts(gameDir);
        List<Host> matching = all.stream()
                .filter(host -> loader == null || host.loader().equalsIgnoreCase(loader))
                .filter(host -> minecraft == null || host.minecraft().equals(minecraft))
                .toList();
        if (matching.size() == 1) return matching.get(0);
        if (all.isEmpty()) {
            throw new IOException("No translated mods were found in " + gameDir
                    + ". Launch the game once with Retromod in in-place mode first.");
        }
        StringBuilder choices = new StringBuilder();
        for (Host host : all) {
            choices.append(choices.isEmpty() ? "" : ", ").append(host.displayName());
        }
        if (matching.isEmpty()) {
            throw new IOException("No translated mods for that loader and version. "
                    + "This game has: " + choices + ".");
        }
        throw new IOException("This game has translated mods for more than one setup: "
                + choices + ". Choose one with --target <version> and "
                + "--target-loader <forge|neoforge>.");
    }

    /** Reads one host cache without the lock. Entries are sorted by original file name. */
    public static Report report(Path gameDir, Host host) throws IOException {
        TranslatedModCache.HostPaths paths = TranslatedModCache.hostPaths(gameDir,
                host.loader(), host.minecraft());
        CacheIndex index = Files.isRegularFile(paths.indexFile(), LinkOption.NOFOLLOW_LINKS)
                ? CacheIndex.read(paths.indexFile()) : null;
        String recorded = AotCacheStamp.readStampBelow(paths.gameDir(), paths.jarsDir());
        boolean consistent = index != null && recorded != null && recorded.equals(index.stamp());
        List<CacheEntry> entries = index == null ? List.of() : index.entries().values().stream()
                .sorted(Comparator.comparing((CacheEntry entry) ->
                        String.valueOf(entry.sourceName()).toLowerCase(Locale.ROOT)))
                .toList();
        return new Report(host, consistent, entries);
    }

    /**
     * Copies translated jars out of one host cache, each under its cache file name.
     *
     * @param selectors mod ids or file names to export; empty exports every translated mod
     */
    public static ExportResult export(Path gameDir, Host host, Path outputDir,
            Collection<String> selectors) throws IOException {
        Report report = report(gameDir, host);
        if (!report.consistent()) {
            throw new IOException("The " + host.displayName() + " cache was written by "
                    + "another Retromod build or is incomplete. Launch the game once so "
                    + "Retromod rebuilds it, then export again.");
        }
        TranslatedModCache.HostPaths paths = TranslatedModCache.hostPaths(gameDir,
                host.loader(), host.minecraft());
        Path output = outputDir.toAbsolutePath().normalize();
        Path cacheRoot = paths.cacheRoot();
        if (output.startsWith(cacheRoot) || resolvedStartsWith(output, cacheRoot)) {
            throw new IOException("Choose an export folder outside " + cacheRoot
                    + ". Retromod cleans that folder on every launch.");
        }

        Set<String> wanted = new LinkedHashSet<>();
        for (String selector : selectors) wanted.add(selector.toLowerCase(Locale.ROOT));
        Set<String> matched = new LinkedHashSet<>();
        List<Path> exported = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (CacheEntry entry : report.entries()) {
            Optional<String> selector = matchingSelector(entry, wanted);
            if (!wanted.isEmpty() && selector.isEmpty()) continue;
            selector.ifPresent(matched::add);
            String name = Objects.requireNonNullElse(entry.sourceName(), entry.sourceSha256());
            if (entry.status() != CacheEntry.Status.OK) {
                skipped.add(new Skipped(name, notExportedReason(entry)));
                continue;
            }
            if (!Files.isDirectory(output)) Files.createDirectories(output);
            Optional<String> problem = copyVerified(paths.jarsDir().resolve(entry.file()),
                    output.resolve(entry.file()), entry.fileSha256());
            if (problem.isPresent()) {
                skipped.add(new Skipped(name, problem.get()));
            } else {
                exported.add(output.resolve(entry.file()));
            }
        }
        List<String> unmatched = new ArrayList<>();
        for (String selector : selectors) {
            if (!matched.contains(selector.toLowerCase(Locale.ROOT))) unmatched.add(selector);
        }
        return new ExportResult(host, output, exported, skipped, unmatched);
    }

    /**
     * Jars directly in {@code mods/} that rewrite mode or the older file picker wrote. Their
     * originals are looked up by the bare file name the transformer recorded, and only in
     * Retromod's own backup folders, because a shared jar's manifest is not trusted to name a
     * path.
     */
    public static List<RewriteOutput> rewriteOutputs(Path gameDir) throws IOException {
        Path game = gameDir.toRealPath();
        Path mods = game.resolve("mods");
        List<RewriteOutput> outputs = new ArrayList<>();
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) return outputs;
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path child : children) {
                if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) jars.add(child);
            }
        }
        jars.sort(Comparator.naturalOrder());
        List<Path> backupFolders = List.of(game.resolve("retromod-input").resolve("processed"),
                mods.resolve("retromod-backups"), game.resolve("retromod-backups"));
        for (Path jar : jars) {
            Manifest manifest;
            try (JarFile file = new JarFile(jar.toFile(), false)) {
                manifest = file.getManifest();
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (!TranslatedJarMarkers.isRetromodOutput(manifest)) continue;
            String originalName = manifest.getMainAttributes().getValue(ORIGINAL_JAR);
            outputs.add(new RewriteOutput(jar, findOriginal(originalName, backupFolders)));
        }
        return outputs;
    }

    /**
     * Deletes every host cache and serve folder. Leaves the extracted runtime and the older
     * AOT caches alone. Refuses while a game holds the cache lock, which a game does only
     * while it starts and while it translates. A game that finished starting still has the
     * served jars open, so the CLI tells the player to close the game first.
     */
    public static ClearResult clear(Path gameDir) throws IOException {
        Path game = gameDir.toRealPath();
        Path cacheRoot = game.resolve(TranslatedModCache.CACHE_ROOT);
        // A linked cache folder is not one Retromod made, so it is left alone.
        if (!Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS)) {
            return new ClearResult(false, List.of());
        }
        Optional<CacheLock> lock = CacheLock.acquire(
                cacheRoot.resolve(TranslatedModCache.LOCK_FILE), CLEAR_LOCK_WAIT);
        if (lock.isEmpty()) return new ClearResult(true, List.of());
        try (CacheLock held = lock.get()) {
            List<Path> removed = new ArrayList<>();
            for (String folder : List.of(TranslatedModCache.TRANSLATED,
                    TranslatedModCache.SERVE)) {
                Path target = cacheRoot.resolve(folder);
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                TranslatedModCache.deleteTree(target);
                removed.add(target);
            }
            return new ClearResult(false, removed);
        }
    }

    /** For example "NeoForge" for the cache folder name {@code neoforge}. */
    public static String loaderName(String loader) {
        return switch (loader) {
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            default -> loader;
        };
    }

    private static Optional<String> matchingSelector(CacheEntry entry, Set<String> wanted) {
        for (String selector : wanted) {
            if (selector.equals(lower(entry.sourceName())) || selector.equals(lower(entry.file()))
                    || entry.modIds().stream().anyMatch(id -> selector.equals(lower(id)))) {
                return Optional.of(selector);
            }
        }
        return Optional.empty();
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String notExportedReason(CacheEntry entry) {
        return switch (entry.status()) {
            case FAILED -> "translation failed"
                    + (entry.failure() == null ? "" : ": " + entry.failure());
            case PENDING, RETRY, STARTED ->
                    "not translated yet. Launch the game once, then export again";
            case SKIPPED -> "translation was turned off for this file";
            case OK -> "it is translated";
        };
    }

    /**
     * Copies through a temporary file in the target folder and checks the copy's hash before
     * it takes the final name, so a half-written or changed file is never left as a jar.
     */
    private static Optional<String> copyVerified(Path source, Path target, String expectedSha)
            throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of("its cached copy is missing. Launch the game once to translate "
                    + "it again");
        }
        if (!expectedSha.equals(TranslatedModCache.sha256(source))) {
            return Optional.of("its cached copy changed on disk. Launch the game once to "
                    + "translate it again");
        }
        if (Files.isSymbolicLink(target) || (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
            return Optional.of(target + " exists and is not a regular file");
        }
        // The cache's files are owner-only, and both Files.createTempFile and Files.copy would
        // carry that over. A jar made to be shared gets the normal permissions a new file gets.
        Path staged = target.resolveSibling(".export-" + UUID.randomUUID() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(staged, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                Files.copy(source, out);
            }
            if (!expectedSha.equals(TranslatedModCache.sha256(staged))) {
                return Optional.of("the copy did not match the cached file");
            }
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return Optional.empty();
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static Optional<Path> findOriginal(String originalName, List<Path> folders) {
        if (originalName == null || originalName.isBlank() || originalName.contains("/")
                || originalName.contains("\\") || originalName.equals("..")
                || originalName.equals(".")) {
            return Optional.empty();
        }
        for (Path folder : folders) {
            Path candidate = folder.resolve(originalName);
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean resolvedStartsWith(Path path, Path root) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return false;
        try {
            return existing.toRealPath().startsWith(root);
        } catch (IOException e) {
            return false;
        }
    }
}
