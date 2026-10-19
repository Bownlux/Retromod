/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.coverage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.loaderapi.fabric.v26.HostSurface;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The coverage report never moves backwards. */
class LoaderApiCoverageBaselineTest {

    private static final String BASELINE = "/loaderapi/fabric-26x-baseline.json";
    private static LoaderApiCoverage.Report report;
    private static List<LoaderApiCoverage.StatusRow> rows;

    @BeforeAll
    static void generate() throws Exception {
        rows = LoaderApiCoverage.readStatus(Path.of("docs-dev/loader-api/fabric-26x-status.tsv"));
        List<String> hosts = new ArrayList<>(LoaderApiCoverage.DEFAULT_HOSTS);
        hosts.add(newestIndexedHost());
        report = LoaderApiCoverage.generate(rows, hosts);
    }

    @Test
    @DisplayName("Rows marked done in the baseline are still done, and no host's count dropped")
    void doneRowsStayDone() throws Exception {
        JsonObject done;
        try (InputStream in = getClass().getResourceAsStream(BASELINE)) {
            done = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("done");
        }
        for (Map.Entry<String, com.google.gson.JsonElement> host : done.entrySet()) {
            Set<String> expected = new TreeSet<>();
            host.getValue().getAsJsonArray().forEach(key -> expected.add(key.getAsString()));
            Set<String> actual = report.doneKeys(host.getKey());
            Set<String> lost = new TreeSet<>(expected);
            lost.removeAll(actual);
            assertTrue(lost.isEmpty(), "rows no longer done on " + host.getKey() + ": " + lost);
            assertTrue(actual.size() >= expected.size(), "done count dropped on " + host.getKey());
        }
    }

    @Test
    @DisplayName("Every runtime target links on every indexed host surface")
    void runtimeTargetsLinkEverywhere() {
        List<String> unlinked = report.results().stream()
                .filter(result -> "unlinked".equals(result.status()))
                .map(result -> result.host() + " " + result.row().key() + " -> " + result.target())
                .toList();
        assertEquals(List.of(), unlinked);
    }

    @Test
    @DisplayName("A row that works on 26.1 or 26.3 also works on the newest indexed host")
    void fixesReachTheNewestHost() {
        String newest = newestIndexedHost();
        Set<String> newestWorking = working(newest);
        for (String host : List.of("26.1", "26.3")) {
            Set<String> missing = new TreeSet<>(working(host));
            missing.removeAll(newestWorking);
            assertTrue(missing.isEmpty(), "rows that work on " + host + " but not on " + newest
                    + ": " + missing);
        }
    }

    @Test
    @DisplayName("The status table holds old Fabric API rows only")
    void statusTableIsClean() {
        for (LoaderApiCoverage.StatusRow row : rows) {
            assertTrue(row.oldDescriptor() == null || !row.oldDescriptor().contains("com/retromod/"),
                    "a row taken from Retromod's own output: " + row.key());
        }
    }

    private static Set<String> working(String host) {
        Set<String> keys = new TreeSet<>();
        for (LoaderApiCoverage.Result result : report.forHost(host)) {
            if ("done".equals(result.status()) || "linked".equals(result.status())) {
                keys.add(result.row().key());
            }
        }
        return keys;
    }

    private static String newestIndexedHost() {
        return HostSurface.INDEXED_LINES.get(HostSurface.INDEXED_LINES.size() - 1);
    }
}
