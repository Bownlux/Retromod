/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RemovedItemBaseBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Holds the line between a class MOVE and a class DELETION in the vanilla tables.
 *
 * <p>A move is a rename and a redirect expresses it exactly. A deletion has no destination, and
 * three separate crashes came from rows that named the nearest surviving class anyway: `RecordItem`
 * onto the final record `JukeboxSong` (#260), `EnchantedBookItem` onto the constructor-less holder
 * `Items`, and `AgeableListModel` onto the final record `BabyModelTransform`. Each turned a missing
 * class into an inheritance or linkage error further from the cause.
 *
 * <p>Shape cannot be checked here, because the test suite has no Minecraft jar. What is checked is
 * the rule those rows broke: a base Retromod rebases must not also be renamed by a table, or the
 * rename fires first and the rebase never sees it.
 */
class DeletedBaseClassTableTest {

    private static final String TABLE = "/mojang-class-moves-26.1.tsv";

    /**
     * Classes proven removed against the 26.1.2 and 26.2 jars, so no destination can be correct.
     *
     * <p>Each of these once had a row naming the nearest surviving class. A deleted item base was
     * pointed at a final record and at a constructor-less holder, a callback interface at a class
     * with no public constructor, and a holder of six int constants at an enum whose constants
     * share their names and not their type. Every one turned a missing class into a linkage or
     * inheritance error further from the cause.
     *
     * <p>Being deleted is not the same as being renamed onto the wrong class. A horse coat variant
     * really did become the equine package's enum, so that row was retargeted rather than removed
     * and is deliberately absent from this list. The schedule keyframe is here because it looked
     * like the same case and is not: neither surviving Keyframe carries its members, and the one
     * that reads closest belongs to an unrelated new timeline system.
     */
    private static final List<String> DELETED_BASES = List.of(
            "net/minecraft/world/item/RecordItem",
            "net/minecraft/world/item/EnchantedBookItem",
            "net/minecraft/client/model/AgeableListModel",
            "net/minecraft/network/chat/TranslatableComponent",
            "net/minecraft/client/renderer/FaceInfo$Constants",
            "net/minecraft/client/gui/components/Button$OnTooltip",
            "net/minecraft/client/CycleOption$OptionSetter",
            "net/minecraft/world/entity/schedule/Keyframe");

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    private static Map<String, String> classMoveTable() throws IOException {
        Map<String, String> rows = new LinkedHashMap<>();
        try (InputStream in = DeletedBaseClassTableTest.class.getResourceAsStream(TABLE)) {
            assertNotNull(in, "the class-move table must ship on the classpath");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String row = line.strip();
                if (row.isEmpty() || row.startsWith("#")) continue;
                String[] parts = row.split("\t");
                if (parts.length == 2) rows.put(parts[0], parts[1]);
            }
        }
        return rows;
    }

    @Test
    @DisplayName("a deleted base is never renamed onto another class")
    void deletedBasesAreNotClassMoves() throws Exception {
        Map<String, String> rows = classMoveTable();
        List<String> reintroduced = new ArrayList<>();
        for (String deleted : DELETED_BASES) {
            if (rows.containsKey(deleted)) {
                reintroduced.add(deleted + " -> " + rows.get(deleted));
            }
        }
        assertTrue(reintroduced.isEmpty(),
                "These classes were removed, not moved, so no destination is correct. Where mods "
                + "extend one, rebase the inheritance edge with registerGeneratedLegacyBase; "
                + "otherwise leave the name unmapped so the failure names the missing class: "
                + reintroduced);
    }

    @Test
    @DisplayName("a rebased base is not also renamed, or the rename would win")
    void rebasedBasesAreNotAlsoRenamed() throws Exception {
        Map<String, String> rows = classMoveTable();
        RemovedItemBaseBridge.register(transformer);

        // The class remapper runs ahead of the superclass rebase, so a row for the same name would
        // rewrite the extends slot before the rebase could claim it.
        for (String rebased : transformer.getSuperclassRebaseSources()) {
            assertFalse(rows.containsKey(rebased),
                    rebased + " is rebased onto a generated base and must not also be a class move; "
                    + "the rename runs first and the rebase would never fire");
        }
    }

    @Test
    @DisplayName("the deleted item bases are registered, not silently dropped")
    void bridgeStaysWired() {
        RemovedItemBaseBridge.register(transformer);
        for (String base : List.of("net/minecraft/world/item/RecordItem",
                "net/minecraft/world/item/EnchantedBookItem")) {
            assertTrue(transformer.getSuperclassRebaseSources().contains(base),
                    base + " lost its rebase, so a mod extending it fails at class load again");
        }
    }

    @Test
    @DisplayName("no source is mapped to two different destinations")
    void noConflictingSources() throws Exception {
        // The table is read into a map, so a source listed twice with different destinations keeps
        // only the last one and the other row is silently dead. Listing the same pair twice is
        // merely redundant and harms nothing, so only a genuine conflict fails.
        Map<String, String> seen = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        try (InputStream in = DeletedBaseClassTableTest.class.getResourceAsStream(TABLE)) {
            assertNotNull(in);
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String row = line.strip();
                if (row.isEmpty() || row.startsWith("#")) continue;
                String[] parts = row.split("\t");
                if (parts.length != 2) continue;
                String previous = seen.put(parts[0], parts[1]);
                if (previous != null && !previous.equals(parts[1])) {
                    conflicts.add(parts[0] + " -> " + previous + " and " + parts[1]);
                }
            }
        }
        assertTrue(conflicts.isEmpty(),
                "one destination wins and the other row never applies: " + conflicts);
    }
}
