/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.ArchiveCheck;
import com.retromod.util.ArchiveLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Decides whether Retromod may translate a mod jar, from how much it unpacks to and its archive check. */
public final class ArchiveAdmission {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Archive");

    /** Config switch that lets every jar over 25 GB through once it passes the archive check. */
    public static final String ALLOW_KEY = "allow_huge_jars";
    /** Config list of SHA-256 hashes of jars over 25 GB that passed the check and were approved. */
    public static final String APPROVED_KEY = "approved_huge_jars";
    /** Config list of SHA-256 hashes of jars approved after their archive check did not pass. */
    public static final String APPROVED_UNVERIFIED_KEY = "approved_unverified_jars";
    /** One-run override of {@link #ALLOW_KEY}, for example on a server start command. */
    public static final String SYSTEM_PROPERTY = "retromod.allowHugeJars";

    private static final String CONFIG_FILE = "config/retromod/config.json";

    /** Size class of a jar. */
    public enum Tier {
        /** Up to 1 GB: translated as before, with no archive check. */
        NORMAL,
        /** Over 1 GB and up to 25 GB: translated once the archive check passes. */
        VERIFIED,
        /** Over 25 GB: translated only with the player's approval. */
        HUGE
    }

    /** What happens to a jar. */
    public enum Verdict { ALLOW, HOLD, REFUSE }

    /**
     * One decision.
     *
     * @param unpackedBytes the unpacked size the jar declares, or its file size when larger
     * @param check         the archive check, or null for a {@link Tier#NORMAL} jar
     * @param sha256        the jar's hash, known for every held jar
     * @param message       what the player reads for a held or refused jar, otherwise null
     */
    public record Decision(Path jar, Tier tier, Verdict verdict, long unpackedBytes, long fileBytes,
            ArchiveCheck.Result check, String sha256, ArchiveLimits.Allowance allowance,
            String message) {

        /** The jar's file name. */
        public String name() {
            return String.valueOf(jar.getFileName());
        }

        /** True when the archive check ran and did not pass, so the jar may be a zip bomb. */
        public boolean unverified() {
            return check != null && !check.passed();
        }
    }

    /** Thrown for a jar that waits for the player's approval. */
    public static final class HeldException extends IOException {
        private final transient Decision decision;

        HeldException(Decision decision) {
            super(decision.message());
            this.decision = decision;
        }

        public Decision decision() {
            return decision;
        }
    }

    /** Thrown for a jar a path cannot take at its size. */
    public static final class RefusedException extends IOException {
        private final transient Decision decision;

        RefusedException(Decision decision) {
            super(decision.message());
            this.decision = decision;
        }

        public Decision decision() {
            return decision;
        }
    }

    /** The size limits, so tests can decide on small files. */
    record Limits(long normalTotalBytes, long verifiedTotalBytes, long outputSlackBytes) {}

    private static final Limits DEFAULT_LIMITS = new Limits(ArchiveLimits.NORMAL_TOTAL_BYTES,
            ArchiveLimits.VERIFIED_TOTAL_BYTES, ArchiveLimits.OUTPUT_SLACK_BYTES);
    private static volatile Limits limits = DEFAULT_LIMITS;

    // Null means the working directory, which is the game directory on every loader.
    private static volatile Path gameDir;
    private static volatile boolean allowHugeForRun;
    private static volatile boolean allowUnverifiedForRun;

    // Checks and hashes are kept per file version, so a second decision on one jar costs nothing.
    private static final Map<String, Known> KNOWN = new ConcurrentHashMap<>();
    private static final Map<String, Decision> HELD = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private static final java.util.Set<String> LOGGED_ALLOWED = ConcurrentHashMap.newKeySet();

    private static final class Known {
        volatile ArchiveCheck.Totals totals;
        volatile ArchiveCheck.Result structure;
        volatile ArchiveCheck.Result content;
        volatile String sha256;
    }

    private ArchiveAdmission() {}

    /** Reads approvals from {@code dir}'s config instead of the working directory's. */
    public static void useGameDirectory(Path dir) {
        gameDir = dir == null ? null : dir.toAbsolutePath().normalize();
    }

    /** Lets every jar over 25 GB that passes the check through in this process ({@code --allow-huge-jars}). */
    public static void allowHugeJarsForThisRun() {
        allowHugeForRun = true;
    }

