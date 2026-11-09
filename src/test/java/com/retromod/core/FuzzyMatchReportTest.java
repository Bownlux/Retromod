/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiTestSupport;
import com.retromod.util.RetromodLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fuzzy report turns the resolver's near-miss band (50-84 "possible match") into a TSV that
 * pre-26.1 bridge authoring can consume offline. One row per distinct unresolved reference,
 * fresh file per run, and reporting failures must never break a transform.
 */
class FuzzyMatchReportTest {

    @TempDir
    Path tmp;

    @AfterEach
    void reset() {
        // park the report somewhere harmless so later tests (and the suite's cwd) stay clean
        FuzzyMatchReport.setOutputFile(tmp.resolve("parked.tsv"));
        RetromodLog.setDetailInGameLogForTesting(null);
    }

    @Test
    @DisplayName("rows land as TSV with a header, deduplicated per unresolved reference")
    void writesAndDedupes() throws IOException {
        Path out = tmp.resolve("fuzzy-report.tsv");
        FuzzyMatchReport.setOutputFile(out);

        FuzzyMatchReport.record("METHOD", FuzzyMatchReport.BAND_NEAR, 70,
                "net/minecraft/core/Registry", "getHolder",
                "(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;",
                "net/minecraft/core/Registry", "get",
                "(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;");
        // same reference from a second call site: must not produce a second row
        FuzzyMatchReport.record("METHOD", FuzzyMatchReport.BAND_NEAR, 70,
                "net/minecraft/core/Registry", "getHolder",
                "(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;",
                "net/minecraft/core/Registry", "get",
                "(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;");
        FuzzyMatchReport.record("FIELD", FuzzyMatchReport.BAND_SUPPRESSED, 90,
                "net/minecraft/world/entity/Entity", "level",
                "Lnet/minecraft/world/level/Level;",
                "net/minecraft/world/entity/Entity", "levelId", "I");

        assertEquals(2, FuzzyMatchReport.recordedCount());
        List<String> lines = Files.readAllLines(out);
        List<String> rows = lines.stream().filter(l -> !l.startsWith("#")).toList();
        assertEquals(2, rows.size(), "one row per distinct reference");
        String[] cols = rows.get(0).split("\t");
        assertEquals(9, cols.length, "kind band score owner name desc candOwner candName candDesc");
        assertEquals("METHOD", cols[0]);
        assertEquals(FuzzyMatchReport.BAND_NEAR, cols[1]);
        assertEquals("70", cols[2]);
        assertEquals("getHolder", cols[4]);
        assertEquals("get", cols[7]);
        assertTrue(lines.get(0).startsWith("#"), "header comment first");
    }

    @Test
    @DisplayName("setOutputFile starts a fresh report (truncate, state reset)")
    void freshPerRun() throws IOException {
        Path out = tmp.resolve("fuzzy-report.tsv");
        FuzzyMatchReport.setOutputFile(out);
        FuzzyMatchReport.record("METHOD", FuzzyMatchReport.BAND_NEAR, 60,
                "a/B", "old", "()V", "a/B", "next", "()V");
        FuzzyMatchReport.setOutputFile(out);
        FuzzyMatchReport.record("METHOD", FuzzyMatchReport.BAND_NEAR, 61,
                "c/D", "old2", "()V", "c/D", "next2", "()V");
        long rows = Files.readAllLines(out).stream().filter(l -> !l.startsWith("#")).count();
        assertEquals(1, rows, "old run's rows must not survive a reset");
    }

    @Test
    @DisplayName("an unwritable target disables reporting instead of throwing")
    void unwritableIsSilent() {
        // a path whose parent is a FILE, so createDirectories fails deterministically
        Path blocker = tmp.resolve("blocker");
        assertDoesNotThrow(() -> Files.writeString(blocker, "x"));
        FuzzyMatchReport.setOutputFile(blocker.resolve("sub").resolve("report.tsv"));
        assertDoesNotThrow(() -> FuzzyMatchReport.record("METHOD", FuzzyMatchReport.BAND_NEAR, 55,
                "a/B", "m", "()V", "a/B", "n", "()V"));
        // and stays disabled without further throws
        assertDoesNotThrow(() -> FuzzyMatchReport.record("METHOD", FuzzyMatchReport.BAND_NEAR, 55,
                "x/Y", "m2", "()V", "x/Y", "n2", "()V"));
    }

