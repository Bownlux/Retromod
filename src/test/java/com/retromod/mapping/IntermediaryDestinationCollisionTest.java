/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Watches which Mojang names more than one intermediary class maps to.
 *
 * <p>A collision is usually fine. Fabric renumbers intermediary ids across versions, so one logical
 * class can carry an old id and a new one, and both have to resolve to the same Mojang name or a mod
 * built against either version stops working.
 *
 * <p>A collision is a bug when the two ids are genuinely different classes. Minecraft sometimes
 * renames a class and then gives the old name to something new: the old {@code Cow} became
 * {@code AbstractCow} and a new leaf {@code Cow} appeared under it, {@code BushBlock} became
 * {@code VegetationBlock}, {@code SkipPacketException} became {@code SkipPacketEncoderException} and
 * the old name came back as an interface. Mapping both ids to the newcomer means a mod that meant
 * the base silently links against something it has never seen, which is worse than failing.
 *
 * <p>37 rows were in that state, found by joining Fabric intermediary to the Mojang mappings for
 * 1.21.11, the last version intermediary covers. Deciding a new entry needs that join; noticing one
 * appeared does not, which is what this test is for, because the suite has no Minecraft jar.
 */
class IntermediaryDestinationCollisionTest {

    private static final String TABLE = "/intermediary-to-mojang.tsv";
    private static final String BASELINE = "/intermediary-collision-baseline.txt";

    private static List<String> readLines(String resource) {
        List<String> lines = new ArrayList<>();
        try (InputStream in = IntermediaryDestinationCollisionTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must ship on the classpath");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        } catch (Exception e) {
            return fail("could not read " + resource + ": " + e);
        }
        return lines;
    }

    private static Map<String, List<String>> sourcesByDestination() {
        Map<String, List<String>> byDestination = new LinkedHashMap<>();
        for (String line : readLines(TABLE)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\t");
            if (parts.length < 3 || !parts[0].equals("CLASS")) continue;
            List<String> sources = byDestination.computeIfAbsent(
                    parts[2], ignored -> new ArrayList<>());
            // Count distinct ids. The same id listed twice is redundancy, not a collision, and the
            // loader keeps one of them anyway.
            if (!sources.contains(parts[1])) sources.add(parts[1]);
        }
        return byDestination;
    }

    private static TreeSet<String> collidingDestinations() {
        TreeSet<String> colliding = new TreeSet<>();
        sourcesByDestination().forEach((destination, sources) -> {
            if (sources.size() > 1) colliding.add(destination);
        });
        return colliding;
    }

    private static TreeSet<String> baseline() {
        TreeSet<String> expected = new TreeSet<>();
        for (String line : readLines(BASELINE)) {
            String entry = line.strip();
            if (!entry.isEmpty() && !entry.startsWith("#")) expected.add(entry);
        }
        return expected;
    }

    @Test
    @DisplayName("no new Mojang name becomes claimed by two intermediary classes")
    void noNewCollisions() {
        TreeSet<String> actual = collidingDestinations();
        TreeSet<String> expected = baseline();

        TreeSet<String> added = new TreeSet<>(actual);
        added.removeAll(expected);
        assertTrue(added.isEmpty(),
                "these Mojang names are now claimed by more than one intermediary class. Join Fabric "
                + "intermediary to the 1.21.11 Mojang mappings and check whether the ids are the same "
                + "logical class under different numbering, which is fine and belongs in the "
                + "baseline, or genuinely different classes, which silently points a mod at the "
                + "wrong one: " + added);
    }

    @Test
    @DisplayName("a resolved collision is removed from the baseline deliberately")
    void baselineDoesNotRot() {
        TreeSet<String> actual = collidingDestinations();
        TreeSet<String> expected = baseline();

        TreeSet<String> stale = new TreeSet<>(expected);
        stale.removeAll(actual);
        assertTrue(stale.isEmpty(),
                "these no longer collide, so the fix is done and the baseline should shrink with it: "
                + stale);
    }

    @Test
    @DisplayName("the recycled names that prompted this stay resolved")
    void recycledNamesStayCorrect() {
        Map<String, String> bySource = new HashMap<>();
        sourcesByDestination().forEach((destination, sources) ->
                sources.forEach(source -> bySource.put(source, destination)));

        // Each is a class Minecraft renamed and whose old name it then reused, so the table has two
        // ids for what reads as one name. The mod means the renamed one. Verified by hierarchy
        // against the 26.2 jar: the newcomer extends or implements the renamed class in every case.
        Map<String, String> expected = Map.of(
                "net/minecraft/class_1430", "net/minecraft/world/entity/animal/cow/AbstractCow",
                "net/minecraft/class_2261", "net/minecraft/world/level/block/VegetationBlock",
                "net/minecraft/class_2548", "net/minecraft/network/SkipPacketEncoderException",
                "net/minecraft/class_382", "net/minecraft/client/gui/font/glyphs/BakedSheetGlyph",
                "net/minecraft/class_840",
                        "net/minecraft/client/renderer/blockentity/AbstractEndPortalRenderer",
                "net/minecraft/class_5149", "net/minecraft/world/entity/animal/equine/Variant",
                "net/minecraft/class_6501", "net/minecraft/util/BoundedFloatFunction",
                "net/minecraft/class_5878", "net/minecraft/core/particles/ParticleLimit",
                "net/minecraft/class_8804", "net/minecraft/references/ItemIds",
                "net/minecraft/class_9329",
                        "net/minecraft/core/component/DataComponentExactPredicate");

        expected.forEach((source, destination) ->
                assertEquals(destination, bySource.get(source),
                        source + " must resolve to the class it actually became, not to the "
                        + "newcomer that took over its old name"));
    }
}