    /** Lets jars whose archive check failed through in this process ({@code --allow-unverified-jars}). */
    public static void allowUnverifiedJarsForThisRun() {
        allowUnverifiedForRun = true;
    }

    /** The config file approvals are read from and written to. */
    public static Path configPath() {
        Path dir = gameDir;
        return dir == null ? RetromodConfig.CONFIG_PATH : RetromodConfig.configPath(dir);
    }

    /**
     * Decides for {@code jar} and applies its allowance on this thread until the scope closes.
     * A held or refused jar throws instead, with a message that says what to do.
     */
    public static ArchiveLimits.Scope admit(Path jar) throws IOException {
        return ArchiveLimits.enter(require(jar).allowance());
    }

    /** Decides for {@code jar}, throwing for a held jar; an allowed one is returned. */
    public static Decision require(Path jar) throws IOException {
        Decision decision = decide(jar);
        if (decision.verdict() == Verdict.HOLD) {
            if (HELD.put(key(jar), decision) == null) LOGGER.warn(decision.message());
            throw new HeldException(decision);
        }
        if (decision.tier() != Tier.NORMAL && LOGGED_ALLOWED.add(key(jar))) {
            if (decision.unverified()) {
                LOGGER.warn("Translating {} as approved, although its archive check did not pass: {}.",
                        decision.name(), decision.check().problem());
            } else {
                LOGGER.info("{} unpacks to {} and passed the archive check; translating it.",
                        decision.name(), ArchiveCheck.humanBytes(decision.unpackedBytes()));
            }
        }
        return decision;
    }

    /**
     * Like {@link #admit}, for a path that holds a whole jar in memory: it refuses any jar over
     * {@code maxBytes} or the normal limit, before any check or hash, and says what to use instead.
     */
    public static ArchiveLimits.Scope admitInMemory(Path jar, long maxBytes, String pathName, String instead)
            throws IOException {
        long unpacked = unpackedSize(jar);
        long limit = Math.min(maxBytes, limits.normalTotalBytes());
        if (unpacked > limit) {
            Decision refused = new Decision(jar, unpacked > limits.verifiedTotalBytes() ? Tier.HUGE
                    : unpacked > limits.normalTotalBytes() ? Tier.VERIFIED : Tier.NORMAL, Verdict.REFUSE,
                    unpacked, Files.size(jar), null, null, null,
                    pathName + " reads the whole jar into memory, so it takes jars up to "
                    + ArchiveCheck.humanBytes(limit) + ". " + jar.getFileName()
                    + " unpacks to " + ArchiveCheck.humanBytes(unpacked) + ". " + instead);
            LOGGER.warn(refused.message());
            throw new RefusedException(refused);
        }
        return admit(jar);
    }

    /** The unpacked size the jar declares, or its file size when larger or when it cannot be read. */
    private static long unpackedSize(Path jar) throws IOException {
        long fileBytes = Files.size(jar);
        try {
            return Math.max(totals(jar, known(jar)).unpackedBytes(), fileBytes);
        } catch (IOException unreadable) {
            return fileBytes;
        }
    }

    /** Decides for {@code jar} without throwing. */
    public static Decision decide(Path jar) throws IOException {
        return decide(jar, limits, Approvals.current());
    }

