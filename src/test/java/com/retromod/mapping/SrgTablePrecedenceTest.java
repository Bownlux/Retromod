/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two SRG tables overlap, and the owner-verified one has to win.
 *
 * <p>{@code srg-to-mojang.tsv} is an owner and descriptor disciplined join. {@code
 * srg-1.12.2-to-mojang.tsv} keeps whatever readable name MCP used, filtered only by "the name
 * exists somewhere", so where the two disagree it tends to hold the name from the mod's own era
 * rather than the host's. Both load into the same maps, so whichever loads second wins.
 *
 * <p>They loaded in the wrong order for a long time, because a comment asserted the key patterns
 * never collide. They collide on thousands of keys: the main table carries old {@code field_} and
 * {@code func_} spellings too. The effect was that a 1.12.2 mod's member got rewritten to a name
 * that does not exist on any modern host, or worse, to a real name belonging to an unrelated class.
 */
class SrgTablePrecedenceTest {

    private static final String STRONG = "/retromod/srg-to-mojang.tsv";
    private static final String LEGACY = "/retromod/srg-1.12.2-to-mojang.tsv";

    /**
     * Names verified against the 26.2 jar: the first value exists on the host, the second does not.
     * Each is a member a real 1.12.2 mod uses.
     */
    private static final String[][] MUST_RESOLVE_TO_HOST_NAME = {
            // GuiTextField character limit, now EditBox.maxLength; maxStringLength exists on 26.x
            // only on an unrelated search class.
            {"FIELD", "field_146217_k", "maxLength", "maxStringLength"},
            // Witch drinking speed. MODIFIER is a real 26.x name on a different owner, so the wrong
            // value resolves to something unrelated instead of failing loudly.
            {"FIELD", "field_110185_bq", "SPEED_MODIFIER_DRINKING", "MODIFIER"},
            // TileEntity.isInvalid became isRemoved. isInvalid exists nowhere on 26.x.
            {"METHOD", "func_145837_r", "isRemoved", "isInvalid"},
            // Entity.entityId became id. entityId survives on 26.x only on packet classes.
            {"FIELD", "field_145783_c", "id", "entityId"},
    };

    private static Map<String, String> read(String resource, String kind) {
        Map<String, String> rows = new HashMap<>();
        try (InputStream in = SrgTablePrecedenceTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must ship on the classpath");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String row = line.strip();
                if (row.isEmpty() || row.startsWith("#")) continue;
                String[] parts = row.split("\t");
                if (parts.length >= 3 && parts[0].equals(kind)) rows.put(parts[1], parts[2]);
            }
        } catch (Exception e) {
            return fail("could not read " + resource + ": " + e);
        }
        return rows;
    }

    @Test
    @DisplayName("a shared key resolves to the host's name, not the mod era's")
    void ownerVerifiedTableWins() {
        SrgToMojangMapper mapper = SrgToMojangMapper.getInstance();
        for (String[] c : MUST_RESOLVE_TO_HOST_NAME) {
            String kind = c[0], srg = c[1], hostName = c[2], modEraName = c[3];
            String resolved = kind.equals("FIELD")
                    ? mapper.getFieldMap().get(srg)
                    : mapper.getMethodMap().get(srg);
            assertEquals(hostName, resolved,
                    srg + " must resolve to " + hostName + ", which exists on the host, not "
                    + modEraName + ", which does not. The 1.12.2 table has to load FIRST so the "
                    + "owner-verified table overrides it.");
        }
    }

    @Test
    @DisplayName("the tables really do overlap, so load order is load-bearing")
    void theTablesOverlap() {
        for (String kind : new String[]{"FIELD", "METHOD"}) {
            Set<String> shared = new HashSet<>(read(STRONG, kind).keySet());
            shared.retainAll(read(LEGACY, kind).keySet());
            assertFalse(shared.isEmpty(),
                    "if these stop overlapping the precedence fix is no longer load-bearing, but "
                    + "the ordering should stay anyway; this test exists because a comment once "
                    + "claimed the keys never collide");
        }
    }

    @Test
    @DisplayName("loading in order loses no entry either table uniquely provides")
    void neitherTableIsLost() {
        SrgToMojangMapper mapper = SrgToMojangMapper.getInstance();
        for (String kind : new String[]{"FIELD", "METHOD"}) {
            Map<String, String> strong = read(STRONG, kind);
            Map<String, String> legacy = read(LEGACY, kind);
            Map<String, String> loaded = kind.equals("FIELD")
                    ? mapper.getFieldMap() : mapper.getMethodMap();

            for (Map.Entry<String, String> only : legacy.entrySet()) {
                if (strong.containsKey(only.getKey())) continue;
                assertEquals(only.getValue(), loaded.get(only.getKey()),
                        kind + " " + only.getKey() + " is only in the 1.12.2 table and must survive "
                        + "the ordering; that table is still the sole source for those ids");
            }
        }
    }
}
