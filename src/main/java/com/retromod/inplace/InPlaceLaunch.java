/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.retromod.core.SyntheticLibraries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * What Retromod's Forge and NeoForge entry points do with {@code mods/} in this launch.
 *
 * <p>Three outcomes:
 * <ul>
 *   <li>{@link Action#REWRITE}: the flow from before in-place loading. It runs when
 *       {@code write_translated_jars} is true, and when Retromod was installed without its
 *       bootstrap, which cannot serve anything from the cache.</li>
 *   <li>{@link Action#TRANSLATE_INTO_CACHE}: the bootstrap ran in-place loading during
 *       discovery. The entry point translates what it left pending into the cache, and
 *       writes nothing into {@code mods/} or {@code retromod-input/}.</li>
 *   <li>{@link Action#LEAVE_ALONE}: in-place mode is on but the discovery pass could not run.
 *       Rewriting {@code mods/} would go against the setting, so nothing is changed.</li>
 * </ul>
 */
public final class InPlaceLaunch {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-InPlace");

    /** Set by the bootstrap that loaded Retromod. Absent when Retromod sits in mods/ alone. */
    static final String BOOT_OWNER_PROPERTY = InPlaceSettings.BOOT_OWNER_PROPERTY;

    static final Duration LOCK_WAIT = Duration.ofSeconds(10);

    /**
     * Launches an I/O failure is retried before it counts as a failure. A full disk clears
     * up; an archive the transformer cannot read never does, and must not be retried, and
     * held back, on every launch.
     */
    static final int MAX_TRANSLATION_ATTEMPTS = 3;

    /** What the entry point does with {@code mods/}. */
    public enum Action { REWRITE, TRANSLATE_INTO_CACHE, LEAVE_ALONE }

    /**
     * What in-place loading did in this launch, for Retromod's screen.
     *
     * @param served     translated copies the loader received
     * @param translated new translations that load after a restart
     * @param failed     sources whose translation failed and waits for a change
     */
    public record Summary(int served, int translated, int failed) {}

    /**
     * The discovery pass reports before translation runs, so its status file cannot say what
     * happened after it. The entry point and the screen share this process, so it lives here.
     */
    private static volatile Summary summary;

    /** Translates one jar into a folder, the way the runtime transformer always has. */
    @FunctionalInterface
    public interface Translator {
        /** Returns the translated jar inside {@code outputDir}, or null when it failed. */
        Path translate(Path sourceJar, Path outputDir) throws IOException;

        /** Translates a jar that may build on {@code libraries}, the copies translated before it. */
        default Path translate(Path sourceJar, Path outputDir, SyntheticLibraries libraries)
                throws IOException {
            return translate(sourceJar, outputDir);
        }
    }

    /**
     * A translator that links each jar against the libraries translated before it, so an addon
     * reuses its library's stand-in classes. See {@link SyntheticLibraries}.
     */
    @FunctionalInterface
    public interface LinkingTranslator extends Translator {
        @Override
        Path translate(Path sourceJar, Path outputDir, SyntheticLibraries libraries)
                throws IOException;

        @Override
        default Path translate(Path sourceJar, Path outputDir) throws IOException {
            return translate(sourceJar, outputDir, SyntheticLibraries.NONE);
        }
    }

    private final Action action;
    private final InPlaceStatus status;
    private final String reason;
    private final java.util.Set<String> hiddenPaths;

    private InPlaceLaunch(Action action, InPlaceStatus status, String reason) {
        this.action = action;
        this.status = status;
        this.reason = reason;
        this.hiddenPaths = status == null ? java.util.Set.of() : status.hiddenPaths();
    }

    /** Decides from this JVM's system properties and the game directory's config. */
    public static InPlaceLaunch forEntryPoint(Path gameDir) {
        return decide(gameDir, System.getProperties());
    }

    static InPlaceLaunch decide(Path gameDir, Properties properties) {
        String mode = properties.getProperty(InPlaceStatus.MODE_PROPERTY);
        if (InPlaceStatus.MODE_REWRITE.equals(mode)) {
            return new InPlaceLaunch(Action.REWRITE, null, "write_translated_jars is true");
        }
        if (InPlaceStatus.MODE_IN_PLACE.equals(mode)) {
            String statusPath = properties.getProperty(InPlaceStatus.STATUS_PROPERTY);
            if (statusPath == null || statusPath.isBlank()) {
                return new InPlaceLaunch(Action.LEAVE_ALONE, null,
                        "the discovery pass wrote no status");
            }
            try {
                InPlaceStatus status = InPlaceStatus.read(Path.of(statusPath));
                return new InPlaceLaunch(Action.TRANSLATE_INTO_CACHE, status, null);
            } catch (IOException | RuntimeException e) {
                return new InPlaceLaunch(Action.LEAVE_ALONE, null,
                        "the discovery status could not be read: " + e.getMessage());
            }
        }
        if (InPlaceStatus.MODE_UNAVAILABLE.equals(mode)) {
            return new InPlaceLaunch(Action.LEAVE_ALONE, null,
                    "the translated mod cache could not be used this launch");
        }
        InPlaceSettings.Mode configured = InPlaceSettings.resolve(gameDir, properties);
        if (configured.writeTranslatedJars()) {
            return new InPlaceLaunch(Action.REWRITE, null, "write_translated_jars is true");
        }
        if (properties.getProperty(BOOT_OWNER_PROPERTY) == null) {
            return new InPlaceLaunch(Action.REWRITE, null,
                    "Retromod was loaded without its bootstrap, which in-place loading needs");
        }
        return new InPlaceLaunch(Action.LEAVE_ALONE, null,
                "the bootstrap did not finish its discovery pass");
    }

    public Action action() {
        return action;
    }

    /** True when the entry point should run the rewrite flow from before in-place loading. */
    public boolean rewrite() {
        return action == Action.REWRITE;
    }

    /** Why this action was chosen, or null for in-place translation. */
    public String reason() {
        return reason;
    }

    public Optional<InPlaceStatus> status() {
        return Optional.ofNullable(status);
    }

    /**
     * True for an original the bootstrap kept away from the loader. Retromod's own scans of
     * {@code mods/} skip these: the loader runs the translated copy, or nothing, instead.
     */
    public boolean isHiddenOriginal(Path jar) {
        if (jar == null || hiddenPaths.isEmpty()) return false;
        return hiddenPaths.contains(jar.toAbsolutePath().normalize().toString())
                || hiddenPaths.contains(InPlaceStatus.resolved(jar));
    }

    /** Logs the decision once, in words a player can act on. */
    public void logDecision() {
        switch (action) {
            case REWRITE -> LOGGER.info("Retromod writes translated mods into mods/ this launch "
                    + "({})", reason);
            case TRANSLATE_INTO_CACHE -> LOGGER.info("In-place loading is on. Translated mods "
                    + "live in retromod-cache/, and mods/ is not changed.");
            case LEAVE_ALONE -> LOGGER.warn("In-place loading is on, but {}. Retromod leaves "
                    + "mods/ unchanged this launch. Set write_translated_jars to true in "
                    + "config/retromod/config.json to use the rewrite mode instead, and "
                    + "please report this with logs/latest.log.", reason);
        }
    }

    /**
     * Translates every source the discovery pass left pending into the cache, with the same
     * transformer the rewrite flow uses. A failure is recorded so the next launch does not
     * try again until Retromod, a setting, or the file changes, which keeps a broken mod from
     * asking for a restart forever.
     *
     * @param targetMinecraft   the version the translator translates for; it must be the one
     *                          the discovery pass used, or the cache would hold output for
     *                          another version under this one's folder
     * @param translatorFactory creates the translator once, only when something is pending
     * @return how many new translations are ready for the next launch
     */
    public int translatePending(String targetMinecraft, Supplier<Translator> translatorFactory) {
        Objects.requireNonNull(translatorFactory, "translatorFactory");
        if (status == null) return 0;
        summary = new Summary(status.served().size(), 0, status.failed().size());
        if (status.pending().isEmpty()) return 0;
        if (status.gameDir() == null || status.loader() == null || status.minecraft() == null) {
            LOGGER.warn("The in-place status names no cache; nothing is translated this launch");
            return 0;
        }
        if (!status.minecraft().equals(targetMinecraft)) {
            LOGGER.warn("Retromod's loader reported Minecraft {} but the game reports {}. "
                    + "Nothing is translated this launch. Please report this with "
                    + "logs/latest.log.", status.minecraft(), targetMinecraft);
            return 0;
        }
        int ready = 0;
        try (TranslatedModCache cache = TranslatedModCache.attach(status.gameDir(),
                status.loader(), status.minecraft(), LOCK_WAIT)) {
            if (cache.state() != TranslatedModCache.State.READY) {
                LOGGER.warn("Retromod cannot translate new mods this launch: {}. They are "
                        + "translated on a later launch.",
                        Objects.requireNonNullElse(cache.unavailableReason(),
                                "another game is using the cache"));
                return 0;
            }
            Translator translator = translatorFactory.get();
            SyntheticLibraries libraries = servedLibraries(cache);
            int failed = 0;
            // A library goes first, so the addons built on it can reuse its stand-in classes.
            for (InPlaceStatus.Source row : SyntheticLibraries.librariesFirst(status.pending(),
                    row -> Path.of(row.source()))) {
                if (translateOne(cache, row, translator, libraries)) {
                    ready++;
                } else if (hasFailed(cache, row.sha256())) {
                    failed++;
                }
            }
            ready += relinkServed(cache, translator, libraries);
            addToSummary(ready, failed);
        }
        return ready;
    }

    /** The translated copies served this launch, as libraries for the jars translated now. */
    private SyntheticLibraries servedLibraries(TranslatedModCache cache) {
        SyntheticLibraries libraries = SyntheticLibraries.create();
        for (InPlaceStatus.Served row : status.served()) {
            cache.verifiedJar(row.sha256()).ifPresent(libraries::add);
        }
        return libraries;
    }

    /**
     * Translates again each served copy whose links no longer match its libraries: a copy it
     * reuses was translated away, or a library it builds on arrived after it. Without this an
     * addon kept failing at the library's API until its own file changed. The new copy is served
     * from the next launch.
     */
    private int relinkServed(TranslatedModCache cache, Translator translator,
            SyntheticLibraries libraries) {
        int relinked = 0;
        for (InPlaceStatus.Served row : status.served()) {
            Optional<Path> served = cache.verifiedJar(row.sha256());
            if (served.isEmpty() || !libraries.isStale(served.get())) continue;
            LOGGER.info("{} builds on a mod that changed; it is translated again",
                    Path.of(row.source()).getFileName());
            cache.forget(row.sha256());
            InPlaceStatus.Source source = new InPlaceStatus.Source(row.source(), row.location(),
                    row.sha256(), false, null);
            if (translateOne(cache, source, translator, libraries)) relinked++;
        }
        return relinked;
    }

    /** What in-place loading did this launch, or empty when it did not run. */
    public static Optional<Summary> summaryThisLaunch() {
        return Optional.ofNullable(summary);
    }

    private static synchronized void addToSummary(int translated, int failed) {
        Summary current = summary == null ? new Summary(0, 0, 0) : summary;
        summary = new Summary(current.served(), current.translated() + translated,
                current.failed() + failed);
    }

    private static boolean hasFailed(TranslatedModCache cache, String sourceSha256) {
        return cache.entry(sourceSha256)
                .map(entry -> entry.status() == CacheEntry.Status.FAILED)
                .orElse(false);
    }

    /** What happened to a jar the player chose in Retromod's file picker. */
    public enum ChosenOutcome {
        /** Translated into the cache now. The next launch loads the copy. */
        TRANSLATED,
        /** The cache already holds a translation of these exact bytes. */
        ALREADY_TRANSLATED,
        /** Translation failed. It is not tried again until Retromod or the file changes. */
        FAILED,
        /** The cache cannot take it now. The next launch translates it and asks to restart. */
        NEXT_LAUNCH
    }

    /**
     * Translates a jar the player just copied into {@code mods/} through the file picker. The
     * next launch then serves the copy, so the player restarts once instead of twice. It uses
     * the same steps as {@link #translatePending}, so the copy matches what a launch would
     * have produced.
     */
    public ChosenOutcome translateChosen(Path jarInMods, String targetMinecraft,
            Supplier<Translator> translatorFactory) {
        Objects.requireNonNull(translatorFactory, "translatorFactory");
        if (action != Action.TRANSLATE_INTO_CACHE || status == null || status.gameDir() == null
                || status.loader() == null || !Objects.equals(status.minecraft(),
                        targetMinecraft)) {
            return ChosenOutcome.NEXT_LAUNCH;
        }
        try (TranslatedModCache cache = TranslatedModCache.attach(status.gameDir(),
                status.loader(), status.minecraft(), LOCK_WAIT)) {
            if (cache.state() != TranslatedModCache.State.READY) return ChosenOutcome.NEXT_LAUNCH;
            SourceJar described;
            try {
                described = cache.describe(jarInMods, DiscoveryEngine.MODS);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not read {}: {}", jarInMods.getFileName(), e.getMessage());
                return ChosenOutcome.NEXT_LAUNCH;
            }
            if (cache.verifiedJar(described.sha256()).isPresent()) {
                return ChosenOutcome.ALREADY_TRANSLATED;
            }
            // A failure the summary already counts must not be counted again.
            boolean failedBefore = hasFailed(cache, described.sha256());
            InPlaceStatus.Source row = new InPlaceStatus.Source(
                    DiscoveryEngine.absolute(jarInMods), DiscoveryEngine.MODS,
                    described.sha256(), false, null);
            if (translateOne(cache, row, translatorFactory.get(), servedLibraries(cache))) {
                addToSummary(1, 0);
                return ChosenOutcome.TRANSLATED;
            }
            if (hasFailed(cache, described.sha256())) {
                if (!failedBefore) addToSummary(0, 1);
                return ChosenOutcome.FAILED;
            }
            return ChosenOutcome.NEXT_LAUNCH;
        }
    }

    private static boolean translateOne(TranslatedModCache cache, InPlaceStatus.Source row,
            Translator translator, SyntheticLibraries libraries) {
        Path source = Path.of(row.source());
        String name = String.valueOf(source.getFileName());
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            LOGGER.info("{} is gone; it is not translated", name);
            return false;
        }
        SourceJar described;
        try {
            described = cache.describe(source,
                    row.location() == null ? DiscoveryEngine.MODS : row.location());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}: {}", name, e.getMessage());
            return false;
        }
        if (!described.sha256().equals(row.sha256())) {
            LOGGER.info("{} changed since the game started; it is translated on the next launch",
                    name);
            return false;
        }
        if (!cache.shouldTranslate(described.sha256())) return false;

        Path staging = null;
        try {
            staging = cache.newStagingDirectory();
            cache.markStarted(described);
            LOGGER.info("Translating {} into Retromod's cache", name);
            Path translated = translator.translate(source, staging, libraries);
            if (translated == null || !Files.isRegularFile(translated, LinkOption.NOFOLLOW_LINKS)) {
                cache.recordFailure(described, "Retromod could not translate this file. "
                        + "logs/latest.log has the reason.");
                LOGGER.warn("Retromod could not translate {}. It is not tried again until "
                        + "Retromod or the file changes.", name);
                return false;
            }
            Optional<ForgeModMetadata> metadata = ForgeModMetadata.readEffective(translated);
            cache.publish(described, translated,
                    metadata.map(ForgeModMetadata::modIds).orElse(java.util.List.of()),
                    metadata.map(ForgeModMetadata::firstVersion).orElse(null));
            cache.verifiedJar(described.sha256()).ifPresent(libraries::add);
            return true;
        } catch (IOException e) {
            int attempts = cache.entry(described.sha256()).map(CacheEntry::attempts).orElse(0);
            if (attempts >= MAX_TRANSLATION_ATTEMPTS) {
                cache.recordFailure(described, e.toString());
                LOGGER.warn("Retromod could not translate {} in {} launches: {}. It is not "
                        + "tried again until Retromod or the file changes.", name, attempts,
                        e.getMessage());
                return false;
            }
            // A full disk or a busy file clears up, so the next launch tries again.
            cache.recordRetry(described, e.toString());
            LOGGER.warn("Could not translate {}: {}. Retromod tries again on the next launch.",
                    name, e.getMessage());
            return false;
        } catch (RuntimeException | LinkageError | StackOverflowError e) {
            // The transformer only catches Exception. A stack overflow from a huge method
            // unwinds cleanly and repeats on every attempt, so it is a failure like any other.
            // Other errors propagate; the STARTED record counts them on the next launch.
            cache.recordFailure(described, e.toString());
            LOGGER.warn("Retromod could not translate {}: {}. It is not tried again until "
                    + "Retromod or the file changes.", name, e.toString());
            return false;
        } finally {
            cache.deleteStaging(staging);
        }
    }
}