    static Decision decide(Path jar, Limits limits, Approvals approvals) throws IOException {
        Objects.requireNonNull(jar, "jar");
        long fileBytes = Files.size(jar);
        Known known = known(jar);
        ArchiveCheck.Totals totals;
        try {
            totals = totals(jar, known);
        } catch (IOException unreadable) {
            // A jar the reader cannot open fails in the transformer, with that transformer's message.
            return allow(jar, Tier.NORMAL, fileBytes, fileBytes, null, null, limits.normalTotalBytes());
        }
        long unpacked = Math.max(totals.unpackedBytes(), fileBytes);
        if (unpacked <= limits.normalTotalBytes()) {
            return allow(jar, Tier.NORMAL, unpacked, fileBytes, null, null, limits.normalTotalBytes());
        }
        boolean huge = unpacked > limits.verifiedTotalBytes();
        Tier tier = huge ? Tier.HUGE : Tier.VERIFIED;
        // The declared size plus room for the files a translation adds, so no jar unpacks past what it declared.
        long declaredWithSlack = saturatedAdd(unpacked, limits.outputSlackBytes());
        long allowance = huge ? declaredWithSlack : Math.min(limits.verifiedTotalBytes(), declaredWithSlack);

        // A jar over 25 GB that nothing approves yet gets only the header check, which is fast.
        if (huge && !approvals.allowsHuge() && !approvals.allowsUnverified()
                && !approvals.approvesAny(sha256(jar, known))) {
            return hold(jar, tier, unpacked, fileBytes, structure(jar, known), sha256(jar, known), limits);
        }
        ArchiveCheck.Result content = content(jar, known);
        if (content.passed()) {
            return allow(jar, tier, unpacked, fileBytes, content, known.sha256, allowance);
        }
        // A failed check is never covered by allow_huge_jars: only a per-jar approval or the CLI flag.
        if (approvals.allowsUnverified() || approvals.approvesUnverified(sha256(jar, known))) {
            return allow(jar, tier, unpacked, fileBytes, content, known.sha256, allowance);
        }
        return hold(jar, tier, unpacked, fileBytes, content, sha256(jar, known), limits);
    }

    private static Decision allow(Path jar, Tier tier, long unpacked, long fileBytes,
            ArchiveCheck.Result check, String sha256, long maxTotalBytes) {
        ArchiveLimits.Allowance allowance = new ArchiveLimits.Allowance(maxTotalBytes,
                ArchiveLimits.streamsResourcesFor(unpacked));
        return new Decision(jar, tier, Verdict.ALLOW, unpacked, fileBytes, check, sha256, allowance, null);
    }

    private static Decision hold(Path jar, Tier tier, long unpacked, long fileBytes,
            ArchiveCheck.Result check, String sha256, Limits limits) {
        String message = check.passed()
                ? "Retromod held back " + jar.getFileName() + ": it unpacks to "
                        + ArchiveCheck.humanBytes(unpacked) + ", more than the "
                        + ArchiveCheck.humanBytes(limits.verifiedTotalBytes())
                        + " Retromod translates without asking. Its headers passed the archive check ("
                        + check.entries() + " entries). To translate it, choose Allow on the prompt on "
                        + "the title screen, set " + ALLOW_KEY + " to true in " + CONFIG_FILE
                        + ", or add \"" + sha256 + "\" to " + APPROVED_KEY + " there, then restart. "
                        + "The CLI takes --allow-huge-jars."
                : "Retromod held back " + jar.getFileName() + ": it unpacks to "
                        + ArchiveCheck.humanBytes(unpacked) + " and its archive check did not pass: "
                        + check.problem() + ". It may be a zip bomb. Allow it only if you trust where "
                        + "it came from: choose Allow on the prompt on the title screen, or add \""
                        + sha256 + "\" to " + APPROVED_UNVERIFIED_KEY + " in " + CONFIG_FILE
                        + ", then restart. " + ALLOW_KEY + " does not cover a jar that failed the "
                        + "check. The CLI takes --allow-unverified-jars.";
        return new Decision(jar, tier, Verdict.HOLD, unpacked, fileBytes, check, sha256, null, message);
    }

    // ---- Approvals ----

    /**
     * Where approvals come from.
     *
     * @param allowsHuge         {@code allow_huge_jars}, its system property, or {@code --allow-huge-jars}
     * @param allowsUnverified   {@code --allow-unverified-jars}, which only the CLI sets
     * @param approvedHuge       per-jar approvals of jars that passed the check
     * @param approvedUnverified per-jar approvals given after the check did not pass
     */
    record Approvals(boolean allowsHuge, boolean allowsUnverified, Set<String> approvedHuge,
            Set<String> approvedUnverified) {

        /** Whether a per-jar approval covers a jar that passed the check. */
        boolean approvesAny(String sha256) {
            return sha256 != null && (approvedHuge.contains(sha256) || approvedUnverified.contains(sha256));
        }

        /** Whether a per-jar approval covers a jar whose check did not pass. */
        boolean approvesUnverified(String sha256) {
            return sha256 != null && approvedUnverified.contains(sha256);
        }

        static Approvals current() {
            Path config = configPath();
            String override = System.getProperty(SYSTEM_PROPERTY);
            boolean allowHuge = allowHugeForRun
                    || (override != null && override.trim().equalsIgnoreCase("true"))
                    || RetromodConfig.findBoolean(config, ALLOW_KEY).orElse(false);
            return new Approvals(allowHuge, allowUnverifiedForRun,
                    RetromodConfig.findStrings(config, APPROVED_KEY),
                    RetromodConfig.findStrings(config, APPROVED_UNVERIFIED_KEY));
        }
    }

