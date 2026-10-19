/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Copies a mod's data to the folders a newer game reads it from. */
public final class ModDataLayoutMigrator implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataLayout");

    /** The first host that reads mod data from {@code data/} instead of {@code assets/}. */
    static final String FIRST_DATA_FOLDER_HOST = "1.13";
    /** The first host that reads only the singular data folder names. */
    static final String FIRST_SINGULAR_FOLDER_HOST = "1.21";
    /** The 1.21 release data format, the first release with singular folders. */
    private static final PackFormat FIRST_SINGULAR_DATA_FORMAT = new PackFormat(48, 0);

    // 1.12 data kept under assets/<ns>/, with the file type the game read from each folder.
    // Checked against the 1.12.2 and 1.13 client jars (asset matrix).
    private static final Map<String, String> ASSET_SIDE_FOLDERS = ordered(
            "recipes", ".json", "loot_tables", ".json", "advancements", ".json",
            "structures", ".nbt", "functions", ".mcfunction");

    /** Forge 1.12 recipe helpers that no later loader reads; their own converter inlines them. */
    private static final Set<String> FORGE_RECIPE_HELPERS = Set.of("_constants.json", "_factories.json");

    // The 1.21 folder renames, from a directory diff of the 1.20.5 and 1.21 client jars (asset
    // matrix). Registry tags such as tags/worldgen/ kept their names.
    private static final Map<String, String> SINGULAR_FOLDERS = ordered(
            "recipes", "recipe", "loot_tables", "loot_table", "advancements", "advancement",
            "structures", "structure", "predicates", "predicate", "item_modifiers", "item_modifier",
            "functions", "function", "tags/blocks", "tags/block", "tags/items", "tags/item",
            "tags/entity_types", "tags/entity_type", "tags/fluids", "tags/fluid",
            "tags/game_events", "tags/game_event", "tags/functions", "tags/function");

    /** The characters an identifier path may hold. */
    private static final Pattern IDENTIFIER_PATH = Pattern.compile("[a-z0-9_./-]+");
    /** The characters a namespace may hold. */
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");

    @Override
    public String name() {
        return "data layout";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return copiesAssetSideData(context) || context.targetAtLeast(FIRST_SINGULAR_FOLDER_HOST);
    }

    /** Copies old-layout data to the new folders and returns how many files were copied. */
    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        SortedSet<String> original = tree.names();
        List<Copy> copies = new ArrayList<>();
        // 1.21 renamed the data folders to singular names (recipes to recipe, tags/blocks to
        // tags/block), and nothing loads the plural ones: no recipes, no drops, empty tags.
        if (context.targetAtLeast(FIRST_SINGULAR_FOLDER_HOST)) {
            List<String> roots = packRoots(original);
            boolean shipsSingularFolders = hasAnySingularFolder(original, roots);
            for (String root : roots) {
                if (usesPluralFolders(tree, root, context, shipsSingularFolders)) {
                    planSingularCopies(original, root, copies);
                }
            }
        }
        // 1.13 moved recipes, loot tables, advancements, structures and functions from assets/<ns>/
        // to data/<ns>/, so an older mod also lost its advancements and jigsaw pieces.
        if (copiesAssetSideData(context)) {
            planAssetSideCopies(original, context.targetAtLeast(FIRST_SINGULAR_FOLDER_HOST), copies);
        }
        // A name that is valid as shipped wins over one that only becomes valid lowercased.
        copies.sort(Comparator.comparing(Copy::lowercased));

        // Files are copied, never moved: 1.12 mods read some of this data from the classpath
        // themselves (/assets/<ns>/structures/), and the original costs only space.
        int copied = 0;
        for (Copy copy : copies) {
            if (tree.exists(copy.to())) continue; // the mod's own file at the new path wins
            tree.write(copy.to(), tree.read(copy.from()));
            // Copied data reaches a loader that never saw it, so DataLoadGate checks each copy
            // and keeps out what would stop a world from loading.
            context.ledger().relocated(new MigrationLedger.Relocation(
                    copy.from(), copy.to(), copy.kind(), copy.legacyAssetSide()));
            copied++;
        }
        if (copied > 0) {
            LOGGER.info("Copied {} data file(s) to the folders Minecraft {} reads", copied,
                    context.targetMcVersion());
        }
        return copied;
    }

    /** Whether the mod predates the data folder and the host reads it. */
    private static boolean copiesAssetSideData(MigrationContext context) {
        return context.sourceBefore(FIRST_DATA_FOLDER_HOST)
                && context.targetAtLeast(FIRST_DATA_FOLDER_HOST);
    }

    /** Whether the data under {@code root} predates the singular folders. */
    private static boolean usesPluralFolders(ResourceTree tree, String root, MigrationContext context,
                                             boolean shipsSingularFolders) {
        // A nested data pack a mod registers from code declares its own format, which says more
        // than the jar's version.
        if (!root.isEmpty()) {
            PackFormat declared = declaredDataFormat(tree, root + "pack.mcmeta");
            if (declared != null) return declared.compareTo(FIRST_SINGULAR_DATA_FORMAT) < 0;
        }
        // The jar's own data follows the jar's version. An unknown version is not taken as old: a
        // 1.21 jar that names no version can still carry stale plural leftovers its game never
        // loaded. Such a jar counts as older only when no data folder in it has a 1.21 name.
        String source = context.sourceMcVersion();
        if (source == null || !source.matches("\\d+\\.\\d+.*")) return !shipsSingularFolders;
        return context.sourceBefore(FIRST_SINGULAR_FOLDER_HOST);
    }

    /** Whether any namespace under any pack root's {@code data/} has a folder with a 1.21 name. */
    private static boolean hasAnySingularFolder(SortedSet<String> names, List<String> roots) {
        for (String root : roots) {
            String dataPrefix = root + "data/";
            for (String namespace : namespacesUnder(names, dataPrefix)) {
                for (String singular : SINGULAR_FOLDERS.values()) {
                    if (hasPrefix(names, dataPrefix + namespace + "/" + singular + "/")) return true;
                }
            }
        }
        return false;
    }

    /** The data format a nested pack's {@code pack.mcmeta} declares, or null when it cannot be read. */
    private static PackFormat declaredDataFormat(ResourceTree tree, String metadata) {
        try {
            String content = new String(tree.read(metadata, PackArchive.MAX_METADATA_BYTES),
                    StandardCharsets.UTF_8);
            return PackMetadata.parse(content, metadata).primary();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The jar root plus every folder with its own pack.mcmeta, such as a pack a mod registers. */
    private static List<String> packRoots(SortedSet<String> names) {
        List<String> roots = new ArrayList<>();
        roots.add("");
        for (String name : names) {
            if (name.endsWith("/pack.mcmeta")) {
                roots.add(name.substring(0, name.length() - "pack.mcmeta".length()));
            }
        }
        return roots;
    }

    /** Plans a copy of each file in a plural folder under {@code root} to its singular folder. */
    private static void planSingularCopies(SortedSet<String> names, String root, List<Copy> copies) {
        String dataPrefix = root + "data/";
        for (String namespace : namespacesUnder(names, dataPrefix)) {
            String base = dataPrefix + namespace + "/";
            boolean shared = MigrationContext.SHARED_NAMESPACES.contains(namespace);
            for (Map.Entry<String, String> folder : SINGULAR_FOLDERS.entrySet()) {
                // In a shared namespace a copied recipe, loot table or advancement replaces
                // vanilla's working entry with one in the old shape, which 1.21 to 26.2 load
                // unchecked and drop when it fails to parse, taking vanilla's with it. The plural
                // original was inert there, so vanilla's entry loaded. Tags merge instead, and the
                // load gate makes a copied tag's entries optional, so only tags are copied there.
                if (shared && !folder.getValue().startsWith("tags/")) continue;
                String pluralPrefix = base + folder.getKey() + "/";
                String singularPrefix = base + folder.getValue() + "/";
                if (hasPrefix(names, singularPrefix)) continue; // already ships the 1.21 layout
                // Names the new game would reject are lowercased when that makes them valid,
                // and skipped when it does not.
                for (String name : withPrefix(names, pluralPrefix)) {
                    String path = identifierPath(name.substring(pluralPrefix.length()));
                    if (path == null) continue;
                    copies.add(new Copy(name, singularPrefix + path, folder.getValue(), false,
                            !path.equals(name.substring(pluralPrefix.length()))));
                }
            }
        }
    }

    /** Plans a copy of each 1.12 file under {@code assets/} to its folder under {@code data/}. */
    private static void planAssetSideCopies(SortedSet<String> names, boolean singular,
                                            List<Copy> copies) {
        for (String namespace : namespacesUnder(names, "assets/")) {
            // 1.12 Forge loaded recipes and advancements only for the mod's own id, and vanilla's
            // data came from the game jar, so a mod's assets/minecraft copy never replaced it.
            // Copied to data/minecraft it would replace vanilla's data on the new host.
            if (MigrationContext.SHARED_NAMESPACES.contains(namespace)) continue;
            for (Map.Entry<String, String> folder : ASSET_SIDE_FOLDERS.entrySet()) {
                String sourcePrefix = "assets/" + namespace + "/" + folder.getKey() + "/";
                String singularName = SINGULAR_FOLDERS.get(folder.getKey());
                String dataBase = "data/" + namespace + "/";
                // A jar that also ships this kind under data/ already carries its 1.13 data, as
                // MoreArmor does beside its 1.12 copy, so the author's version stays the only one.
                if (hasPrefix(names, dataBase + folder.getKey() + "/")
                        || hasPrefix(names, dataBase + singularName + "/")) {
                    continue;
                }
                // The destination uses the singular name when the host reads only that.
                String destinationPrefix = dataBase + (singular ? singularName : folder.getKey()) + "/";
                for (String name : withPrefix(names, sourcePrefix)) {
                    String relative = name.substring(sourcePrefix.length());
                    String fileName = relative.substring(relative.lastIndexOf('/') + 1);
                    // Only the file type the game read from this folder is copied, and never a
                    // Forge recipe helper, which its own converter inlines.
                    if (!relative.toLowerCase(Locale.ROOT).endsWith(folder.getValue())
                            || FORGE_RECIPE_HELPERS.contains(fileName)) {
                        continue;
                    }
                    String path = identifierPath(relative);
                    if (path == null) continue;
                    copies.add(new Copy(name, destinationPrefix + path, singularName, true,
                            !path.equals(relative)));
                }
            }
        }
    }

    /** The path as the new game accepts it: unchanged, lowercased, or null when neither works. */
    static String identifierPath(String path) {
        if (IDENTIFIER_PATH.matcher(path).matches()) return path;
        String lowercased = path.toLowerCase(Locale.ROOT);
        return IDENTIFIER_PATH.matcher(lowercased).matches() ? lowercased : null;
    }

    /** The valid namespace folders directly under {@code prefix}. */
    private static Set<String> namespacesUnder(SortedSet<String> names, String prefix) {
        Set<String> namespaces = new TreeSet<>();
        for (String name : withPrefix(names, prefix)) {
            int slash = name.indexOf('/', prefix.length());
            if (slash < 0) continue;
            String namespace = name.substring(prefix.length(), slash);
            if (NAMESPACE.matcher(namespace).matches()) namespaces.add(namespace);
        }
        return namespaces;
    }

    /** The names that start with {@code prefix}. */
    private static SortedSet<String> withPrefix(SortedSet<String> names, String prefix) {
        // Every name starting with the prefix sorts between the prefix and the prefix plus the
        // highest character, so the sorted set answers this without a full scan.
        return names.subSet(prefix, prefix + Character.MAX_VALUE);
    }

    private static boolean hasPrefix(SortedSet<String> names, String prefix) {
        return !withPrefix(names, prefix).isEmpty();
    }

    /** An unmodifiable map that keeps the order of its key and value pairs. */
    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(map);
    }

    /** One planned copy; {@code lowercased} marks a name that only became valid lowercased. */
    private record Copy(String from, String to, String kind, boolean legacyAssetSide,
                        boolean lowercased) {}
}
