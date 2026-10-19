/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodVersion;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.util.OptOutCheck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * The discovery-time half of in-place loading on Forge and NeoForge.
 *
 * <p>The bootstrap locator calls {@link #run(Map)} while the loader is still listing mod files,
 * before any mod code runs. This class decides, for every jar in {@code mods/} and
 * {@code retromod-input/}:
 *
 * <ul>
 *   <li>a cached translation exists: serve it and keep the original away from the loader;</li>
 *   <li>no translation yet: record it as pending for Retromod's entry point, and keep the
 *       original away only when the loader would refuse or skip it anyway. Holding back a jar
 *       the loader accepts could break a mod that depends on it, so it loads untranslated
 *       once, as it did before in-place loading;</li>
 *   <li>translation failed before: do not retry until Retromod, a setting, or the file
 *       changes.</li>
 * </ul>
 *
 * <p>It translates nothing itself. Translating at discovery time needs a host model that does
 * not probe the running game, which is a later phase. Nothing here may load a Minecraft or
 * loader class: the bootstrap runs this class in a private class loader that cannot see them.
 *
 * <p>Only JDK types cross the call, because the bootstrap and this class live in different
 * class loaders.
 */
public final class DiscoveryEngine {

    /** Bumped when the request or result maps change shape. */
    public static final int PROTOCOL = 1;

    /**
     * Launches in a row a hidden source may wait for a translation that never runs. Past
     * this, the original is shown to the loader once, so its own error names the file and a
     * mod is never hidden forever (for example when another mod requires it and the loader
     * stops before Retromod's entry point runs).
     */
    static final int MAX_HIDDEN_LAUNCHES = 3;

    static final Duration LOCK_WAIT = Duration.ofSeconds(5);

    static final String MODS = "mods";
    static final String INPUT = "retromod-input";

    /** How the loader takes extra mod files. */
    public enum ServeStyle {
        /** Forge and NeoForge 1.20.1 take a folder and load every jar in it. */
        FOLDER,
        /** NeoForge 20.5 and newer take single files. */
        FILES
    }

    /**
     * One discovery pass.
     *
     * @param modsListing  the jars exactly as the loader lists {@code mods/}; hidden paths are
     *                     returned as these same objects because loaders compare raw paths
     * @param hostToml     the mods toml the host reads, {@code META-INF/mods.toml} or
     *                     {@code META-INF/neoforge.mods.toml}
     */
    public record Request(Path gameDir, Path modsDir, List<Path> modsListing, String loader,
            String minecraftVersion, String loaderBuild, String hostToml, ServeStyle serveStyle,
            Properties properties, BiConsumer<String, String> log) {

        public Request {
            Objects.requireNonNull(gameDir, "gameDir");
            Objects.requireNonNull(loader, "loader");
            Objects.requireNonNull(minecraftVersion, "minecraftVersion");
            Objects.requireNonNull(hostToml, "hostToml");
            Objects.requireNonNull(serveStyle, "serveStyle");
            modsListing = modsListing == null ? List.of() : List.copyOf(modsListing);
            properties = properties == null ? new Properties() : properties;
            log = log == null ? (level, message) -> { } : log;
        }
    }

    /**
     * What the bootstrap does with the pass.
     *
     * @param hide        originals from {@code modsListing} to keep away from the loader
     * @param serveFolder for {@link ServeStyle#FOLDER}: a folder holding only verified copies
     * @param serveFiles  for {@link ServeStyle#FILES}: verified copies to add one by one
     * @param statusFile  the status file to publish, or null
     */
    public record Outcome(String mode, List<Path> hide, Path serveFolder, List<Path> serveFiles,
            Path statusFile, String summary) {

        public Outcome {
            hide = List.copyOf(hide);
            serveFiles = List.copyOf(serveFiles);
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new HashMap<>();
            result.put("protocol", PROTOCOL);
            result.put("mode", mode);
            result.put("hide", hide);
            result.put("serveFolder", serveFolder);
            result.put("serveFiles", serveFiles);
            result.put("statusFile", statusFile);
            result.put("summary", summary);
            return result;
        }
    }

    /** What one jar is, as far as in-place loading cares. */
    enum Kind {
        /** Not a Forge-family mod, or unreadable. Left to the loader and other translators. */
        IGNORED,
        /** A Retromod bootstrap. The bootstrap keeps these out of the mod list itself. */
        RETROMOD_BOOTSTRAP,
        /** A plain Retromod runtime jar from before the bootstrap layout. */
        STALE_RETROMOD,
        /** Already translated by Retromod. */
        TRANSLATED,
        /** The author opted out of translation. */
        OPTED_OUT,
        /** Already built for this Minecraft version. */
        CURRENT,
        /** Needs translation. */
        OLD
    }

    /**
     * One classified jar. {@code effective} is the toml that describes its code;
     * {@code loaderWouldLoad} comes from the toml the host reads.
     */
    record Classified(Path path, String location, Kind kind, Optional<ForgeModMetadata> effective,
            boolean loaderWouldLoad) {

        String fileName() {
            return path.getFileName().toString();
        }

        List<String> modIds() {
            return effective.map(ForgeModMetadata::modIds).orElse(List.of());
        }
    }

    private DiscoveryEngine() {}

    /**
     * Entry for the bootstrap, which can only pass JDK types. Never throws: a failed pass
     * leaves the loader's own behavior in place.
     */
    public static Map<String, Object> run(Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        BiConsumer<String, String> log = request.get("log") instanceof BiConsumer<?, ?> callback
                ? (BiConsumer<String, String>) callback : (level, message) -> { };
        try {
            Object protocol = request.get("protocol");
            if (!(protocol instanceof Integer number) || number != PROTOCOL) {
                log.accept("warn", "Retromod's bootstrap and runtime do not match (protocol "
                        + protocol + ", expected " + PROTOCOL + "). In-place loading is off for "
                        + "this launch. Keep one Retromod file in mods/.");
                return Map.of("protocol", PROTOCOL, "mode", "error");
            }
            @SuppressWarnings("unchecked")
            List<Path> listing = (List<Path>) request.get("modsListing");
            Request typed = new Request((Path) request.get("gameDir"),
                    (Path) request.get("modsDir"), listing, (String) request.get("loader"),
                    (String) request.get("minecraft"), (String) request.get("loaderBuild"),
                    (String) request.get("hostToml"),
                    ServeStyle.valueOf(((String) request.get("serveStyle"))
                            .toUpperCase(Locale.ROOT)),
                    (Properties) request.get("properties"), log);
            return run(typed).toMap();
        } catch (Throwable failure) {
            log.accept("error", "Retromod's in-place pass failed: " + failure
                    + ". The loader continues without it. Please report this with "
                    + "logs/latest.log.");
            return Map.of("protocol", PROTOCOL, "mode", "error");
        }
    }

    /** Runs one pass. Sets the host version Retromod's metadata readers compare against. */
    public static Outcome run(Request request) {
        String previousTarget = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = request.minecraftVersion();
        try {
            return new Pass(request).run();
        } finally {
            RetromodVersion.TARGET_MC_VERSION = previousTarget;
        }
    }

    /** State for one pass. */
    private static final class Pass {
        private final Request request;
        private final List<Path> hide = new ArrayList<>();
        private final List<String> hiddenNames = new ArrayList<>();

        Pass(Request request) {
            this.request = request;
        }

        private void info(String message) {
            request.log().accept("info", message);
        }

        private void warn(String message) {
            request.log().accept("warn", message);
        }

        Outcome run() {
            InPlaceSettings.Mode mode = InPlaceSettings.resolve(request.gameDir(),
                    request.properties());
            if (mode.writeTranslatedJars()) return runRewrite();
            List<Classified> mods = classifyAll(request.modsListing(), MODS, true);
            hideStaleRuntimes(mods);
            List<Classified> inputs = classifyAll(listInputFolder(), INPUT, true);
            try (TranslatedModCache cache = TranslatedModCache.open(request.gameDir(),
                    request.loader(), request.minecraftVersion(),
                    TransformContext.forGame(request.gameDir(), request.loader(),
                            request.loaderBuild(), request.properties()),
                    LOCK_WAIT)) {
                if (cache.state() == TranslatedModCache.State.UNAVAILABLE) {
                    warn("In-place loading is off for this launch: "
                            + cache.unavailableReason() + ". Old mods in mods/ load as if "
                            + "Retromod were not installed. Set write_translated_jars to true "
                            + "in config/retromod/config.json to use the older rewrite mode.");
                    return new Outcome(InPlaceStatus.MODE_UNAVAILABLE, hide, null, List.of(),
                            null, "cache unavailable");
                }
                return new InPlacePass(this, cache, mods, inputs).run();
            }
        }

        /**
         * Rewrite mode leaves the cache alone and runs the earlier flow. One exception keeps
         * a switch back from in-place mode working: an old original that in-place mode
         * handled is still in {@code mods/}, untranslated. If the loader refuses it, the
         * launch stops before Retromod's entry point can rewrite it. Holding back exactly
         * those originals lets the entry point rewrite them in this launch. Without a cache
         * index, only an older Retromod file is looked for, which keeps this pass cheap.
         */
        private Outcome runRewrite() {
            Set<String> known = TranslatedModCache.knownSources(request.gameDir(),
                    request.loader(), request.minecraftVersion());
            List<Classified> mods = classifyAll(request.modsListing(), MODS, !known.isEmpty());
            hideStaleRuntimes(mods);
            for (Classified jar : mods) {
                if (jar.kind() != Kind.OLD || jar.loaderWouldLoad()) continue;
                // Only what the rewrite flow will rewrite: it skips these names, and a jar it
                // never rewrites would otherwise be held back on every launch.
                if (!rewriteFlowTakes(jar.fileName())) continue;
                try {
                    if (known.contains(TranslatedModCache.sha256(jar.path()))) {
                        hideOriginal(jar);
                        info("Holding back " + jar.fileName() + " for this launch so Retromod "
                                + "can rewrite it into mods/. In-place mode handled it before.");
                    }
                } catch (IOException e) {
                    // An unreadable jar is left to the loader, which reports it.
                }
            }
            return new Outcome(InPlaceStatus.MODE_REWRITE, hide, null, List.of(), null,
                    "rewrite mode (write_translated_jars is true)");
        }

        /** The file names the Forge and NeoForge rewrite flow leaves alone in {@code mods/}. */
        static boolean rewriteFlowTakes(String fileName) {
            return !fileName.contains("-retromod") && !fileName.startsWith("retromod");
        }

        private void hideStaleRuntimes(List<Classified> mods) {
            for (Classified jar : mods) {
                if (jar.kind() != Kind.STALE_RETROMOD) continue;
                hideOriginal(jar);
                warn("Found an older Retromod file in mods/: " + jar.fileName()
                        + ". Retromod now loads from its newer file, so this one is ignored. "
                        + "Delete it.");
            }
        }

        private List<Path> listInputFolder() {
            Path input = request.gameDir().resolve(INPUT);
            if (!Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) return List.of();
            try (Stream<Path> files = Files.list(input)) {
                return files.sorted().toList();
            } catch (IOException e) {
                warn("Could not list " + INPUT + "/: " + e.getMessage());
                return List.of();
            }
        }

        /** With {@code full} false, only Retromod's own files are recognized. */
        private List<Classified> classifyAll(List<Path> paths, String location, boolean full) {
            List<Classified> classified = new ArrayList<>();
            for (Path path : paths) {
                classified.add(classify(path, location, full));
            }
            return classified;
        }

        Classified classify(Path path, String location, boolean full) {
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            if (name.startsWith(".") || !name.toLowerCase(Locale.ROOT).endsWith(".jar")
                    || !Files.isRegularFile(path)) {
                return ignored(path, location);
            }
            Optional<ForgeModMetadata> effective;
            Optional<ForgeModMetadata> forHost;
            Manifest manifest;
            boolean bootstrap;
            try (JarFile jar = new JarFile(path.toFile(), false)) {
                manifest = jar.getManifest();
                bootstrap = jar.getEntry("META-INF/retromod/runtime.jar") != null
                        && manifest != null && "Retromod".equals(
                                manifest.getMainAttributes().getValue("Implementation-Title"));
                String effectiveEntry = com.retromod.core.ForgeTomlChoice.metadataEntry(jar);
                effective = effectiveEntry == null ? Optional.empty()
                        : ForgeModMetadata.read(jar, effectiveEntry);
                forHost = ForgeModMetadata.read(jar, request.hostToml());
            } catch (IOException | RuntimeException e) {
                return ignored(path, location);
            }
            if (bootstrap) {
                return new Classified(path, location, Kind.RETROMOD_BOOTSTRAP, effective, true);
            }
            if (effective.flatMap(ForgeModMetadata::primaryModId)
                    .filter("retromod"::equals).isPresent()) {
                return new Classified(path, location, Kind.STALE_RETROMOD, effective, true);
            }
            if (!full) return ignored(path, location);
            if (TranslatedJarMarkers.isRetromodOutput(manifest)) {
                warnIfTranslatedForAnotherVersion(name, manifest);
                return new Classified(path, location, Kind.TRANSLATED, effective, true);
            }
            ModVersionInfo info;
            try {
                info = ModVersionDetector.metadataOnly().detectVersion(path);
            } catch (IOException | RuntimeException | LinkageError e) {
                return ignored(path, location);
            }
            if (info == null || !isForgeFamily(info.modLoaderType())) {
                return ignored(path, location);
            }
            boolean wouldLoad = ForgeModMetadata.loaderWouldLoad(forHost, request.hostToml(),
                    request.minecraftVersion(), request.loaderBuild());
            // The rewrite flow sends every retromod-input/ jar through the transformer, which
            // itself copies opted-out and current jars unchanged. Treating them all as inputs
            // keeps the cache's files the same as the rewrite flow's.
            boolean input = INPUT.equals(location);
            if (!input && OptOutCheck.isOptedOut(path)) {
                return new Classified(path, location, Kind.OPTED_OUT, effective, wouldLoad);
            }
            // A jar with no readable Minecraft version counts as current, except when the loader
            // would refuse it anyway: Macaw's Forge 1.21.1 jars declare no minecraft dependency,
            // and NeoForge skips their Forge-only mods.toml. Translation is the only way such a
            // jar loads, as it always was from retromod-input/.
            boolean unknownAndRefused = info.targetMcVersion() == null && !wouldLoad;
            if (!input && !unknownAndRefused && !info.needsTransformation(request.minecraftVersion())) {
                return new Classified(path, location, Kind.CURRENT, effective, wouldLoad);
            }
            if (Files.isSymbolicLink(path)) {
                // ForgeModTransformer refuses links, as the rewrite flow always has, so holding
                // this jar back would only hide it while it waits for a translation that never
                // comes. The loader gets it as it is.
                info("Leaving " + name + " to the loader as it is: " + name + " is a link, and "
                        + "Retromod only translates real files. Put the jar itself in "
                        + location + "/ to have it translated.");
                return ignored(path, location);
            }
            return new Classified(path, location, Kind.OLD, effective, wouldLoad);
        }

        /**
         * A translated jar is left as it is. One translated for an older Minecraft version (a
         * shared export, or an instance moved to a new version) has a range widened to accept
         * this host while it carries the other version's fixes, and the loader says nothing.
         * One for a newer version is refused by the loader, and naming it first points at
         * the fix.
         */
        private void warnIfTranslatedForAnotherVersion(String name, Manifest manifest) {
            String target = manifest.getMainAttributes()
                    .getValue(TranslatedJarMarkers.TARGET_VERSION);
            if (target == null || target.isBlank()) return;
            String version = target.trim();
            if (version.equals(request.minecraftVersion())) return;
            if (version.length() > 64) version = version.substring(0, 64);
            warn(name + " was translated by Retromod for Minecraft " + version + ", not "
                    + request.minecraftVersion() + ". Retromod leaves it as it is, so the loader "
                    + "may reject it or it may fail later. Put its original jar in mods/ "
                    + "instead, so Retromod translates it for this game.");
        }

        private static boolean isForgeFamily(String loaderType) {
            return "forge".equals(loaderType) || "neoforge".equals(loaderType);
        }

        /** Left to the loader and to other translators: not a jar, unreadable, or not Forge. */
        private static Classified ignored(Path path, String location) {
            return new Classified(path, location, Kind.IGNORED, Optional.empty(), true);
        }

        void hideOriginal(Classified jar) {
            if (!MODS.equals(jar.location())) return;
            if (!hide.contains(jar.path())) {
                hide.add(jar.path());
                hiddenNames.add(jar.path().toAbsolutePath().normalize().toString());
            }
        }
    }

    /** The in-place half of a pass, once the cache is open. */
    private static final class InPlacePass {
        private final Pass pass;
        private final Request request;
        private final TranslatedModCache cache;
        private final List<Classified> mods;
        private final List<Classified> inputs;

        private final List<InPlaceStatus.Served> served = new ArrayList<>();
        private final List<InPlaceStatus.Source> pending = new ArrayList<>();
        private final List<InPlaceStatus.Source> failed = new ArrayList<>();
        private final List<InPlaceStatus.Source> superseded = new ArrayList<>();
        private final Map<String, Path> servedJars = new LinkedHashMap<>();
        private final Map<String, Classified> servedSources = new LinkedHashMap<>();
        private final Set<String> live = new LinkedHashSet<>();

        InPlacePass(Pass pass, TranslatedModCache cache, List<Classified> mods,
                List<Classified> inputs) {
            this.pass = pass;
            this.request = pass.request;
            this.cache = cache;
            this.mods = mods;
            this.inputs = inputs;
        }

        Outcome run() {
            boolean ready = cache.state() == TranslatedModCache.State.READY;
            if (!ready) {
                pass.warn("Another game is using Retromod's cache. Already translated mods "
                        + "load as usual; new ones wait for a later launch.");
            }
            List<Candidate> candidates = describeCandidates();
            Set<String> providedByOthers = modIdsOfLoadedJars();
            Map<String, Candidate> winners = chooseWinners(candidates);

            for (Candidate candidate : candidates) {
                Optional<String> primary = candidate.jar().effective()
                        .flatMap(ForgeModMetadata::primaryModId);
                if (primary.isPresent() && providedByOthers.contains(primary.get())) {
                    supersede(candidate, "another jar in mods/ already provides "
                            + primary.get());
                    continue;
                }
                if (primary.isPresent() && winners.get(primary.get()) != candidate) {
                    supersede(candidate, "a newer copy of " + primary.get() + " is installed");
                    continue;
                }
                decide(candidate);
            }

            if (ready) {
                TranslatedModCache.PruneReport report = cache.prune(live);
                if (report.removedEntries() > 0) {
                    pass.info("Removed " + report.removedEntries() + " translation(s) whose "
                            + "original is gone.");
                }
            }
            Path serveFolder = null;
            List<Path> serveFiles = new ArrayList<>();
            if (request.serveStyle() == ServeStyle.FOLDER) {
                serveFolder = buildServeFolder();
            } else {
                serveFiles.addAll(servedJars.values());
            }
            warnAboutBlockedDependents();

            InPlaceStatus status = new InPlaceStatus(InPlaceStatus.MODE_IN_PLACE,
                    request.loader(), request.minecraftVersion(), cache.gameDirectory(),
                    cache.state().name(), served, pending, failed, superseded, pass.hiddenNames);
            Path statusFile = cache.statusFileForThisLaunch();
            try {
                status.write(statusFile);
            } catch (IOException e) {
                pass.warn("Could not write " + statusFile + ": " + e.getMessage()
                        + ". Retromod translates nothing this launch.");
                statusFile = null;
            }
            String summary = served.size() + " served, " + pending.size() + " waiting, "
                    + failed.size() + " failed, " + pass.hide.size() + " held back";
            pass.info("In-place loading: " + summary + ".");
            for (InPlaceStatus.Served copy : served) {
                pass.info("Serving " + Path.of(copy.source()).getFileName() + " from "
                        + cache.gameDirectory().relativize(Path.of(copy.file())));
            }
            return new Outcome(InPlaceStatus.MODE_IN_PLACE, pass.hide, serveFolder, serveFiles,
                    statusFile, summary);
        }

        private record Candidate(Classified jar, SourceJar source) {}

        private List<Candidate> describeCandidates() {
            List<Candidate> candidates = new ArrayList<>();
            List<Classified> all = new ArrayList<>(mods);
            all.addAll(inputs);
            for (Classified jar : all) {
                if (jar.kind() != Kind.OLD) continue;
                try {
                    candidates.add(new Candidate(jar, cache.describe(jar.path(), jar.location())));
                } catch (IOException | RuntimeException e) {
                    pass.warn("Could not read " + jar.fileName() + ": " + e.getMessage()
                            + ". It is left to the loader.");
                }
            }
            return candidates;
        }

        /** Mod ids of jars in {@code mods/} that load as they are. */
        private Set<String> modIdsOfLoadedJars() {
            Set<String> ids = new LinkedHashSet<>();
            for (Classified jar : mods) {
                if (jar.kind() == Kind.CURRENT || jar.kind() == Kind.TRANSLATED
                        || jar.kind() == Kind.OPTED_OUT) {
                    ids.addAll(jar.modIds());
                }
            }
            return ids;
        }

        /**
         * One candidate per first mod id, like the loaders keep one file per mod: the highest
         * version, then a jar in {@code mods/} over one in {@code retromod-input/}, then the
         * first file name.
         */
        private Map<String, Candidate> chooseWinners(List<Candidate> candidates) {
            Comparator<Candidate> preference = Comparator
                    .comparing((Candidate c) -> c.jar().effective()
                            .map(ForgeModMetadata::firstVersion).orElse(""),
                            DiscoveryEngine::compareModVersions).reversed()
                    .thenComparing(c -> MODS.equals(c.jar().location()) ? 0 : 1)
                    .thenComparing(c -> c.jar().fileName());
            Map<String, Candidate> winners = new HashMap<>();
            for (Candidate candidate : candidates) {
                Optional<String> primary = candidate.jar().effective()
                        .flatMap(ForgeModMetadata::primaryModId);
                if (primary.isEmpty()) continue;
                winners.merge(primary.get(), candidate,
                        (a, b) -> preference.compare(a, b) <= 0 ? a : b);
            }
            return winners;
        }

        private void supersede(Candidate candidate, String reason) {
            pass.hideOriginal(candidate.jar());
            superseded.add(source(candidate, MODS.equals(candidate.jar().location()), reason));
            pass.info("Leaving out " + candidate.jar().fileName() + ": " + reason + ".");
        }

        private void decide(Candidate candidate) {
            Classified jar = candidate.jar();
            String sha = candidate.source().sha256();
            live.add(sha);
            Optional<Path> hit = cache.verifiedJar(sha);
            if (hit.isPresent()) {
                pass.hideOriginal(jar);
                servedJars.put(sha, hit.get());
                servedSources.put(sha, jar);
                served.add(new InPlaceStatus.Served(absolute(jar.path()), jar.location(), sha,
                        hit.get().toString()));
                return;
            }
            boolean holdBack = MODS.equals(jar.location()) && !jar.loaderWouldLoad();
            Optional<CacheEntry> entry = cache.entry(sha);
            if (entry.isPresent() && !entry.get().allowsAttempt()
                    && entry.get().status() != CacheEntry.Status.OK) {
                if (holdBack) pass.hideOriginal(jar);
                failed.add(source(candidate, holdBack, entry.get().failure()));
                pass.warn("Not translating " + jar.fileName() + " again: "
                        + Objects.requireNonNullElse(entry.get().failure(), "it failed before")
                        + ". Retromod tries again after an update or when the file changes.");
                return;
            }
            if (cache.state() == TranslatedModCache.State.READY) {
                CacheEntry.Status before = entry.map(CacheEntry::status).orElse(null);
                int attempts = cache.markPending(candidate.source());
                if (before == CacheEntry.Status.STARTED
                        && attempts > InPlaceLaunch.MAX_TRANSLATION_ATTEMPTS) {
                    giveUpOnTranslation(candidate, holdBack);
                    return;
                }
                if (before == CacheEntry.Status.PENDING && !holdBack
                        && MODS.equals(jar.location())) {
                    // The entry point moves every pending source to a result, so one still
                    // pending means the loader stopped first, most likely on this jar for a
                    // reason the prediction missed.
                    holdBack = true;
                    pass.warn(jar.fileName() + " was waiting for translation, but the game "
                            + "stopped before Retromod could translate it. Retromod holds it "
                            + "back this launch so it can be translated.");
                }
                // A STARTED entry proves the entry point runs, so showing the original to the
                // loader would not help; the translation-attempt limit above ends that case.
                if (holdBack && attempts > MAX_HIDDEN_LAUNCHES
                        && before != CacheEntry.Status.STARTED) {
                    // Start the count over, so the next launch holds it back again.
                    cache.forget(sha);
                    holdBack = false;
                    pass.warn("Retromod held back " + jar.fileName() + " for "
                            + MAX_HIDDEN_LAUNCHES + " launches, but the game stopped before "
                            + "Retromod could translate it each time. The loader sees it in "
                            + "this launch so its own message names the problem. A mod that "
                            + "requires it can cause this: remove that mod for one launch.");
                }
            }
            if (holdBack) pass.hideOriginal(jar);
            pending.add(source(candidate, holdBack, null));
        }

        /**
         * The game stopped while Retromod translated this jar on too many launches. Without
         * this the translation would run, and crash, on every launch.
         */
        private void giveUpOnTranslation(Candidate candidate, boolean holdBack) {
            Classified jar = candidate.jar();
            String reason = "The game stopped while Retromod translated this file, after "
                    + InPlaceLaunch.MAX_TRANSLATION_ATTEMPTS + " launches without a result. "
                    + "The crash report or log of the last launch names the reason";
            cache.recordFailure(candidate.source(), reason);
            if (holdBack) pass.hideOriginal(jar);
            failed.add(source(candidate, holdBack, reason));
            pass.warn("Not translating " + jar.fileName() + " again: " + reason
                    + ". Retromod tries again after an update or when the file changes.");
        }

        private InPlaceStatus.Source source(Candidate candidate, boolean hidden, String reason) {
            Classified jar = candidate.jar();
            return new InPlaceStatus.Source(absolute(jar.path()), jar.location(),
                    candidate.source().sha256(), hidden, reason);
        }

        /**
         * Forge and NeoForge 1.20.1 load every jar in the folder they are given, so the
         * served copies go into a folder rebuilt from empty. If it cannot be built, nothing is
         * served, and only originals the loader would refuse stay held back.
         */
        private Path buildServeFolder() {
            // Rebuilt even when nothing is served, so no copy from an earlier launch lingers.
            Optional<TranslatedModCache.ServeFolder> folder =
                    cache.prepareServeFolder(servedJars.keySet());
            Set<String> placed = folder.map(f -> f.files().keySet()).orElse(Set.of());
            for (Map.Entry<String, Classified> entry : servedSources.entrySet()) {
                if (placed.contains(entry.getKey())) continue;
                Classified jar = entry.getValue();
                served.removeIf(row -> row.sha256().equals(entry.getKey()));
                if (jar.loaderWouldLoad()) {
                    pass.hide.remove(jar.path());
                    pass.hiddenNames.remove(absolute(jar.path()));
                }
                pass.warn("Could not serve the translation of " + jar.fileName()
                        + " this launch.");
            }
            return folder.filter(f -> !f.files().isEmpty())
                    .map(TranslatedModCache.ServeFolder::directory).orElse(null);
        }

        /**
         * A held-back mod that a loaded mod requires stops the loader before Retromod's
         * entry point runs, so it never gets translated. Say so, and what to do.
         */
        private void warnAboutBlockedDependents() {
            Map<String, String> waiting = new HashMap<>();
            for (InPlaceStatus.Source row : pending) {
                if (!row.hidden()) continue;
                for (Classified jar : mods) {
                    if (absolute(jar.path()).equals(row.source())) {
                        for (String id : jar.modIds()) waiting.put(id, jar.fileName());
                    }
                }
            }
            if (waiting.isEmpty()) return;
            for (Classified jar : mods) {
                if (jar.kind() != Kind.CURRENT && jar.kind() != Kind.TRANSLATED) continue;
                for (ForgeModMetadata.Dependency dependency : jar.effective()
                        .map(ForgeModMetadata::dependencies).orElse(List.of())) {
                    String blocked = waiting.get(dependency.modId());
                    if (blocked == null
                            || dependency.requirement() != ForgeModMetadata.Requirement.REQUIRED) {
                        continue;
                    }
                    pass.warn(jar.fileName() + " requires " + blocked + ", which Retromod "
                            + "translates on its first launch. The loader may stop before "
                            + "that happens. If it does, move " + jar.fileName() + " out of "
                            + "mods/ for one launch, then put it back.");
                }
            }
        }
    }

    static String absolute(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    /**
     * Orders mod versions such as {@code 1.2.10} and {@code 1.2.9-beta} by their number
     * groups, then by text. An unknown version sorts lowest.
     */
    static int compareModVersions(String left, String right) {
        String[] a = left == null ? new String[0] : left.split("[^0-9A-Za-z]+");
        String[] b = right == null ? new String[0] : right.split("[^0-9A-Za-z]+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "";
            String y = i < b.length ? b[i] : "";
            int result;
            if (x.matches("\\d{1,18}") && y.matches("\\d{1,18}")) {
                result = Long.compare(Long.parseLong(x), Long.parseLong(y));
            } else if (x.isEmpty() || y.isEmpty()) {
                result = x.isEmpty() ? (y.isEmpty() ? 0 : -1) : 1;
            } else {
                result = x.compareTo(y);
            }
            if (result != 0) return result;
        }
        return 0;
    }
}
