/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.coverage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Corpus usage reaches the coverage report through every call shape, subtypes included. */
class LoaderApiCoverageUsageTest {

    static final String TRANSFER = "net/fabricmc/fabric/api/transfer/v1/storage/TransferVariant";
    static final String FLUID = "net/fabricmc/fabric/api/transfer/v1/fluid/FluidVariant";
    static final String ITEM = "net/fabricmc/fabric/api/transfer/v1/item/ItemVariant";
    static final String GET_NBT = "()Lnet/minecraft/class_2487;";

    @TempDir
    Path dir;

    @Test
    @DisplayName("Calls through a subtype count toward the row of the declaring owner")
    void callSiteUsageIsCredited() throws Exception {
        List<LoaderApiCoverage.StatusRow> rows = LoaderApiCoverage.applyUsage(
                LoaderApiCoverage.readStatus(statusTable()), usageTable());
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).oldMods(), "no mod names TransferVariant itself");
        assertEquals(7, rows.get(0).callSiteMods(),
                "6 mods call FluidVariant.getNbt and 1 calls ItemVariant.getNbt");
    }

    @Test
    @DisplayName("The report shows mods that call through a subtype next to direct calls")
    void reportShowsCallSiteMods() throws Exception {
        List<LoaderApiCoverage.StatusRow> rows = LoaderApiCoverage.applyUsage(
                LoaderApiCoverage.readStatus(statusTable()), usageTable());
        LoaderApiCoverage.Report report = LoaderApiCoverage.generate(rows, List.of("26.3"));
        LoaderApiCoverage.write(report, dir.resolve("out"));

        List<String> tsv = Files.readAllLines(dir.resolve("out/fabric-26x.tsv"));
        List<String> header = List.of(tsv.get(0).split("\t", -1));
        List<String> row = List.of(tsv.get(1).split("\t", -1));
        assertTrue(header.contains("call_site_mods"), "the TSV needs a call_site_mods column: " + header);
        assertEquals("7", row.get(header.indexOf("call_site_mods")));
        String json = Files.readString(dir.resolve("out/fabric-26x.json"));
        assertTrue(json.contains("\"call_site_mods\": 7"), "the JSON rows need call_site_mods too");
    }

    @Test
    @DisplayName("Usage keys that match no host member and no status row are listed")
    void unmatchedUsageIsListed() throws Exception {
        String gone = "M net/fabricmc/fabric/api/fixture/v1/Gone#gone()V";
        Map<String, Integer> usage = new TreeMap<>(LoaderApiCoverage.readUsage(usageTable()));
        usage.put(gone, 3);
        usage.put("M net/fabricmc/fabric/api/event/Event#register(Ljava/lang/Object;)V", 127);
        usage.put("X net/minecraft/class_310#method_1574", 4);
        List<LoaderApiCoverage.StatusRow> rows = LoaderApiCoverage.applyUsage(
                LoaderApiCoverage.readStatus(statusTable()), usage);

        LoaderApiCoverage.Report report = LoaderApiCoverage.generate(rows, List.of("26.3"), usage);
        assertEquals(List.of(gone + "\t3"), report.unmatchedUsage().get("26.3"),
                "the subtype calls belong to the TransferVariant row, Event.register is on the host, "
                        + "and a mixin selector is not an API member");
        LoaderApiCoverage.write(report, dir.resolve("out"));
        assertTrue(Files.readString(dir.resolve("out/fabric-26x-unmatched.tsv")).contains(gone));
    }

    /** One gap row declared on TransferVariant, reached through FluidVariant and ItemVariant. */
    Path statusTable() throws Exception {
        Path status = dir.resolve("status.tsv");
        Files.writeString(status, String.join("\t", "module", "owner", "kind", "name",
                "old_descriptor", "call_site_owners", "old_mods", "call_site_mods", "mark_26.1",
                "mark_26.2", "mark_26.3", "reason", "batch", "evidence") + "\n"
                + String.join("\t", "fabric-transfer-api-v1", TRANSFER, "M", "getNbt", GET_NBT,
                FLUID + "," + ITEM, "0", "0", "missing", "missing", "missing", "-", "T1", "-") + "\n");
        return status;
    }

    /** Corpus counts in the {@code ranked-members.tsv} shape, keyed on the call-site owner. */
    Path usageTable() throws Exception {
        Path usage = dir.resolve("usage.tsv");
        Files.writeString(usage, String.join("\t", "rank", "old_mods", "kind", "on_26.3", "key") + "\n"
                + String.join("\t", "1", "6", "method", "maybe-inherited",
                "M " + FLUID + "#getNbt" + GET_NBT) + "\n"
                + String.join("\t", "2", "1", "method", "maybe-inherited",
                "M " + ITEM + "#getNbt" + GET_NBT) + "\n");
        return usage;
    }
}
