/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.retromod.aot.AotCacheStamp;
import com.retromod.core.RetromodVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Retromod's cache of translated mods for one loader and Minecraft version.
 *
 * <p>Layout, all below the game directory:
 * <pre>
 * retromod-cache/
 *   .lock                              held for a whole pass, never deleted
 *   translated/&lt;loader&gt;/&lt;mc&gt;/
 *     index.json                       entries by source SHA-256, outside the wiped folder
 *     status.json                      reserved for the discovery pass report
 *     jars/                            stamped; wiped when the stamp changes
 *       .cache-stamp
 *       &lt;safeBase&gt;-retromod.jar
 *   serve/&lt;loader&gt;/&lt;mc&gt;/              rebuilt from empty for loaders that scan a folder
 * </pre>
 *
 * <p>The stamp binds {@code jars/} to the Retromod build, the host, and the
 * {@link TransformContext}. Only {@link #open} checks and rewrites it. Code that runs later in
 * the same launch uses {@link #attach}, which trusts the stamp the earlier pass wrote. The
 * discovery-time engine and the in-game entry point see different code sources, so their
 * self-hashes can differ, and two writers of the stamp would wipe each other's work.
 *
 * <p>A cached jar is code that loads as a mod. Only {@link CacheEntry.Status#OK} entries whose
 * file still hashes to the recorded value are ever handed out, and no folder Retromod creates
 * below the game directory may be a link. Links above the game directory are the player's
 * choice and are accepted.
 */
public final class TranslatedModCache implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-InPlace");

    /** Folder below the game directory that holds every Retromod cache. */
    public static final String CACHE_ROOT = "retromod-cache";
    static final String LOCK_FILE = ".lock";
    static final String TRANSLATED = "translated";
    static final String SERVE = "serve";
    static final String JARS = "jars";
    static final String INDEX = "index.json";
    static final String STATUS = "status.json";
    static final String STAMP_FILE = ".cache-stamp";
    static final String OUTPUT_SUFFIX = "-retromod.jar";

    private static final Pattern LOADER = Pattern.compile("[a-z]{1,32}");
    private static final Pattern MINECRAFT_VERSION =
            Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");
    private static final Pattern LOCATION = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final int MAX_BASE_NAME = 150;

    /** What this handle may do. */
    public enum State {
        /** The lock is held and the stamp matches: lookups, writes, and pruning work. */
        READY,
        /** Another holder has the lock. Verified hits can be served, nothing is written. */
        READ_ONLY,
        /** The cache cannot be trusted this launch. Nothing is served or written. */
        UNAVAILABLE
    }

    /** Files cleaned up by {@link #prune}, and the ones a later launch has to retry. */
    public record PruneReport(int removedEntries, int deletedFiles, List<Path> failedDeletes) {}

    /** A folder that holds exactly the requested verified jars and nothing else. */
    public record ServeFolder(Path directory, Map<String, Path> files) {}

    private final Path gameDir;
    private final String loader;
    private final String minecraftVersion;
    private final Path cacheRoot;
    private final Path hostDir;
    private final Path jarsDir;
    private final Path indexFile;
    private final CacheLock lock;
    private final State state;
    private final String unavailableReason;
    private final CacheIndex index;
    private final Set<Path> activeStaging = new HashSet<>();
    /** Files hashed by {@link #verifiedJar} through this handle, with the hash they had. */
    private final Map<Path, String> hashedThisHandle = new HashMap<>();
    private boolean dirty;

    private TranslatedModCache(Layout layout, CacheLock lock, State state, String reason,
            CacheIndex index, boolean dirty) {
        this.gameDir = layout.gameDir;
        this.loader = layout.loader;
        this.minecraftVersion = layout.minecraftVersion;
        this.cacheRoot = layout.cacheRoot;
        this.hostDir = layout.hostDir;
        this.jarsDir = layout.jarsDir;
        this.indexFile = layout.indexFile;
        this.lock = lock;
        this.state = state;
        this.unavailableReason = reason;
        this.index = index == null ? new CacheIndex("") : index;
        this.dirty = dirty;
    }

    /**
     * Opens the cache for the pass that owns the stamp: the first Retromod code to run in a
     * launch. Clears {@code jars/} when it belongs to another build, host, or context.
     */
    public static TranslatedModCache open(Path gameDir, String loader, String minecraftVersion,
            TransformContext context, Duration lockWait) {
        Objects.requireNonNull(context, "context");
        String stamp = AotCacheStamp.stampForTarget(
                "inplace/" + loader + "/" + minecraftVersion + "/" + context.hash());
        return open(gameDir, loader, minecraftVersion, stamp, lockWait);
    }

    /** Test seam: {@link #open} with an explicit stamp. */
    static TranslatedModCache open(Path gameDir, String loader, String minecraftVersion,
            String stamp, Duration lockWait) {
        Layout layout;
        try {
            layout = Layout.of(gameDir, loader, minecraftVersion);
        } catch (IOException | IllegalArgumentException e) {
            return unavailable(gameDir, loader, minecraftVersion,
                    "the cache folder cannot be used: " + e.getMessage());
        }
        if (stamp == null) {
            return unavailable(layout, "the cache stamp could not be built for this host");
        }
        Optional<CacheLock> held;
        try {
            layout.prepareHostDir();
            held = CacheLock.acquire(layout.lockFile, lockWait);
        } catch (IOException e) {
            return unavailable(layout, "the cache folder cannot be used: " + e.getMessage());
        }
        if (held.isEmpty()) {
            return readOnly(layout, stamp);
        }
        CacheLock lock = held.get();
        try {
            if (!AotCacheStamp.ensureCurrentBelow(layout.gameDir, layout.jarsDir, stamp)) {
                closeQuietly(lock);
                return unavailable(layout, "translations from another Retromod build could not "
                        + "be cleared. They are checked again on the next launch");
            }
            CacheIndex index = readIndex(layout.indexFile);
            boolean dirty = false;
            if (index == null) {
                index = new CacheIndex(stamp);
                dirty = true;
            } else if (!stamp.equals(index.stamp())) {
                index.reset(stamp);
                dirty = true;
            }
            return new TranslatedModCache(layout, lock, State.READY, null, index, dirty);
        } catch (RuntimeException e) {
            closeQuietly(lock);
            return unavailable(layout, "the cache could not be opened: " + e);
        }
    }

    /**
     * Joins the cache a discovery pass prepared earlier in this launch. Never checks or
     * rewrites the stamp; it only confirms the index belongs to the stamped folder.
     */
    public static TranslatedModCache attach(Path gameDir, String loader,
            String minecraftVersion, Duration lockWait) {
        Layout layout;
        try {
            layout = Layout.of(gameDir, loader, minecraftVersion);
        } catch (IOException | IllegalArgumentException e) {
            return unavailable(gameDir, loader, minecraftVersion,
                    "the cache folder cannot be used: " + e.getMessage());
        }
        Optional<CacheLock> held;
        try {
            layout.prepareHostDir();
            held = CacheLock.acquire(layout.lockFile, lockWait);
        } catch (IOException e) {
            return unavailable(layout, "the cache folder cannot be used: " + e.getMessage());
        }
        String recorded = AotCacheStamp.readStampBelow(layout.gameDir, layout.jarsDir);
        if (held.isEmpty()) {
            return recorded == null
                    ? unavailable(layout, "another game holds the cache and it has no stamp")
                    : readOnly(layout, recorded);
        }
        CacheIndex index = readIndex(layout.indexFile);
        if (recorded == null || index == null || !recorded.equals(index.stamp())) {
            closeQuietly(held.get());
            return unavailable(layout, "the cache was not prepared during discovery in this "
                    + "launch");
        }
        return new TranslatedModCache(layout, held.get(), State.READY, null, index, false);
    }

    private static TranslatedModCache readOnly(Layout layout, String expectedStamp) {
        String recorded = AotCacheStamp.readStampBelow(layout.gameDir, layout.jarsDir);
        CacheIndex index = readIndex(layout.indexFile);
        if (expectedStamp.equals(recorded) && index != null
                && expectedStamp.equals(index.stamp())) {
            return new TranslatedModCache(layout, null, State.READ_ONLY,
                    "another game is using the cache", index, false);
        }
        return unavailable(layout, "another game is using the cache, and its translations "
                + "belong to a different Retromod build or setting");
    }

    private static TranslatedModCache unavailable(Layout layout, String reason) {
        LOGGER.warn("Retromod's translated mod cache is unavailable: {}", reason);
        return new TranslatedModCache(layout, null, State.UNAVAILABLE, reason, null, false);
    }

    private static TranslatedModCache unavailable(Path gameDir, String loader,
            String minecraftVersion, String reason) {
        Path base = gameDir == null ? Path.of("").toAbsolutePath()
                : gameDir.toAbsolutePath().normalize();
        return unavailable(Layout.unchecked(base, String.valueOf(loader),
                String.valueOf(minecraftVersion)), reason);
    }

    private static CacheIndex readIndex(Path file) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            LOGGER.warn("Ignoring the cache index at {} because it is not a regular file", file);
            return null;
        }
        try {
            return CacheIndex.read(file);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Starting a new cache index because {} could not be read: {}",
                    file, e.getMessage());
            return null;
        }
    }

    public State state() {
        return state;
    }

    /** Why the cache is not {@link State#READY}, or null when it is. */
    public String unavailableReason() {
        return unavailableReason;
    }

    /** The game directory with links resolved, as every cache path is built from it. */
    public Path gameDirectory() {
        return gameDir;
    }

    public Path cacheRoot() {
        return cacheRoot;
    }

    /** {@code retromod-cache/translated/<loader>/<mc>/}. */
    public Path hostDirectory() {
        return hostDir;
    }

    /** The stamped folder that holds the translated jars. */
    public Path jarsDirectory() {
        return jarsDir;
    }

    /** Where the discovery pass reports what it served, hid, and failed. */
    public Path statusFile() {
        return hostDir.resolve(STATUS);
    }

    /**
     * The status file for this launch. A pass that holds the lock owns {@code status.json}.
     * A read-only pass runs while another game holds the lock, so it writes a file named for
     * its own process instead of overwriting the other game's report.
     */
    public Path statusFileForThisLaunch() {
        if (state == State.READY) return statusFile();
        return hostDir.resolve("status-" + ProcessHandle.current().pid() + ".json");
    }

    /**
     * Source hashes the index on disk knows, read without the lock and without checking the
     * stamp. Only for rewrite mode, which must not change the cache but still needs to know
     * which originals in-place mode handled before. Empty when there is no readable index.
     */
    public static Set<String> knownSources(Path gameDir, String loader, String minecraftVersion) {
        try {
            Layout layout = Layout.of(gameDir, loader, minecraftVersion);
            requireRealDirectoriesBelow(layout.gameDir, layout.hostDir);
            CacheIndex index = readIndex(layout.indexFile);
            return index == null ? Set.of() : Set.copyOf(index.entries().keySet());
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
    }

    /**
     * Paths of one host's cache, for tools that read it without opening it. Built with the
     * same checks as {@link #open}: the game directory's links are resolved, the loader and
     * version must be valid folder names, and no folder below the game directory may be a
     * link.
     */
    record HostPaths(Path gameDir, Path cacheRoot, Path hostDir, Path jarsDir, Path indexFile) {}

    static HostPaths hostPaths(Path gameDir, String loader, String minecraftVersion)
            throws IOException {
        Layout layout = Layout.of(gameDir, loader, minecraftVersion);
        requireRealDirectoriesBelow(layout.gameDir, layout.jarsDir);
        return new HostPaths(layout.gameDir, layout.cacheRoot, layout.hostDir, layout.jarsDir,
                layout.indexFile);
    }

    /** Every entry, by source SHA-256. Empty when the cache is unavailable. */
    public Map<String, CacheEntry> entries() {
        if (state == State.UNAVAILABLE) return Map.of();
        return Collections.unmodifiableMap(index.entries());
    }

    public Optional<CacheEntry> entry(String sourceSha256) {
        return Optional.ofNullable(entries().get(sourceSha256));
    }

    /**
     * Hashes an original, reusing the remembered hash while the file's size, modification
     * time, change time, and file key are all unchanged.
     *
     * @param location the input folder name, such as {@code mods} or {@code retromod-input}
     */
    public SourceJar describe(Path source, String location) throws IOException {
        Objects.requireNonNull(source, "source");
        if (location == null || !LOCATION.matcher(location).matches()) {
            throw new IllegalArgumentException("Invalid source location: " + location);
        }
        Path fileName = source.getFileName();
        if (fileName == null || !Files.isRegularFile(source)) {
            throw new IOException("Not a regular file: " + source);
        }
        String name = fileName.toString();
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        String fingerprint = fingerprint(source, attributes, true);
        String memoKey = location + "/" + name;
        CacheIndex.Memo remembered = state == State.UNAVAILABLE ? null : index.memo().get(memoKey);
        String sha;
        if (remembered != null && remembered.fingerprint().equals(fingerprint)) {
            sha = remembered.sha256();
        } else {
            sha = sha256(source);
            if (state == State.READY) {
                index.memo().put(memoKey, new CacheIndex.Memo(fingerprint, sha));
                dirty = true;
            }
        }
        return new SourceJar(source, location, name, attributes.size(), fingerprint, sha);
    }

    /**
     * The translated jar for a source, only when its entry is {@link CacheEntry.Status#OK}
     * and the file still matches the recorded hash. A mismatch drops the entry so the
     * source is translated again.
     *
     * <p>The file is hashed on every launch, not only when its size or time changed: a file
     * rewritten in place can keep its size, modification time, and inode, and this file loads
     * as a mod. Within one handle a file is hashed once, so the serve folder does not read it
     * a second time.
     */
    public Optional<Path> verifiedJar(String sourceSha256) {
        if (state == State.UNAVAILABLE) return Optional.empty();
        CacheEntry entry = index.entries().get(sourceSha256);
        if (entry == null || entry.status() != CacheEntry.Status.OK) return Optional.empty();
        Path file = jarsDir.resolve(entry.file());
        if (entry.fileSha256().equals(hashedThisHandle.get(file))) return Optional.of(file);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                LOGGER.info("Translated copy of {} is missing; it will be translated again",
                        entry.sourceName());
                drop(entry);
                return Optional.empty();
            }
            if (!sha256(file).equals(entry.fileSha256())) {
                LOGGER.warn("Translated copy of {} changed on disk; it will be translated "
                        + "again", entry.sourceName());
                drop(entry);
                return Optional.empty();
            }
            hashedThisHandle.put(file, entry.fileSha256());
            return Optional.of(file);
        } catch (IOException e) {
            LOGGER.warn("Could not check the translated copy of {}: {}", entry.sourceName(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * True when this launch may translate the source: it has no entry, or a
     * {@link CacheEntry.Status#RETRY} or {@link CacheEntry.Status#PENDING} one. Failed and
     * skipped sources wait for a new Retromod build, setting, or file.
     */
    public boolean shouldTranslate(String sourceSha256) {
        if (state != State.READY) return false;
        CacheEntry entry = index.entries().get(sourceSha256);
        return entry == null || entry.allowsAttempt();
    }

    /** A new empty file in {@code jars/} to write a translation into before {@link #publish}. */
    public Path newStagingFile() throws IOException {
        requireReady("stage a translation");
        Path staged = Files.createTempFile(jarsDir, ".staging-", ".tmp");
        activeStaging.add(staged);
        return staged;
    }

    /**
     * A new empty folder in {@code jars/} for a transformer that writes its output, and any
     * sidecar files, into a folder it is given. Keeping it inside the cache means a
     * translation never writes outside {@code retromod-cache/}; a leftover from a crashed
     * launch is removed by the next {@link #prune}.
     */
    public Path newStagingDirectory() throws IOException {
        requireReady("stage a translation");
        Path staged = Files.createTempDirectory(jarsDir, ".staging-");
        activeStaging.add(staged);
        return staged;
    }

    /** Deletes a staging file or folder now instead of waiting for {@link #close}. */
    public void deleteStaging(Path staged) {
        if (staged == null) return;
        try {
            deleteTree(staged);
            activeStaging.remove(staged);
        } catch (IOException e) {
            LOGGER.debug("Could not remove staging {}: {}", staged, e.getMessage());
        }
    }

    /**
     * Publishes a finished translation atomically and records it as {@link
     * CacheEntry.Status#OK}. A staged file outside {@code jars/} is copied in first, so the
     * final step is always a rename inside one folder.
     */
    public CacheEntry publish(SourceJar source, Path staged, List<String> modIds,
            String modVersion) throws IOException {
        requireReady("publish a translation");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(staged, "staged");
        if (Files.isSymbolicLink(staged) || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Translated jar is not a regular file: " + staged);
        }
        Path inFolder = staged;
        if (!jarsDir.equals(staged.toAbsolutePath().normalize().getParent())) {
            inFolder = newStagingFile();
            Files.copy(staged, inFolder, StandardCopyOption.REPLACE_EXISTING);
        }
        try (JarFile ignored = new JarFile(inFolder.toFile(), false)) {
            // Opening the staged jar validates its central directory before anything loads it.
        }
        String fileSha = sha256(inFolder);
        String name = chooseFileName(source);
        Path target = jarsDir.resolve(name);
        moveIntoPlace(inFolder, target);
        hashedThisHandle.remove(target);
        activeStaging.remove(inFolder);
        activeStaging.remove(staged);

        CacheEntry previous = index.entries().get(source.sha256());
        if (previous != null && previous.file() != null && !previous.file().equals(name)) {
            deleteIfUnreferenced(previous.file(), source.sha256());
        }
        BasicFileAttributes attributes = Files.readAttributes(target,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        CacheEntry entry = new CacheEntry(source.sha256(), CacheEntry.Status.OK, name, fileSha,
                fingerprint(target, attributes, false), modIds, modVersion, source.fileName(),
                source.location(), source.size(), null, 0, RetromodVersion.RETROMOD_VERSION,
                System.currentTimeMillis());
        index.entries().put(source.sha256(), entry);
        dirty = true;
        return entry;
    }

    /** Records a translation failure that would repeat. Not retried until something changes. */
    public void recordFailure(SourceJar source, String reason) {
        record(source, CacheEntry.Status.FAILED, reason, 0);
    }

    /**
     * Records a transient problem, such as an I/O error. Retried on the next launch. The
     * entry keeps its attempt count, so a problem that never clears can be told apart from
     * one that happened once.
     */
    public void recordRetry(SourceJar source, String reason) {
        record(source, CacheEntry.Status.RETRY, reason, waitingAttempts(source));
    }

    /** Records the player's choice not to translate this file. */
    public void recordSkipped(SourceJar source, String reason) {
        record(source, CacheEntry.Status.SKIPPED, reason, 0);
    }

    /**
     * Marks a hidden original as waiting for translation and returns how many launches in a
     * row it has waited, starting at 1. A caller should stop hiding a source once this
     * passes its limit, so a translation that never runs cannot hide a mod forever.
     */
    public int markPending(SourceJar source) {
        requireReadyUnchecked("mark a source pending");
        int attempts = waitingAttempts(source) + 1;
        record(source, CacheEntry.Status.PENDING, null, attempts);
        return attempts;
    }

    /**
     * Records that translation of this source begins now, and writes the index at once. If
     * the launch dies before a result is recorded, the next discovery pass finds this entry
     * and counts the attempt. The attempt count carries over unchanged.
     */
    public void markStarted(SourceJar source) {
        record(source, CacheEntry.Status.STARTED, "Translation started and did not finish",
                waitingAttempts(source));
        try {
            save();
        } catch (IOException e) {
            LOGGER.debug("Could not save the cache index before translating: {}",
                    e.getMessage());
        }
    }

    /** Launches in a row this source waited or was retried without a translation. */
    private int waitingAttempts(SourceJar source) {
        CacheEntry previous = index.entries().get(source.sha256());
        return previous != null && previous.allowsAttempt() ? previous.attempts() : 0;
    }

    /** Removes a source's entry and its translated file. */
    public void forget(String sourceSha256) {
        requireReadyUnchecked("forget an entry");
        CacheEntry entry = index.entries().get(sourceSha256);
        if (entry != null) drop(entry);
    }

    private void record(SourceJar source, CacheEntry.Status status, String reason,
            int attempts) {
        requireReadyUnchecked("record a cache result");
        Objects.requireNonNull(source, "source");
        CacheEntry previous = index.entries().get(source.sha256());
        if (previous != null && previous.file() != null) {
            deleteIfUnreferenced(previous.file(), source.sha256());
        }
        index.entries().put(source.sha256(), new CacheEntry(source.sha256(), status, null, null,
                null, List.of(), null, source.fileName(), source.location(), source.size(),
                oneLine(reason), attempts, RetromodVersion.RETROMOD_VERSION,
                System.currentTimeMillis()));
        dirty = true;
    }

    /**
     * Removes entries whose source is gone, files no entry names, and staging leftovers.
     * A file that cannot be deleted (Windows keeps open files) is reported and retried on a
     * later launch. Call this before handing any cache path to a loader.
     */
    public PruneReport prune(Collection<String> liveSourceSha256s) {
        requireReadyUnchecked("prune");
        Set<String> live = new HashSet<>(liveSourceSha256s);
        int removed = 0;
        int deleted = 0;
        List<Path> failed = new ArrayList<>();
        for (Iterator<CacheEntry> it = index.entries().values().iterator(); it.hasNext(); ) {
            CacheEntry entry = it.next();
            if (live.contains(entry.sourceSha256())) continue;
            it.remove();
            removed++;
            dirty = true;
        }
        index.memo().values().removeIf(memo -> {
            boolean stale = !live.contains(memo.sha256());
            if (stale) dirty = true;
            return stale;
        });

        Set<String> referenced = new HashSet<>();
        for (CacheEntry entry : index.entries().values()) {
            if (entry.status() == CacheEntry.Status.OK) referenced.add(entry.file());
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(jarsDir)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (name.equals(STAMP_FILE) || activeStaging.contains(child)) continue;
                if (referenced.contains(name)
                        && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    deleteTree(child);
                    deleted++;
                } catch (IOException e) {
                    failed.add(child);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not list {} for cleanup: {}", jarsDir, e.getMessage());
        }
        if (!failed.isEmpty()) {
            LOGGER.info("Retromod could not remove {} old cache file(s); it will try again on "
                    + "the next launch", failed.size());
        }
        return new PruneReport(removed, deleted, List.copyOf(failed));
    }

    /**
     * Rebuilds {@code serve/<loader>/<mc>/} from empty with only the requested verified jars,
     * as hard links where the file system allows and verified copies otherwise.
     *
     * <p>Loaders that take a folder rather than single files (Forge and NeoForge before 20.5)
     * load every jar in it. Pointing them at {@code jars/} would also load removed, skipped,
     * stale, and unindexed files, so they get this folder instead. Empty when the folder
     * cannot be emptied, because a leftover jar would load too.
     */
    public Optional<ServeFolder> prepareServeFolder(Collection<String> sourceSha256s) {
        if (state != State.READY) return Optional.empty();
        Path serveDir = cacheRoot.resolve(SERVE).resolve(loader).resolve(minecraftVersion);
        try {
            requireRealDirectoriesBelow(gameDir, serveDir);
            if (Files.exists(serveDir, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(serveDir)) {
                    for (Path child : children) {
                        deleteTree(child);
                    }
                }
            }
            Files.createDirectories(serveDir);
            requireRealDirectoriesBelow(gameDir, serveDir);
            try (DirectoryStream<Path> children = Files.newDirectoryStream(serveDir)) {
                if (children.iterator().hasNext()) {
                    LOGGER.warn("Could not empty {}; no translated mods are served from it",
                            serveDir);
                    return Optional.empty();
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not prepare {}: {}", serveDir, e.getMessage());
            return Optional.empty();
        }

        Map<String, Path> served = new LinkedHashMap<>();
        for (String sha : sourceSha256s) {
            Optional<Path> jar = verifiedJar(sha);
            if (jar.isEmpty()) continue;
            CacheEntry entry = index.entries().get(sha);
            Path target = serveDir.resolve(entry.file());
            if (linkOrVerifiedCopy(jar.get(), target, entry.fileSha256())) {
                served.put(sha, target);
            }
        }
        return Optional.of(new ServeFolder(serveDir, Collections.unmodifiableMap(served)));
    }

    private static boolean linkOrVerifiedCopy(Path source, Path target, String expectedSha) {
        try {
            Files.createLink(target, source);
            if (Files.isSameFile(target, source)) return true;
            Files.deleteIfExists(target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Some file systems refuse hard links; a checked copy is the fallback.
        }
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            if (sha256(target).equals(expectedSha)) return true;
            Files.deleteIfExists(target);
        } catch (IOException e) {
            LOGGER.warn("Could not place {} for loading: {}", target.getFileName(),
                    e.getMessage());
        }
        return false;
    }

    /** Writes the index when something changed. */
    public void save() throws IOException {
        if (state != State.READY || !dirty) return;
        index.write(indexFile);
        dirty = false;
    }

    /** Saves the index and releases the lock. */
    @Override
    public void close() {
        try {
            save();
        } catch (IOException e) {
            LOGGER.warn("Could not save the cache index {}: {}", indexFile, e.getMessage());
        } finally {
            for (Path staged : activeStaging) {
                try {
                    deleteTree(staged);
                } catch (IOException ignored) {
                    // prune removes staging leftovers on a later launch
                }
            }
            activeStaging.clear();
            if (lock != null) closeQuietly(lock);
        }
    }

    /**
     * The base of a translated file's name. Same rule as {@code ForgeModTransformer}, so
     * cached and rewritten jars get the same name and the same derived JPMS module name
     * (#47: spaces and odd characters break the module a loader derives from a file name).
     */
    public static String safeBaseName(String originalFileName) {
        String base = originalFileName.replace(".jar", "").replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.startsWith(".")) base = "_" + base.substring(1);
        if (base.length() > MAX_BASE_NAME) base = base.substring(0, MAX_BASE_NAME);
        return base;
    }

    /** SHA-256 of a file's bytes, lowercase hex. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String chooseFileName(SourceJar source) {
        String base = safeBaseName(source.fileName());
        String preferred = base + OUTPUT_SUFFIX;
        CacheEntry owner = ownerOf(preferred);
        if (owner == null || owner.sourceSha256().equals(source.sha256())) return preferred;
        // The same file in the same folder with new bytes is an updated mod. Its old
        // translation is stale, so the new one takes over the familiar name if the old file
        // can be removed (Windows refuses while a game still has it open).
        if (Objects.equals(owner.sourceLocation(), source.location())
                && Objects.equals(owner.sourceName(), source.fileName())
                && deleteQuietly(jarsDir.resolve(preferred))) {
            index.entries().remove(owner.sourceSha256());
            dirty = true;
            return preferred;
        }
        // The leading "h" stops a digit-only suffix from reading as a version when a loader
        // derives a module name from the file name.
        for (int length : new int[] {8, 16, 64}) {
            String hashed = base + "-retromod-h" + source.sha256().substring(0, length) + ".jar";
            CacheEntry hashedOwner = ownerOf(hashed);
            if (hashedOwner == null || hashedOwner.sourceSha256().equals(source.sha256())) {
                return hashed;
            }
        }
        throw new IllegalStateException("No free cache file name for " + source.fileName());
    }

    private CacheEntry ownerOf(String fileName) {
        for (CacheEntry entry : index.entries().values()) {
            if (fileName.equals(entry.file())) return entry;
        }
        return null;
    }

    private void drop(CacheEntry entry) {
        if (state != State.READY) return;
        index.entries().remove(entry.sourceSha256());
        if (entry.file() != null) deleteIfUnreferenced(entry.file(), entry.sourceSha256());
        dirty = true;
    }

    private void deleteIfUnreferenced(String fileName, String exceptSourceSha) {
        for (CacheEntry other : index.entries().values()) {
            if (!other.sourceSha256().equals(exceptSourceSha) && fileName.equals(other.file())) {
                return;
            }
        }
        deleteQuietly(jarsDir.resolve(fileName));
    }

    private static boolean deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void moveIntoPlace(Path staged, Path target) throws IOException {
        if (Files.isSymbolicLink(target)
                || (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Cache destination is not a regular file: " + target);
        }
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Size, modification time, and file key, plus the change time when asked. The change
     * time catches a file rewritten with its old modification time restored; the file key
     * (inode where available) catches a different file moved into place.
     */
    static String fingerprint(Path file, BasicFileAttributes attributes,
            boolean includeChangeTime) {
        StringBuilder text = new StringBuilder()
                .append(attributes.size()).append('|')
                .append(attributes.lastModifiedTime()).append('|')
                .append(attributes.fileKey());
        if (includeChangeTime) {
            text.append('|').append(changeTime(file));
        }
        return text.toString();
    }

    private static String changeTime(Path file) {
        try {
            return String.valueOf(Files.getAttribute(file, "unix:ctime"));
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException
                | SecurityException e) {
            return "";
        }
    }

    private static String oneLine(String reason) {
        if (reason == null) return null;
        String flat = reason.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() > 300 ? flat.substring(0, 300) : flat;
    }

    private void requireReady(String action) throws IOException {
        if (state != State.READY) {
            throw new IOException("Cannot " + action + ": the cache is " + state
                    + (unavailableReason == null ? "" : " (" + unavailableReason + ")"));
        }
    }

    private void requireReadyUnchecked(String action) {
        if (state != State.READY) {
            throw new IllegalStateException("Cannot " + action + ": the cache is " + state);
        }
    }

    private static void closeQuietly(CacheLock lock) {
        try {
            lock.close();
        } catch (IOException e) {
            LOGGER.debug("Could not release the cache lock: {}", e.getMessage());
        }
    }

    /** Deletes a file, link, or folder tree without following links. */
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

    /**
     * Requires every existing component from {@code root} (exclusive) to {@code path} to be a
     * real directory, not a link. Components that do not exist yet are fine.
     */
    static void requireRealDirectoriesBelow(Path root, Path path) throws IOException {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(absoluteRoot) || absolute.equals(absoluteRoot)) {
            throw new IOException(path + " is not inside " + root);
        }
        Path current = absoluteRoot;
        for (Path component : absoluteRoot.relativize(absolute)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException(current + " is a link. Retromod only uses real folders "
                        + "inside the game directory");
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(current + " is not a folder");
            }
        }
    }

    /** Resolved paths for one game directory, loader, and Minecraft version. */
    private static final class Layout {
        final Path gameDir;
        final String loader;
        final String minecraftVersion;
        final Path cacheRoot;
        final Path lockFile;
        final Path hostDir;
        final Path jarsDir;
        final Path indexFile;

        private Layout(Path gameDir, String loader, String minecraftVersion) {
            this.gameDir = gameDir;
            this.loader = loader;
            this.minecraftVersion = minecraftVersion;
            this.cacheRoot = gameDir.resolve(CACHE_ROOT);
            this.lockFile = cacheRoot.resolve(LOCK_FILE);
            this.hostDir = cacheRoot.resolve(TRANSLATED).resolve(loader).resolve(minecraftVersion);
            this.jarsDir = hostDir.resolve(JARS);
            this.indexFile = hostDir.resolve(INDEX);
        }

        /**
         * Resolves the game directory's real path once, so a game directory reached through
         * a link still works while links below it are refused.
         */
        static Layout of(Path gameDir, String loader, String minecraftVersion)
                throws IOException {
            Objects.requireNonNull(gameDir, "gameDir");
            if (loader == null || !LOADER.matcher(loader).matches()) {
                throw new IllegalArgumentException("invalid loader name: " + loader);
            }
            if (minecraftVersion == null || !MINECRAFT_VERSION.matcher(minecraftVersion).matches()
                    || minecraftVersion.contains("..")) {
                throw new IllegalArgumentException("invalid Minecraft version: "
                        + minecraftVersion);
            }
            return new Layout(gameDir.toRealPath(), loader, minecraftVersion);
        }

        static Layout unchecked(Path gameDir, String loader, String minecraftVersion) {
            String safeLoader = LOADER.matcher(loader).matches() ? loader : "unknown";
            String safeVersion = MINECRAFT_VERSION.matcher(minecraftVersion).matches()
                    && !minecraftVersion.contains("..") ? minecraftVersion : "unknown";
            return new Layout(gameDir, safeLoader, safeVersion);
        }

        void prepareHostDir() throws IOException {
            requireRealDirectoriesBelow(gameDir, hostDir);
            Files.createDirectories(hostDir);
            requireRealDirectoriesBelow(gameDir, hostDir);
        }
    }
}