    /**
     * Records the player's approval of a held jar in the config, so the next launch translates it.
     * A jar that failed the check goes into its own list, which {@code allow_huge_jars} never covers.
     */
    public static void approve(Decision decision) throws IOException {
        Objects.requireNonNull(decision, "decision");
        if (decision.sha256() == null) throw new IOException("The jar's hash is unknown: " + decision.name());
        String list = decision.unverified() ? APPROVED_UNVERIFIED_KEY : APPROVED_KEY;
        RetromodConfig.addString(configPath(), list, decision.sha256().toLowerCase(Locale.ROOT));
        LOGGER.info("Approved {} for translation on the next launch", decision.name());
    }

    /** Jars held for approval this launch, in the order they were found. */
    public static List<Decision> heldThisLaunch() {
        synchronized (HELD) {
            return List.copyOf(new ArrayList<>(HELD.values()));
        }
    }

    /** Records a hash computed elsewhere, so a large jar is not hashed twice. */
    public static void rememberHash(Path jar, String sha256) {
        if (jar == null || sha256 == null) return;
        try {
            known(jar).sha256 = sha256.toLowerCase(Locale.ROOT);
        } catch (IOException ignored) {
            // The decision computes the hash itself.
        }
    }

    // ---- Cached work ----

    private static Known known(Path jar) throws IOException {
        return KNOWN.computeIfAbsent(fingerprint(jar), ignored -> new Known());
    }

    private static String fingerprint(Path jar) throws IOException {
        var attributes = Files.readAttributes(jar, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        return key(jar) + '|' + attributes.size() + '|' + attributes.lastModifiedTime().toMillis();
    }

    private static String key(Path jar) {
        return jar.toAbsolutePath().normalize().toString();
    }

    private static ArchiveCheck.Totals totals(Path jar, Known known) throws IOException {
        if (known.totals == null) known.totals = ArchiveCheck.readTotals(jar);
        return known.totals;
    }

    private static ArchiveCheck.Result structure(Path jar, Known known) throws IOException {
        if (known.structure == null) known.structure = ArchiveCheck.checkStructure(jar);
        return known.structure;
    }

    private static ArchiveCheck.Result content(Path jar, Known known) throws IOException {
        if (known.content == null) {
            LOGGER.info("Checking {} before translation; a jar this large takes a while.", jar.getFileName());
            known.content = ArchiveCheck.checkContent(jar);
        }
        return known.content;
    }

    private static String sha256(Path jar, Known known) throws IOException {
        if (known.sha256 == null) {
            LOGGER.info("Hashing {} to look up its approval.", jar.getFileName());
            known.sha256 = com.retromod.inplace.TranslatedModCache.sha256(jar);
        }
        return known.sha256;
    }

    // ---- Test seams ----

    /** Test seam: replaces the 1 GB and 25 GB limits so small files take every path. */
    public static void setLimitsForTesting(long normalTotalBytes, long verifiedTotalBytes) {
        setLimitsForTesting(normalTotalBytes, verifiedTotalBytes, ArchiveLimits.OUTPUT_SLACK_BYTES);
    }

    /** Test seam: also replaces the room a translation gets beyond a checked jar's declared size. */
    public static void setLimitsForTesting(long normalTotalBytes, long verifiedTotalBytes, long outputSlackBytes) {
        limits = new Limits(normalTotalBytes, verifiedTotalBytes, outputSlackBytes);
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /** Test seam: restores the limits and forgets every setting, check, and held jar. */
    public static void resetForTesting() {
        limits = DEFAULT_LIMITS;
        gameDir = null;
        allowHugeForRun = false;
        allowUnverifiedForRun = false;
        KNOWN.clear();
        HELD.clear();
        LOGGED_ALLOWED.clear();
    }
}
