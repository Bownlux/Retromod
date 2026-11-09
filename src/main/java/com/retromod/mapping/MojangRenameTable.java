/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.RetromodLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** The Minecraft class and member renames a Mojang-named mod needs on one host, from {@code mojang-renames.tsv}. */
public final class MojangRenameTable {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-MojangRenames");
    private static final String RESOURCE = "/retromod/mojang-renames.tsv";
    private static final Map<String, MojangRenameTable> CACHE = new ConcurrentHashMap<>();
    private static final MojangRenameTable EMPTY = new MojangRenameTable(List.of());

    /** One rename. {@code from} is null when the old name predates the oldest version the table read. */
    public record Row(Kind kind, String owner, String name, String desc, String newName,
            String hostOwner, String hostDesc, String from, String until) {

        /** Whether a mod built for {@code source} carries this row's old name; an unknown source does. */
        boolean appliesTo(String source) {
            if (source == null) return true;
            return (from == null || RetromodVersion.compareMcVersions(source, from) >= 0)
                    && RetromodVersion.compareMcVersions(source, until) < 0;
        }
    }

    public enum Kind { CLASS, METHOD, FIELD }

    private final List<Row> classRows = new ArrayList<>();
    // Member rows by the old owner, name and descriptor a mod carries.
    private final Map<String, List<Row>> bySource = new HashMap<>();
    // By the owner's host name with the old descriptor, for walks through host supertypes.
    private final Map<String, List<Row>> byHostOwner = new HashMap<>();
    // By the owner's and descriptor's host names, for selector text that is already redirected.
    private final Map<String, List<Row>> byHost = new HashMap<>();
    // Method rows by owner and old name alone, for bare selectors and lambda method names.
    private final Map<String, List<Row>> byOwnerAndName = new HashMap<>();
    private final Set<String> oldNames = new HashSet<>();

    private MojangRenameTable(List<Row> rows) {
        for (Row row : rows) {
            if (row.kind() == Kind.CLASS) {
                classRows.add(row);
                continue;
            }
            String kind = row.kind().name();
            add(bySource, key(kind, row.owner(), row.name(), row.desc()), row);
            add(byHostOwner, key(kind, row.hostOwner(), row.name(), row.desc()), row);
            add(byHost, key(kind, row.hostOwner(), row.name(), row.hostDesc()), row);
            if (row.kind() == Kind.METHOD) {
                add(byOwnerAndName, row.owner() + '\0' + row.name(), row);
                if (!row.hostOwner().equals(row.owner())) {
                    add(byOwnerAndName, row.hostOwner() + '\0' + row.name(), row);
                }
            }
            oldNames.add(kind + '\0' + row.name());
        }
    }

    /** The rows for {@code hostVersion}, or an empty table on a host that runs SRG or intermediary names. */
    public static MojangRenameTable forHost(String hostVersion) {
        if (hostVersion == null || hostVersion.isBlank()) return EMPTY;
        return CACHE.computeIfAbsent(hostVersion, MojangRenameTable::load);
    }

    /**
     * Turns the table on for the NeoForge and Forge mods transformed next on {@code host} and
     * registers its class renames. Call it after every shim and polyfill, which it defers to.
     */
    public static int register(RetromodTransformer transformer, String host) {
        MojangRenameTable table = forHost(host);
        transformer.setMojangRenames(table);
        if (table.isEmpty()) return 0;
        // The running game answers which classes exist; offline, the indexed --mc-jar does.
        Predicate<String> hostHasClass = IntermediaryToMojangMapper.buildHostClassCheck();
        if (hostHasClass == null) {
            com.retromod.core.FuzzyMethodResolver index = transformer.getFuzzyResolver();
            if (index != null && index.isIndexed()) hostHasClass = index::hasClass;
        }
        int classes = table.registerClassRenames(transformer, hostHasClass);
        LOGGER.debug(RetromodLog.DETAIL, "Registered {} Minecraft class renames and {} member renames for host {}",
                classes, table.memberRowCount(), host);
        return classes;
    }

    public boolean isEmpty() {
        return classRows.isEmpty() && bySource.isEmpty();
    }

    public List<Row> classRows() {
        return List.copyOf(classRows);
    }

    public int memberRowCount() {
        int count = 0;
        for (List<Row> rows : bySource.values()) count += rows.size();
        return count;
    }

    /** Whether any member row renames {@code name}, a cheap check before a lookup walks a hierarchy. */
    public boolean renamesName(boolean field, String name) {
        return oldNames.contains((field ? Kind.FIELD : Kind.METHOD).name() + '\0' + name);
    }

    /** The row for an owner, name and descriptor exactly as a mod carries them. */
    public Row find(boolean field, String owner, String name, String desc, String source) {
        return pick(bySource.get(key(kind(field), owner, name, desc)), source);
    }

    /** The row for a host class reached through a hierarchy walk, with the mod's own descriptor. */
    public Row findOnHostOwner(boolean field, String hostOwner, String name, String desc, String source) {
        return pick(byHostOwner.get(key(kind(field), hostOwner, name, desc)), source);
    }

