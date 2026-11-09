/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 *
 * Machine-readable capture of fuzzy-resolver outcomes, for offline bridge authoring.
 */
package com.retromod.core;

import com.retromod.util.RetromodLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structured sink for {@link FuzzyMethodResolver} outcomes. The resolver's 50-84 "possible match"
 * band is exactly the data needed to author explicit shim bridges from a real failing mod, so it
 * lands here instead of in the launch log, where it only logs at DEBUG. This writes one TSV row
 * per distinct unresolved reference to {@code config/retromod/fuzzy-report.tsv} (created lazily
 * on the first record, truncated once per JVM run so each launch yields a fresh report), and
 * names that file in one INFO line per run.
 *
 * <p>Rows: {@code kind  band  score  owner  name  descriptor  candidateOwner  candidateName
 * candidateDescriptor}. Bands: {@code NEAR} (50-84, not applied: the bridge-authoring backlog),
 * {@code AUTO} (85+, applied; listed so an auto-redirect can be reviewed or pinned as an
 * explicit shim), {@code SUPPRESSED} (85+ but type-incompatible, needs a hand-written adapter
 * bridge, not a rename).
 *
 * <p>Failures are swallowed after one WARN: reporting must never break a transform.
 */
public final class FuzzyMatchReport {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Fuzzy");

    public static final String BAND_AUTO = "AUTO";
    public static final String BAND_NEAR = "NEAR";
    public static final String BAND_SUPPRESSED = "SUPPRESSED";

    private static volatile Path outputFile = Path.of("config", "retromod", "fuzzy-report.tsv");
    private static final Set<String> written = ConcurrentHashMap.newKeySet();
    private static volatile boolean headerWritten = false;
    private static volatile boolean broken = false;
    private static volatile boolean announced = false;

    private FuzzyMatchReport() {}

    /** Redirect the report (CLI points it next to its output dir). Resets per-run state. */
    public static void setOutputFile(Path file) {
        outputFile = file;
        written.clear();
        headerWritten = false;
        broken = false;
        announced = false;
    }

    /** The current report path (for CLI summaries: "wrote N fuzzy rows to ..."). */
    public static Path getOutputFile() {
        return outputFile;
    }

    /** Distinct outcomes seen so far this run, written or not. */
    public static int recordedCount() {
        return written.size();
    }

    /**
     * Record one resolver outcome. Deduplicates on the unresolved reference plus band, so a
     * reference hit from many call sites lands once.
     *
     * @param kind "METHOD" or "FIELD"
     * @param band {@link #BAND_AUTO}, {@link #BAND_NEAR} or {@link #BAND_SUPPRESSED}
     * @return true the first time this run sees the outcome, so the caller logs it only once
     */
    public static boolean record(String kind, String band, int score,
                                 String owner, String name, String descriptor,
                                 String candOwner, String candName, String candDescriptor) {
        // Deduplicate before the broken check, so a report that cannot be written still lets
        // the caller log each outcome once.
        String key = kind + "|" + band + "|" + owner + "." + name + descriptor;
        if (!written.add(key)) return false;
        if (broken) return true;
        String row = kind + "\t" + band + "\t" + score + "\t"
                + owner + "\t" + name + "\t" + descriptor + "\t"
                + candOwner + "\t" + candName + "\t" + candDescriptor + "\n";
        synchronized (FuzzyMatchReport.class) {
            try {
                if (!headerWritten) {
                    if (outputFile.getParent() != null) {
                        Files.createDirectories(outputFile.getParent());
                    }
                    Files.writeString(outputFile,
                            "# Retromod fuzzy-resolver report: one row per distinct unresolved "
                            + "reference; NEAR rows are shim-bridge candidates\n"
                            + "# kind\tband\tscore\towner\tname\tdescriptor\tcandidateOwner\t"
                            + "candidateName\tcandidateDescriptor\n",
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    headerWritten = true;
                }
                Files.writeString(outputFile, row, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                // Name the report once per run, as given, so no home path lands in shared logs.
                if (!announced) {
                    announced = true;
                    LOGGER.debug(RetromodLog.DETAIL, "Retromod lists fuzzy matches, applied and not applied, in {}",
                            outputFile);
                }
            } catch (IOException e) {
                broken = true;
                LOGGER.warn("Fuzzy report disabled, cannot write {}: {}", outputFile, e.toString());
            }
        }
        return true;
    }
}