    @Test
    @DisplayName("a VALID inherited reference is never fuzzy-rewritten (Registry.get -> getOptional CCE)")
    void validInheritedReferenceIsUntouched() throws IOException {
        Path out = tmp.resolve("valid.tsv");
        FuzzyMatchReport.setOutputFile(out);

        // parent interface declares get(K)Optional; child declares a same-descriptor DECOY
        // getOptional. Pre-guard, the decoy (exact class, 40) tied the real inherited method
        // (related class, 25 + exact name, 30) at 85 and won on candidate order.
        Path jar = tmp.resolve("mc2.jar");
        String desc = "(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;";
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            org.objectweb.asm.ClassWriter parent = new org.objectweb.asm.ClassWriter(0);
            parent.visit(org.objectweb.asm.Opcodes.V17,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE
                            | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                    "net/minecraft/core/HolderGetter", null, "java/lang/Object", null);
            parent.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC
                    | org.objectweb.asm.Opcodes.ACC_ABSTRACT, "get", desc, null, null).visitEnd();
            parent.visitEnd();
            zos.putNextEntry(new java.util.zip.ZipEntry("net/minecraft/core/HolderGetter.class"));
            zos.write(parent.toByteArray());
            zos.closeEntry();

            org.objectweb.asm.ClassWriter child = new org.objectweb.asm.ClassWriter(0);
            child.visit(org.objectweb.asm.Opcodes.V17,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE
                            | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                    "net/minecraft/core/Registry", null, "java/lang/Object",
                    new String[]{"net/minecraft/core/HolderGetter"});
            child.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC
                    | org.objectweb.asm.Opcodes.ACC_ABSTRACT, "getOptional", desc, null, null).visitEnd();
            child.visitEnd();
            zos.putNextEntry(new java.util.zip.ZipEntry("net/minecraft/core/Registry.class"));
            zos.write(child.toByteArray());
            zos.closeEntry();
        }
        FuzzyMethodResolver r = new FuzzyMethodResolver();
        r.indexJar(jar);
        assertNull(r.resolveMethod("net/minecraft/core/Registry", "get", desc),
                "get(ResourceKey) resolves via HolderGetter; fuzzy must not rewrite it "
                + "(the getOptional decoy returns the VALUE, not the Holder: worldgen CCE)");
        assertFalse(Files.exists(out), "a valid reference must not even be reported");
    }

    @Test
    @DisplayName("resolver wiring: a 50-84 method match lands in the report as NEAR")
    void resolverEmitsNearBand() throws IOException {
        Path out = tmp.resolve("wired.tsv");
        FuzzyMatchReport.setOutputFile(out);

        FuzzyMethodResolver r = new FuzzyMethodResolver();
        r.indexJar(writeWidgetJar(tmp.resolve("mc.jar")));
        // exact class (40) + name miss with common prefix >= 4 (5) + param count exact (15)
        // + params match (15) = 75: inside the 50-84 NEAR band
        assertNull(r.resolveMethod(WIDGET, "recalcSize", "(I)V"));

        assertTrue(Files.exists(out), "NEAR match must be captured");
        String tsv = Files.readString(out);
        assertTrue(tsv.contains("METHOD\tNEAR\t"), "band column must be NEAR:\n" + tsv);
        assertTrue(tsv.contains("recalcSize"), "query name recorded");
        assertTrue(tsv.contains("recalculateBounds"), "candidate name recorded");
    }

    @Test
    @DisplayName("near misses stay out of the WARN log, and the log names the report once")
    void nearMissesStayOutOfTheWarnLog() throws Exception {
        Path out = tmp.resolve("quiet.tsv");
        FuzzyMatchReport.setOutputFile(out);
        FuzzyMethodResolver r = new FuzzyMethodResolver();
        r.indexJar(writeWidgetJar(tmp.resolve("mc.jar")));

        AtomicReferenceArray<Object> results = new AtomicReferenceArray<>(3);
        // The line naming the report is a detail line; this writes it where captureErr reads.
        RetromodLog.setDetailInGameLogForTesting(true);
        String log = LoaderApiTestSupport.captureErr(() -> {
            // Two workers reach the same method reference at once, as transform workers do.
            CountDownLatch go = new CountDownLatch(1);
            Thread[] workers = new Thread[2];
            for (int i = 0; i < workers.length; i++) {
                int slot = i;
                workers[i] = new Thread(() -> {
                    try {
                        go.await();
                        results.set(slot, r.resolveMethod(WIDGET, "recalcSize", "(I)V"));
                    } catch (InterruptedException e) {
                        results.set(slot, e);
                    }
                });
                workers[i].start();
            }
            go.countDown();
            for (Thread worker : workers) worker.join();
            // exact class (40) + shared prefix (5) + same type (30) = 75, a field near miss
            results.set(2, r.resolveField(WIDGET, "recalcWidth", "I"));
        });

        for (int i = 0; i < results.length(); i++) {
            assertNull(results.get(i), "a near miss is never applied (call " + i + ")");
        }
        long nearRows = Files.readAllLines(out).stream()
                .filter(l -> !l.startsWith("#") && l.contains("\tNEAR\t")).count();
        assertEquals(2, nearRows, "one NEAR row for the method and one for the field");
        assertFalse(log.contains("Possible"), "near misses must not reach the default log:\n" + log);
        assertFalse(log.contains("WARN Retromod-Fuzzy"), "no fuzzy WARN lines expected:\n" + log);
        assertEquals(1, log.split(java.util.regex.Pattern.quote(out.toString()), -1).length - 1,
                "the log must name the report exactly once:\n" + log);
    }

    @Test
    @DisplayName("record reports only the first sighting of an outcome, even when unwritable")
    void recordReportsFirstSightingOnly() throws IOException {
        FuzzyMatchReport.setOutputFile(tmp.resolve("first.tsv"));
        assertTrue(FuzzyMatchReport.record("METHOD", FuzzyMatchReport.BAND_NEAR, 70,
                "a/B", "m", "()V", "a/B", "n", "()V"), "first sighting");
        assertFalse(FuzzyMatchReport.record("METHOD", FuzzyMatchReport.BAND_NEAR, 70,
                "a/B", "m", "()V", "a/B", "n", "()V"), "same reference and band again");
        assertTrue(FuzzyMatchReport.record("METHOD", FuzzyMatchReport.BAND_AUTO, 90,
                "a/B", "m", "()V", "a/B", "n", "()V"), "same reference in a new band");

        // A report that cannot be written still answers first sightings, so DEBUG lines survive.
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "x");
        FuzzyMatchReport.setOutputFile(blocker.resolve("sub").resolve("report.tsv"));
        assertTrue(FuzzyMatchReport.record("FIELD", FuzzyMatchReport.BAND_NEAR, 60,
                "c/D", "f", "I", "c/D", "g", "I"), "first sighting while the write fails");
        assertTrue(FuzzyMatchReport.record("FIELD", FuzzyMatchReport.BAND_NEAR, 60,
                "c/D", "f2", "I", "c/D", "g2", "I"), "first sighting once the report is disabled");
        assertFalse(FuzzyMatchReport.record("FIELD", FuzzyMatchReport.BAND_NEAR, 60,
                "c/D", "f2", "I", "c/D", "g2", "I"), "a repeat stays quiet while disabled");
    }

    private static final String WIDGET = "net/minecraft/core/Widget";

    /** A one-class host jar whose members only half match the queries above. */
    private static Path writeWidgetJar(Path jar) throws IOException {
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            org.objectweb.asm.ClassWriter cw =
                    new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
            cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                    WIDGET, null, "java/lang/Object", null);
            // candidates differ enough from the queries to score in the NEAR band, not auto-apply
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "recalculateBounds", "(I)V",
                    null, null).visitEnd();
            cw.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "recalculatedHeight", "I",
                    null, null).visitEnd();
            cw.visitEnd();
            zos.putNextEntry(new java.util.zip.ZipEntry(WIDGET + ".class"));
            zos.write(cw.toByteArray());
            zos.closeEntry();
        }
        return jar;
    }
}