    /** The row for selector text whose owner and descriptor already carry host class names. */
    public Row findOnHost(boolean field, String hostOwner, String name, String hostDesc, String source) {
        return pick(byHost.get(key(kind(field), hostOwner, name, hostDesc)), source);
    }

    /** The one method row for an owner and old name whatever its descriptor, or null when they disagree. */
    public Row findMethodByName(String owner, String name, String source) {
        return pick(byOwnerAndName.get(owner + '\0' + name), source);
    }

    /**
     * Registers the class renames as class redirects, each limited to mods built before it.
     * A class a shim or the 26.1 move table already redirects keeps that redirect, and a rename
     * applies only where the host has the new class and not the old one.
     */
    public int registerClassRenames(RetromodTransformer transformer, Predicate<String> hostHasClass) {
        Map<String, Integer> perOldName = new HashMap<>();
        for (Row row : classRows) perOldName.merge(row.owner(), 1, Integer::sum);
        Map<String, String> existing = transformer.getClassRedirects();
        int registered = 0;
        for (Row row : classRows) {
            // Two rows for one old name are two classes that shared it, which one redirect cannot tell apart.
            if (perOldName.get(row.owner()) > 1 || existing.containsKey(row.owner())) continue;
            if (transformer.handlesRemovedClass(row.owner()) || rebuiltByRetromod(row.owner())) continue;
            // A later move of the new class, such as 26.2's advancement packages, is followed in one hop.
            String target = followRedirects(existing, row.newName());
            if (hostHasClass != null && (hostHasClass.test(row.owner())
                    || (target.startsWith("net/minecraft/") || target.startsWith("com/mojang/"))
                            && !hostHasClass.test(target))) {
                continue;
            }
            transformer.registerClassRedirect(row.owner(), target);
            transformer.limitClassRedirectToModsBefore(row.owner(), row.until());
            transformer.markMojangClassRename(row.owner());
            registered++;
        }
        return registered;
    }

    /** Whether Retromod ships its own rebuild of the old class, which a bridge, not a rename, points mods at. */
    private static boolean rebuiltByRetromod(String name) {
        // The intermediary ids follow the code, so a class Mojang reworked can map to a different API.
        if (!name.startsWith("net/minecraft/")) return false;
        String rebuilt = com.retromod.shim.common.RebuiltApiClasses.ROOT + "minecraft/"
                + name.substring("net/minecraft/".length()) + ".class";
        return MojangRenameTable.class.getClassLoader().getResource(rebuilt) != null;
    }

    private static String followRedirects(Map<String, String> redirects, String name) {
        String current = name;
        for (int hop = 0; hop < 8; hop++) {
            String next = redirects.get(current);
            if (next == null || next.equals(current)) break;
            current = next;
        }
        return current;
    }

    /** The row whose source range holds {@code source}, or null when none does or two disagree. */
    private static Row pick(List<Row> rows, String source) {
        if (rows == null) return null;
        Row found = null;
        for (Row row : rows) {
            if (!row.appliesTo(source)) continue;
            if (found != null && !found.newName().equals(row.newName())) return null;
            found = row;
        }
        return found;
    }

    private static String kind(boolean field) {
        return field ? "FIELD" : "METHOD";
    }

    private static String key(String kind, String owner, String name, String desc) {
        return kind + '\0' + owner + '\0' + name + '\0' + desc;
    }

    private static void add(Map<String, List<Row>> index, String key, Row row) {
        index.computeIfAbsent(key, ignored -> new ArrayList<>(1)).add(row);
    }

    private static MojangRenameTable load(String host) {
        List<Row> rows = new ArrayList<>();
        try (InputStream input = MojangRenameTable.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("missing resource " + RESOURCE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length != 11) continue;
                // Each row names the hosts it holds for, so a rename never reaches a host older than it.
                if (!holdsFor(host, parts[9], parts[10])) continue;
                Kind kind = Kind.valueOf(parts[0]);
                String hostOwner = parts[5].equals("=") ? parts[1] : parts[5];
                String hostDesc = parts[6].equals("=") ? parts[3] : parts[6];
                rows.add(new Row(kind, parts[1], parts[2], parts[3], parts[4], hostOwner, hostDesc,
                        parts[7].equals("*") ? null : parts[7], parts[8]));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read Retromod's Minecraft rename table: {}", e.toString());
            return EMPTY;
        }
        LOGGER.debug(RetromodLog.DETAIL, "Loaded {} Minecraft renames for host {}", rows.size(), host);
        return rows.isEmpty() ? EMPTY : new MojangRenameTable(rows);
    }

    private static boolean holdsFor(String host, String hostFrom, String hostUntil) {
        try {
            return RetromodVersion.compareMcVersions(host, hostFrom) >= 0
                    && (hostUntil.equals("*") || RetromodVersion.compareMcVersions(host, hostUntil) < 0);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
