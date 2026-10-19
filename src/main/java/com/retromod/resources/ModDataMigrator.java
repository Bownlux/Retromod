/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.core.RetromodVersion;
import com.retromod.util.JsonSecurity;
import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Rewrites the data-pack JSON and functions a mod ships, which the bytecode pass never reaches. */
public final class ModDataMigrator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataMigrate");
    private static final int MAX_JSON_DEPTH = 256;
    private static final long MAX_MIGRATABLE_FILE_BYTES = 16L * 1024 * 1024;

    // No nested braces inside a dyed_color object, so [^}] stays within the one object.
    private static final Pattern DYED_COLOR = Pattern.compile(
            "\"minecraft:dyed_color\"\\s*:\\s*\\{[^}]*?\"rgb\"\\s*:\\s*(-?\\d+)[^}]*?\\}");

    // Bare-int custom_model_data. The negative lookahead skips a value that's already a float
    // or a longer token.
    private static final Pattern CMD_INT = Pattern.compile(
            "\"minecraft:custom_model_data\"\\s*:\\s*(-?\\d+)(?![\\d.])");

    // A bare-string "minecraft:potion" array element inside an entity_type tag. Anchored on a
    // leading [ or , and a trailing , or ] so it can't touch an object's "id":"minecraft:potion";
    // the preceding ':' never matches [\[,]. The trailing delimiter stays in the lookahead.
    private static final Pattern POTION_ENTITY_SPLIT = Pattern.compile(
            "([\\[,]\\s*)\"minecraft:potion\"(?=\\s*[,\\]])");

    // Lenient-only features the strict reader rejects. A `//` inside a string value only
    // triggers the string-aware strip, never a false negative.
    private static final Pattern LENIENT_JSON_HINT = Pattern.compile("//|/\\*|,\\s*[}\\]]");

    // The first host that parses data-pack JSON strictly. Checked in the client jars: 1.21.5's
    // RegistryDataLoader, TagLoader and SimpleJsonResourceReloadListener read leniently, and 1.21.6
    // routes all three through a new StrictJsonParser. 1.21.11 and 26.x still do.
    static final String FIRST_STRICT_DATA_JSON_HOST = "1.21.6";

    private ModDataMigrator() {}

    /** A cheap pre-filter for the bundled resources this migrator can act on. */
    public static boolean isMigratableData(String entryName) {
        // Data-pack JSON and function files, plus the legacy item-entity vertex shader removed in 26.2.
        return isLegacyItemVertexShader(entryName)
                || entryName.endsWith(".mcfunction") && !isUnderAssets(entryName)
                && (entryName.startsWith("data/") || entryName.contains("/data/"))
                || entryName.endsWith(".json") && !isUnderAssets(entryName)
                && (entryName.startsWith("data/")         // datapack JSON (worldgen, loot, tags, ...)
                 || entryName.contains("/data/")          // same, with a path prefix
                 || entryName.contains("/loot_table")     // fallback for layouts that don't sit
                 || entryName.contains("/advancement")    // under data/
                 || entryName.contains("/recipe")
                 || entryName.contains("/tags/"));
    }

    /** Whether the entry sits in an {@code assets/} folder before any {@code data/} folder. */
    private static boolean isUnderAssets(String entryName) {
        // A 1.12 mod kept recipes and loot tables under assets/ and may still read them from code in
        // the old shape, so only its data/ copy is rewritten. Client files such as a nested pack's
        // models/item/recipe_book.json are not data either.
        for (String segment : entryName.split("/")) {
            if (segment.equals("data")) return false;
            if (segment.equals("assets")) return true;
        }
        return false;
    }

    /** Apply the data rewrites to one entry for the loader running this game, or return it unchanged. */
    public static byte[] migrate(String entryName, byte[] json, String targetMcVersion) {
        return migrate(entryName, json, targetMcVersion, MigrationContext.Loader.host());
    }

    /** Apply the data rewrites to one JSON entry for {@code targetLoader}. */
    public static byte[] migrate(String entryName, byte[] json, String targetMcVersion,
                                 MigrationContext.Loader targetLoader) {
        // Forge and NeoForge read global loot modifiers in different shapes, so the loader the
        // transform writes for decides, never the one it runs on: the CLI and AOT run without one.
        return migrate(entryName, json, null, targetMcVersion, targetLoader);
    }

    /** Apply the data rewrites to one entry of a mod built for {@code sourceMcVersion}, which may be null. */
    public static byte[] migrate(String entryName, byte[] json, String sourceMcVersion,
                                 String targetMcVersion, MigrationContext.Loader targetLoader) {
        // Many format changes since 1.17 are data-only. Each rewrite runs from the release that
        // changed its format, never from 26.x as a stand-in, since 1.21.x rejects an old shape too.
        if (targetMcVersion == null || RetromodVersion.compareMcVersions(targetMcVersion, "1.20") < 0
                || !isMigratableData(entryName)) {
            return json;
        }
        if (isLegacyItemVertexShader(entryName)) {
            return RetromodVersion.isUnobfuscatedTarget(targetMcVersion)
                    ? migrateLegacyItemVertexShader(json, targetMcVersion) : json;
        }
        // A function file only needs the ids its commands name renamed.
        if (entryName.endsWith(".mcfunction")) {
            return VanillaIdRenames.migrateFunction(json, sourceMcVersion, targetMcVersion);
        }

        try {
            JsonSecurity.validate(json, MAX_MIGRATABLE_FILE_BYTES, MAX_JSON_DEPTH,
                    "Data pack JSON");
        } catch (IOException refused) {
            return json;
        }

        // From 1.21.6 a comment or trailing comma leaves the file unbound, and one unbound
        // template_pool is a FatalStartupException that aborts the shared worldgen registry load,
        // so co-loaded structure mods look like they never registered their own types. A clean
        // file comes back as the same array, so the rewrites below still see the original bytes.
        byte[] normalized = parsesDataJsonStrictly(targetMcVersion) ? normalizeLenientJson(json) : json;

        // The rename table lists the thrown potion entity as removed, which would only make it
        // optional, so its 1.21.5 split into two entities runs first. Only entity_type tags
        // change, since the potion item kept its id.
        if (atLeast(targetMcVersion, FIRST_SPLIT_POTION_ENTITY_HOST)
                && entryName.contains("/tags/entity_type/")) {
            normalized = splitPotionEntity(normalized);
        }

        // Minecraft's renamed ids, such as minecraft:chain to minecraft:iron_chain in 1.21.9.
        normalized = VanillaIdRenames.migrate(entryName, normalized, sourceMcVersion, targetMcVersion);

        // 1.21 made painting variants data sized in blocks. One out-of-range size failed the
        // whole registry load, so a size that can only be pixels is read as pixels.
        if (atLeast(targetMcVersion, "1.21") && PAINTING_VARIANT.matcher(entryName).matches()) {
            normalized = paintingSizeInBlocks(normalized);
        }

        // Item stacks, item predicates and loot functions, each from the release that changed
        // them. They read the old condition and function keys, so they run before the 26.x
        // rewrites below replace those keys.
        normalized = ItemStackDataMigrator.migrate(entryName, normalized, sourceMcVersion, targetMcVersion);

        // 26.2 turned EntityPredicate into a registry-keyed map: the old field names ("type",
        // "flags", ...) are now sub-predicate registry ids ("minecraft:entity_type", ...), so an
        // old predicate fails with "Unknown registry key in entity_sub_predicate_type" (#114).
        // 26.1 still reads the old names and ignores the new ones, so the rewrite waits for 26.2.
        // It parses the JSON, so nothing outside a predicate object is touched.
        boolean advancementLike = entryName.contains("/advancement") || entryName.contains("/predicates/");
        String probe = new String(normalized, StandardCharsets.UTF_8);
        if (atLeast(targetMcVersion, FIRST_KEYED_ENTITY_PREDICATE_HOST)
                && (probe.contains("entity_properties") || (advancementLike && probe.contains("\"type\"")))) {
            normalized = migrateEntityPredicates(normalized, advancementLike);
        }

        // 1.20 renamed the loot condition alternative to any_of.
        normalized = renameAlternativeConditions(normalized);

        // 26.3 renamed seven trigger condition fields, such as recipe_unlocked's "recipe" to
        // "recipes", and advancements are loaded with the registries, so one old file stopped the
        // world from loading. An old enter_block "block" was worse: ignored, it matched any block.
        if (entryName.contains("/advancement") && probe.contains("\"criteria\"")
                && atLeast(targetMcVersion, "26.3")) {
            normalized = migrateRenamedTriggerFields(normalized);
        }

        // 26.3 reads a number provider only with its type, so an untyped {"min", "max"} roll or
        // count, accepted since 1.17, failed the loot table with "No key type". It reads the old
        // "functions" and "conditions" keys, so it runs before the rewrite below.
        if (atLeast(targetMcVersion, "26.3")
                && LegacyLootConditionMigrator.isLootFile(entryName) && probe.contains("\"min\"")) {
            normalized = typeUntypedUniforms(normalized);
        }

        // 26.3 rewrote loot condition and function syntax and ignores the old keys, so an old
        // door dropped from both halves and old drops lost their counts and bonuses.
        boolean neoForgeHost = targetLoader == MigrationContext.Loader.NEOFORGE;
        boolean forgeHost = targetLoader == MigrationContext.Loader.FORGE;
        if (atLeast(targetMcVersion, "26.3")
                && LegacyLootConditionMigrator.mayContainOldConditions(entryName,
                        new String(normalized, StandardCharsets.UTF_8), neoForgeHost || forgeHost)) {
            normalized = LegacyLootConditionMigrator.migrate(normalized,
                    LegacyLootConditionMigrator.isLootFile(entryName),
                    forgeHost && LegacyLootConditionMigrator.isLootModifier(entryName));
        }

        // Between 1.20.1 and 1.21, int and float providers lost their "value" wrapper, so an old
        // dimension type or feature failed to parse its spawn light level, counts, or heights.
        if (atLeast(targetMcVersion, "1.21") && probe.contains("\"value\"")
                && (probe.contains("_inclusive") || probe.contains("\"plateau\"")
                || probe.contains("\"deviation\"") || probe.contains("\"distribution\""))) {
            normalized = inlineLegacyProviderValues(normalized);
        }

        boolean unobfuscated = RetromodVersion.isUnobfuscatedTarget(targetMcVersion);
        // 26.1 replaced a tree's dirt_provider and force_dirt with below_trunk_provider, so an old
        // tree failed to parse and left its feature unbound.
        if (unobfuscated && probe.contains("\"dirt_provider\"")) {
            normalized = migrateTreeSoil(normalized);
        }

        // 1.21.11 moved dimension types to environment attributes.
        if (atLeast(targetMcVersion, "1.21.11") && DimensionTypeMigrator.isDimensionType(entryName)) {
            normalized = DimensionTypeMigrator.migrate(normalized, targetMcVersion);
        }

        // 1.21.9, 26.2, and 26.3 each changed noise settings and density functions, and one old mod
        // dimension stops the registry load.
        if (atLeast(targetMcVersion, "1.21") && NoiseSettingsMigrator.isTerrainData(entryName)) {
            normalized = NoiseSettingsMigrator.migrate(entryName, normalized, targetMcVersion);
        }

        // 26.1 deleted the random_patch and flower features, and one stale configured feature
        // fails the whole worldgen registry load. Both read the old "config" wrapper, so they
        // run before the 26.3 rewrite below removes it.
        if (unobfuscated && LegacyRandomPatchMigrator.mayContainPatch(entryName, probe)) {
            normalized = LegacyRandomPatchMigrator.migrate(normalized);
        }
        if (unobfuscated && LegacyDiskStateProviderMigrator.mayContainDisk(entryName, probe)) {
            normalized = LegacyDiskStateProviderMigrator.migrate(normalized);
        }

        // 26.3 changed mob spawn entries.
        if (SpawnerDataMigrator.appliesTo(targetMcVersion) && SpawnerDataMigrator.holdsSpawns(entryName)) {
            normalized = SpawnerDataMigrator.migrate(entryName, normalized);
        }

        if (Worldgen26_3Migrator.appliesTo(targetMcVersion) && Worldgen26_3Migrator.isWorldgen(entryName)) {
            normalized = Worldgen26_3Migrator.migrate(normalized);
        }

        // 1.21.2 replaced ingredient objects ({"item": ...}, {"tag": ...}) with a bare id or
        // "#tag" string, and skips a recipe in the old shape. 26.3 loads recipes with the
        // registries, so there one old recipe stopped the world from loading.
        if (atLeast(targetMcVersion, FIRST_STRING_INGREDIENT_HOST) && isRecipeFile(entryName)
                && (probe.contains("\"item\"") || probe.contains("\"tag\""))) {
            normalized = migrateRecipeIngredients(normalized, targetLoader, sourceMcVersion, targetMcVersion);
        }

        String in = new String(normalized, StandardCharsets.UTF_8);
        String out = in;
        // 1.21.5 reads dyed_color as a plain int instead of an {"rgb": N} object.
        if (atLeast(targetMcVersion, ItemStackComponents.FIRST_INLINE_COMPONENT_HOST)) {
            out = DYED_COLOR.matcher(out).replaceAll("\"minecraft:dyed_color\":$1");
        }
        // 1.21.4 made custom_model_data {"floats": [...]}; the old int becomes the first float.
        if (atLeast(targetMcVersion, ItemStackComponents.FIRST_FLOAT_MODEL_DATA_HOST)) {
            out = CMD_INT.matcher(out).replaceAll("\"minecraft:custom_model_data\":{\"floats\":[$1]}");
        }

        // No string rewrite fired: return normalized, which is `json` when no earlier rewrite
        // changed the file.
        if (out.equals(in)) return normalized;
        LOGGER.debug("Migrated data formats in {}", entryName);
        return out.getBytes(StandardCharsets.UTF_8);
    }

    /** Returns the same array when the tag names no thrown potion entity. */
    private static byte[] splitPotionEntity(byte[] tag) {
        String in = new String(tag, StandardCharsets.UTF_8);
        String out = POTION_ENTITY_SPLIT.matcher(in).replaceAll(
                "$1\"minecraft:splash_potion\", \"minecraft:lingering_potion\"");
        return out.equals(in) ? tag : out.getBytes(StandardCharsets.UTF_8);
    }

    // The first host whose entity predicates are keyed by sub-predicate registry ids. 26.1.2's
    // EntityPredicate still reads type, flags and equipment, and 26.2's vanilla loot tables use
    // minecraft:entity_type 176 times where 26.1.2's use it none.
    static final String FIRST_KEYED_ENTITY_PREDICATE_HOST = "26.2";

    // The first host that reads a recipe ingredient only as an id, a #tag or a list of ids,
    // checked in the 1.21 and 1.21.2 client jars' own recipes.
    static final String FIRST_STRING_INGREDIENT_HOST = "1.21.2";

    // The first host that registers the thrown potion as splash_potion and lingering_potion:
    // 1.21.4's entity type registry still names potion, and 1.21.5's names the two new ids.
    static final String FIRST_SPLIT_POTION_ENTITY_HOST = "1.21.5";

    private static boolean atLeast(String version, String floor) {
        return version != null && RetromodVersion.compareMcVersions(version, floor) >= 0;
    }
    /** Whether {@code targetMcVersion} rejects comments and trailing commas in data JSON. */
    static boolean parsesDataJsonStrictly(String targetMcVersion) {
        return targetMcVersion != null
                && RetromodVersion.compareMcVersions(targetMcVersion, FIRST_STRICT_DATA_JSON_HOST) >= 0;
    }

    private static final java.util.regex.Pattern ALTERNATIVE_CONDITION = java.util.regex.Pattern.compile(
            "(\"condition\"\\s*:\\s*)\"(?:minecraft:)?alternative\"");

    /**
     * 1.20 renamed the loot condition {@code alternative} to {@code any_of} with the same terms,
     * and a loot table that names the old one fails to load.
     */
    static byte[] renameAlternativeConditions(byte[] json) {
        String text = new String(json, StandardCharsets.UTF_8);
        if (!text.contains("alternative")) return json;
        java.util.regex.Matcher matcher = ALTERNATIVE_CONDITION.matcher(text);
        if (!matcher.find()) return json;
        return matcher.replaceAll("$1\"minecraft:any_of\"").getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isLegacyItemVertexShader(String entryName) {
        return entryName.endsWith("/shaders/core/rendertype_item_entity_no_cardinal_shading.vsh");
    }

    /** Updates the old item shader outputs to match 26.2's consolidated core/item fragment shader. */
    static byte[] migrateLegacyItemVertexShader(byte[] shader, String targetMcVersion) {
        if (RetromodVersion.mcVersionExceeds("26.2", targetMcVersion)) return shader;
        String in = new String(shader, StandardCharsets.UTF_8);
        if (!in.contains("texelFetch(Sampler2, UV2 / 16, 0)")) return shader;
        String out = in.replace(
                "#moj_import <minecraft:projection.glsl>",
                "#moj_import <minecraft:projection.glsl>\n"
                        + "#moj_import <minecraft:sample_lightmap.glsl>")
                .replace("in vec2 UV1;", "in ivec2 UV1;")
                .replace("out vec2 texCoord1;\r\n", "")
                .replace("out vec2 texCoord1;\n", "")
                .replace("out vec2 texCoord2;\r\n", "")
                .replace("out vec2 texCoord2;\n", "")
                .replace("out vec4 vertexColor;", "out vec4 vertexColor;\n"
                        + "out vec4 lightMapColor;\n"
                        + "out vec4 overlayColor;")
                .replace("Color * texelFetch(Sampler2, UV2 / 16, 0)",
                        "Color;\n"
                                + "    lightMapColor = sample_lightmap(Sampler2, UV2);\n"
                                + "    overlayColor = vec4(0.0)")
                .replace("    texCoord1 = UV1;\r\n", "")
                .replace("    texCoord1 = UV1;\n", "")
                .replace("    texCoord2 = UV2;\r\n", "")
                .replace("    texCoord2 = UV2;\n", "");
        return out.equals(in) ? shader : out.getBytes(StandardCharsets.UTF_8);
    }

    /** Removes the comments and trailing commas that the strict reader of 1.21.6 and newer rejects. */
    static byte[] normalizeLenientJson(byte[] json) {
        try {
            JsonSecurity.validate(json, MAX_MIGRATABLE_FILE_BYTES, MAX_JSON_DEPTH,
                    "Data pack JSON");
        } catch (IOException refused) {
            return json;
        }
        // Hand-written worldgen files (Jigsaw template_pools, processor_lists) often carry them,
        // and each such file throws MalformedJsonException on a strict host.
        String in = new String(json, StandardCharsets.UTF_8);
        if (!LENIENT_JSON_HINT.matcher(in).find()) return json;   // already strict; cheap exit
        String out = stripLenientJson(in);
        // Other lenient quirks (single quotes, unquoted keys, NaN) are left for the loader to
        // report. The same array comes back when nothing changed, so callers compare references.
        if (out.equals(in)) return json;
        LOGGER.debug("Normalized lenient JSON (stripped comments / trailing commas)");
        return out.getBytes(StandardCharsets.UTF_8);
    }

    /** String-aware strip of // and block comments plus trailing commas. */
    private static String stripLenientJson(String s) {
        // One pass, not a reserialize: a // or , inside a string value is copied verbatim and the
        // formatting is kept. Hand-rolled because Gson's own lenient reader rejects trailing commas.
        int n = s.length();
        StringBuilder out = new StringBuilder(n);
        boolean inStr = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(s.charAt(++i));   // escaped char (incl. \" and \\) copied verbatim
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') { inStr = true; out.append(c); continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {       // line comment
                i += 2;
                while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
                i--;                              // leave the newline for the loop to append
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {       // block comment
                int closing = s.indexOf("*/", i + 2);
                if (closing < 0) return s;
                out.append(' ');                  // keep adjacent JSON tokens separate
                i = closing + 1;                  // skip the closing '/'
                continue;
            }
            if (c == ',' && trailingComma(s, i + 1)) continue;            // drop a dangling comma
            out.append(c);
        }
        return out.toString();
    }

    /** True if the next non-whitespace, non-comment char from {@code i} is a closing }} or ]. */
    private static boolean trailingComma(String s, int i) {
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; }
            else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                return c == '}' || c == ']';
            }
        }
        return false;
    }

    /**
     * Apply {@link #migrate} to every data file in an already-extracted mod tree, rewriting
     * changed files in place. Used by the runtime transformers (Fabric/Forge) and JiJ handlers,
     * which extract a mod before repackaging; the byte-stream {@link #migrate} fits the offline
     * CLI's entry-by-entry copy instead.
     *
     * <p>Returns 0 for a missing or empty root, or when no rewrite applies to the target. Entry
     * names are normalized to forward slashes so {@link #isMigratableData} path checks match on
     * every OS.
     *
     * @return the number of files rewritten.
     */
    public static int migrateTree(Path root, String targetMcVersion) {
        try {
            return migrateTreeChecked(root, targetMcVersion);
        } catch (IOException e) {
            LOGGER.debug("Could not migrate data tree {}: {}", root, e.getMessage());
            return 0;
        }
    }

    /** Applies this class's content steps to an extracted tree, with bounded reads and fail-closed checks. */
    public static int migrateTreeChecked(Path root, String targetMcVersion) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Data migration root is not a regular directory: " + root);
        }
        // Mod transforms run these same steps through ModResourceMigrations with the other resource
        // converters. This entry point keeps the steps a caller without a MigrationContext always had.
        ResourceTree tree = DirectoryResourceTree.open(root);
        // Nothing here waits for 26.x. The item definitions apply from 1.21.4, so a 1.21.1 to 1.21.11
        // translation needs them too.
        return migrateContent(tree, null, targetMcVersion, MigrationContext.Loader.host())
                + removeDanglingRecipeAdvancements(tree, targetMcVersion)
                + writeItemDefinitions(tree, targetMcVersion, null)
                + LegacyKeyCategoryNames.writeTree(tree, targetMcVersion, List.of(), name -> null)
                + moveRelocatedWorldgen(tree, targetMcVersion);
    }

    /** Applies {@link #migrate} to every migratable file in the tree and counts the files rewritten. */
    static int migrateContent(ResourceTree tree, String sourceMcVersion, String targetMcVersion,
                              MigrationContext.Loader targetLoader) throws IOException {
        int changed = 0;
        for (String name : tree.names()) {
            if (!isMigratableData(name)) continue; // migrate() picks the rewrites for the target
            // A file over the size limit fails the migration, as it always has, rather than
            // reaching the host unconverted.
            byte[] before = tree.read(name, MAX_MIGRATABLE_FILE_BYTES);
            byte[] after = migrate(name, before, sourceMcVersion, targetMcVersion, targetLoader);
            if (after != before) {        // same array back means nothing matched
                tree.write(name, after);
                changed++;
            }
        }
        return changed;
    }

    /** Removes the files {@link #danglingRecipeAdvancements} names. */
    static int removeDanglingRecipeAdvancements(ResourceTree tree, String targetMcVersion)
            throws IOException {
        int removed = 0;
        for (String advancement : danglingRecipeAdvancements(tree.names(),
                name -> readQuietly(tree, name), targetMcVersion)) {
            if (tree.delete(advancement)) removed++;
        }
        return removed;
    }

    /** Writes the client item definitions 1.21.4 and newer need for a mod that ships only item models. */
    static int writeItemDefinitions(ResourceTree tree, String targetMcVersion,
            java.util.Set<String> ownNamespaces) throws IOException {
        // Without assets/<ns>/items/<id>.json an item renders as the purple and black missing
        // model. The shared migration runs this same writer, so old overrides, spawn eggs and
        // code-rendered items match. A null ownNamespaces allows every namespace a mod may own.
        return ClientItemDefinitionMigrator.write(tree, targetMcVersion, ownNamespaces);
    }

    /** Moves configured features and carvers into the folders 26.3 reads them from. */
    static int moveRelocatedWorldgen(ResourceTree tree, String targetMcVersion) throws IOException {
        int moved = 0;
        for (String name : tree.names()) {
            String target = migratedEntryName(name, targetMcVersion);
            if (target.equals(name)) continue;
            // An entry already at the new path wins, as it is the mod's own 26.3 data.
            if (!tree.exists(target)) {
                tree.write(target, tree.read(name));
            }
            tree.delete(name);
            moved++;
        }
        return moved;
    }

    private static byte[] readQuietly(ResourceTree tree, String name) {
        try {
            return tree.read(name, MAX_MIGRATABLE_FILE_BYTES);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Where a data entry lives on {@code targetMcVersion}, which can differ from where it was built. */
    public static String migratedEntryName(String entryName, String targetMcVersion) {
        return Worldgen26_3Migrator.migratedEntryName(entryName, targetMcVersion);
    }

    /**
     * Validates every final JSON file below {@code data/} with Gson's strict streaming reader.
     * The structural depth check runs first so an untrusted file cannot grow the reader stack
     * without a fixed limit.
     */
    static void validateStrictDataJsonTree(Path root) throws IOException {
        Path dataRoot = root.resolve("data");
        if (!Files.exists(dataRoot, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(dataRoot)
                || !Files.isDirectory(dataRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Data pack data path is not a regular directory: " + dataRoot);
        }

        for (Path path : PackArchive.collectBoundedPaths(
                dataRoot, PackArchive.MAX_ARCHIVE_ENTRIES)) {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Data pack data tree contains a symbolic link: " + path);
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Data pack entry is not a regular file: " + path);
            }
            if (!path.getFileName().toString().endsWith(".json")) continue;

            String entryName = root.relativize(path).toString().replace(File.separatorChar, '/');
            String content = PackArchive.readRegularFile(path, MAX_MIGRATABLE_FILE_BYTES,
                "data pack JSON " + entryName);
            validateStrictJson(content, entryName);
        }
    }

    private static void validateStrictJson(String content, String entryName) throws IOException {
        JsonSecurity.validate(content, MAX_MIGRATABLE_FILE_BYTES, MAX_JSON_DEPTH,
            "Data pack JSON " + entryName);

        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.setLenient(false);
            boolean valueSeen = false;
            int depth = 0;
            while (true) {
                JsonToken token = reader.peek();
                switch (token) {
                    case BEGIN_ARRAY -> {
                        reader.beginArray();
                        depth++;
                        valueSeen = true;
                    }
                    case END_ARRAY -> {
                        reader.endArray();
                        depth--;
                    }
                    case BEGIN_OBJECT -> {
                        reader.beginObject();
                        depth++;
                        valueSeen = true;
                    }
                    case END_OBJECT -> {
                        reader.endObject();
                        depth--;
                    }
                    case NAME -> reader.nextName();
                    case STRING, NUMBER -> {
                        reader.nextString();
                        valueSeen = true;
                    }
                    case BOOLEAN -> {
                        reader.nextBoolean();
                        valueSeen = true;
                    }
                    case NULL -> {
                        reader.nextNull();
                        valueSeen = true;
                    }
                    case END_DOCUMENT -> {
                        if (!valueSeen || depth != 0) {
                            throw new IOException("Data pack JSON has no complete value: "
                                + entryName);
                        }
                        return;
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IOException ioFailure
                    && ioFailure.getMessage() != null
                    && ioFailure.getMessage().contains(entryName)) {
                throw ioFailure;
            }
            throw new IOException("Data pack JSON is not valid strict JSON: " + entryName
                + " (" + failure.getMessage() + ")", failure);
        }
    }

    // 1.21.x EntityPredicate field name -> 26.x sub-predicate registry id. Keys not in this map
    // (already-namespaced 26.x keys, unknown fields) pass through untouched. Verified against the
    // full 26.2 vanilla datapack vocabulary (3120 files scanned).
    private static final java.util.Map<String, String> ENTITY_PREDICATE_KEYS = java.util.Map.of(
            "type", "minecraft:entity_type",
            "flags", "minecraft:flags",
            "location", "minecraft:location",
            "movement", "minecraft:movement",
            "movement_affected_by", "minecraft:movement_affected_by",
            "periodic_tick", "minecraft:periodic_tick",
            "vehicle", "minecraft:vehicle",
            "equipment", "minecraft:equipment",
            "distance", "minecraft:distance",
            "stepping_on", "minecraft:stepping_on");

    // Only the singular folder: this check runs for 26.3 and newer, which never read the plural
    // one, so a plural original cannot dangle there and is left in the jar as it was shipped.
    private static final Pattern RECIPE_ADVANCEMENT =
            Pattern.compile("data/([a-z0-9_.-]+)/advancement/recipes/.+\\.json");

    /**
     * Recipe advancements that name a recipe from their own namespace which the jar does not ship.
     * Older versions ignored the dangling id; 26.3 binds it as a registry reference, so one such
     * file stopped the world from loading (Macaw's Bridges ships {@code iron_bridge} for a recipe
     * named {@code iron_bridge_middle}). Other namespaces are left alone, since another mod may
     * provide them.
     *
     * @param entryNames every entry path in the jar or tree, forward-slash separated
     * @param reader reads one entry's bytes, or returns null when it cannot
     * @return the advancement entries to drop; empty below 26.3
     */
    public static java.util.Set<String> danglingRecipeAdvancements(
            java.util.Collection<String> entryNames,
            java.util.function.Function<String, byte[]> reader, String targetMcVersion) {
        java.util.Set<String> dangling = new java.util.TreeSet<>();
        if (targetMcVersion == null || RetromodVersion.mcVersionExceeds("26.3", targetMcVersion)) {
            return dangling;
        }
        java.util.Set<String> names = new java.util.HashSet<>(entryNames);
        for (String name : names) {
            java.util.regex.Matcher m = RECIPE_ADVANCEMENT.matcher(name);
            if (!m.matches()) continue;
            byte[] json = reader.apply(name);
            if (json == null) continue;
            for (String id : rewardedRecipes(normalizeLenientJson(json))) {
                int colon = id.indexOf(':');
                if (colon < 0 || !id.substring(0, colon).equals(m.group(1))) continue;
                String path = id.substring(colon + 1);
                String ns = m.group(1);
                // 26.3 reads recipes only from the singular folder. A plural original is inert
                // there, so it counts only through the copy the layout step made of it, and a
                // copy the load gate kept out leaves the reward dangling.
                if (!names.contains("data/" + ns + "/recipe/" + path + ".json")) {
                    dangling.add(name);
                    break;
                }
            }
        }
        if (!dangling.isEmpty()) {
            LOGGER.info("Dropping {} recipe advancement(s) for recipes the mod does not ship: {}",
                    dangling.size(), dangling);
        }
        return dangling;
    }

    private static List<String> rewardedRecipes(byte[] json) {
        List<String> ids = new java.util.ArrayList<>();
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser
                    .parseString(new String(json, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return ids;
            com.google.gson.JsonElement rewards = root.getAsJsonObject().get("rewards");
            if (rewards != null && rewards.isJsonObject()
                    && rewards.getAsJsonObject().get("recipes") instanceof com.google.gson.JsonArray list) {
                for (com.google.gson.JsonElement id : list) {
                    if (id.isJsonPrimitive()) ids.add(id.getAsString());
                }
            }
        } catch (RuntimeException unparseable) {
            // leave the file to the loader's own error
        }
        return ids;
    }

    private static boolean isRecipeFile(String entryName) {
        return (entryName.contains("/recipe/") || entryName.contains("/recipes/"))
                && !entryName.contains("/advancement");
    }

    private static final Pattern PAINTING_VARIANT = Pattern.compile("data/[^/]+/painting_variant/.+\\.json");
    /** The largest painting side 1.21 accepts, in blocks. */
    private static final int MAX_PAINTING_BLOCKS = 16;

    /**
     * A painting variant written in pixels, as code sized paintings before 1.21, converted to the
     * blocks 1.21 reads. Only a file with a side over 16 is converted, since that cannot be blocks;
     * Scorched Guns ships its 64 by 32 painting this way. Both sides are pixels then.
     */
    static byte[] paintingSizeInBlocks(byte[] json) {
        com.google.gson.JsonElement root;
        try {
            root = com.google.gson.JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        } catch (RuntimeException unparsable) {
            return json;
        }
        if (!(root instanceof com.google.gson.JsonObject painting)
                || !(paintingSide(painting, "width") > MAX_PAINTING_BLOCKS
                        || paintingSide(painting, "height") > MAX_PAINTING_BLOCKS)) {
            return json;
        }
        for (String side : new String[] {"width", "height"}) {
            int pixels = paintingSide(painting, side);
            if (pixels > 0) {
                painting.addProperty(side, Math.min(MAX_PAINTING_BLOCKS, Math.max(1, (pixels + 15) / 16)));
            }
        }
        return new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(root)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static int paintingSide(com.google.gson.JsonObject painting, String side) {
        com.google.gson.JsonElement value = painting.get(side);
        return value instanceof com.google.gson.JsonPrimitive number && number.isNumber() ? number.getAsInt() : 0;
    }

    /** Recipe fields that hold one ingredient, and the fields that hold a map or list of them. */
    private static final List<String> SINGLE_INGREDIENT_FIELDS =
            List.of("ingredient", "template", "base", "addition");

    /**
     * A tree's {@code dirt_provider} becomes {@code below_trunk_provider}. With {@code force_dirt}
     * it is used as it is; without, it only replaces blocks outside
     * {@code #minecraft:cannot_replace_below_tree_trunk}, which is how vanilla wrote the change.
     */
    static byte[] migrateTreeSoil(byte[] json) {
        com.google.gson.JsonElement root;
        try {
            root = com.google.gson.JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        } catch (RuntimeException unparsable) {
            return json;
        }
        if (!rewriteTreeSoil(root)) return json;
        return new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(root)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static boolean rewriteTreeSoil(com.google.gson.JsonElement element) {
        boolean changed = false;
        if (element instanceof com.google.gson.JsonArray array) {
            for (com.google.gson.JsonElement item : array) changed |= rewriteTreeSoil(item);
        } else if (element instanceof com.google.gson.JsonObject object) {
            if (object.has("dirt_provider") && object.has("trunk_placer") && !object.has("below_trunk_provider")) {
                com.google.gson.JsonElement soil = object.remove("dirt_provider");
                com.google.gson.JsonElement force = object.remove("force_dirt");
                boolean forced = force != null && force.isJsonPrimitive() && force.getAsBoolean();
                object.add("below_trunk_provider", forced ? soil : unlessProtectedBelowTrunk(soil));
                changed = true;
            }
            for (var field : object.entrySet()) changed |= rewriteTreeSoil(field.getValue());
        }
        return changed;
    }

    private static com.google.gson.JsonObject unlessProtectedBelowTrunk(com.google.gson.JsonElement soil) {
        com.google.gson.JsonObject tag = new com.google.gson.JsonObject();
        tag.addProperty("type", "minecraft:matching_block_tag");
        tag.addProperty("tag", "minecraft:cannot_replace_below_tree_trunk");
        com.google.gson.JsonObject not = new com.google.gson.JsonObject();
        not.addProperty("type", "minecraft:not");
        not.add("predicate", tag);
        com.google.gson.JsonObject rule = new com.google.gson.JsonObject();
        rule.add("if_true", not);
        rule.add("then", soil);
        com.google.gson.JsonArray rules = new com.google.gson.JsonArray();
        rules.add(rule);
        com.google.gson.JsonObject provider = new com.google.gson.JsonObject();
        provider.addProperty("type", "minecraft:rule_based_state_provider");
        provider.add("rules", rules);
        return provider;
    }

    private static final java.util.Set<String> WRAPPED_PROVIDER_TYPES = java.util.Set.of(
            "minecraft:uniform", "minecraft:biased_to_bottom", "minecraft:clamped", "minecraft:clamped_normal",
            "minecraft:trapezoid", "minecraft:weighted_list");
    private static final java.util.Set<String> PROVIDER_FIELDS = java.util.Set.of(
            "min_inclusive", "max_inclusive", "max_exclusive", "min", "max", "plateau", "mean", "deviation",
            "source", "distribution");

    /**
     * {@code {"type": "minecraft:uniform", "value": {"min_inclusive": 0, ...}}} becomes
     * {@code {"type": "minecraft:uniform", "min_inclusive": 0, ...}}. Only those provider types, with
     * exactly a type and an object of provider fields, are inlined: a constant height provider's
     * {@code "value"} is an anchor object and stays as it is.
     */
    static byte[] inlineLegacyProviderValues(byte[] json) {
        com.google.gson.JsonElement root;
        try {
            root = com.google.gson.JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        } catch (RuntimeException unparsable) {
            return json;
        }
        if (!inlineProviders(root)) return json;
        return new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(root)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static boolean inlineProviders(com.google.gson.JsonElement element) {
        boolean changed = false;
        if (element instanceof com.google.gson.JsonArray array) {
            for (com.google.gson.JsonElement item : array) changed |= inlineProviders(item);
        } else if (element instanceof com.google.gson.JsonObject object) {
            if (isWrappedProvider(object)) {
                com.google.gson.JsonObject value = object.remove("value").getAsJsonObject();
                for (var field : value.entrySet()) object.add(field.getKey(), field.getValue());
                changed = true;
            }
            for (var field : object.entrySet()) changed |= inlineProviders(field.getValue());
        }
        return changed;
    }

    private static final java.util.Set<String> POOL_PROVIDERS = java.util.Set.of("rolls", "bonus_rolls");
    private static final java.util.Set<String> FUNCTION_PROVIDERS = java.util.Set.of("count", "damage", "levels");

    /**
     * Adds {@code "type": "minecraft:uniform"} to untyped {@code {"min", "max"}} number providers in
     * a loot table: a pool's rolls and bonus rolls, and a function's count, damage, and levels. The
     * same shape in a condition or predicate is a range, not a provider, and is left alone.
     */
    static byte[] typeUntypedUniforms(byte[] json) {
        com.google.gson.JsonElement root;
        try {
            root = com.google.gson.JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        } catch (RuntimeException unparsable) {
            return json;
        }
        if (!typeUniforms(root)) return json;
        return new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(root)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static boolean typeUniforms(com.google.gson.JsonElement element) {
        boolean changed = false;
        if (element instanceof com.google.gson.JsonArray array) {
            for (com.google.gson.JsonElement item : array) changed |= typeUniforms(item);
        } else if (element instanceof com.google.gson.JsonObject object) {
            java.util.Set<String> providers = object.has("function") ? FUNCTION_PROVIDERS
                    : object.has("entries") ? POOL_PROVIDERS : java.util.Set.of();
            for (String key : providers) {
                if (object.get(key) instanceof com.google.gson.JsonObject value && isUntypedUniform(value)) {
                    value.addProperty("type", "minecraft:uniform");
                    changed = true;
                }
            }
            // A condition list holds ranges, never providers, so it is not walked.
            for (var field : object.entrySet()) {
                if (!field.getKey().equals("conditions")) changed |= typeUniforms(field.getValue());
            }
        }
        return changed;
    }

    private static boolean isUntypedUniform(com.google.gson.JsonObject value) {
        return !value.has("type") && value.size() == 2 && value.has("min") && value.has("max");
    }

    /** A bare id such as {@code uniform} names Minecraft's, as the game reads it. */
    private static String namespaced(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    private static boolean isWrappedProvider(com.google.gson.JsonObject object) {
        if (object.size() != 2 || !(object.get("value") instanceof com.google.gson.JsonObject value)
                || !(object.get("type") instanceof com.google.gson.JsonPrimitive type)
                || !WRAPPED_PROVIDER_TYPES.contains(namespaced(type.getAsString())) || value.isEmpty()) {
            return false;
        }
        return PROVIDER_FIELDS.containsAll(value.keySet());
    }

    /** Convert pre-1.21.2 ingredient objects to the string form. */
    static byte[] migrateRecipeIngredients(byte[] json) {
        return migrateRecipeIngredients(json, null, null, null);
    }

    /** Convert pre-1.21.2 ingredients, including items with components or NBT for {@code loader}. */
    static byte[] migrateRecipeIngredients(byte[] json, MigrationContext.Loader loader,
                                           String sourceMcVersion, String targetMcVersion) {
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser
                    .parseString(new String(json, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return json;
            com.google.gson.JsonObject recipe = root.getAsJsonObject();
            // In a vanilla recipe type, an item that had to carry components or NBT becomes the
            // target loader's component ingredient. Without one that loader reads, it stays as is.
            String type = recipe.get("type") instanceof com.google.gson.JsonPrimitive primitive
                    && primitive.isString() ? namespaced(primitive.getAsString()) : "";
            ComponentIngredients components = type.startsWith("minecraft:") && loader != null
                    ? new ComponentIngredients(loader, sourceMcVersion, targetMcVersion) : null;
            boolean changed = false;
            for (String field : SINGLE_INGREDIENT_FIELDS) {
                com.google.gson.JsonElement converted = legacyIngredient(recipe.get(field), components);
                if (converted != null) {
                    recipe.add(field, converted);
                    changed = true;
                }
            }
            if (recipe.has("key") && recipe.get("key").isJsonObject()) {
                for (var entry : recipe.getAsJsonObject("key").entrySet()) {
                    com.google.gson.JsonElement converted = legacyIngredient(entry.getValue(), components);
                    if (converted != null) {
                        entry.setValue(converted);
                        changed = true;
                    }
                }
            }
            if (recipe.has("ingredients") && recipe.get("ingredients").isJsonArray()) {
                com.google.gson.JsonArray list = recipe.getAsJsonArray("ingredients");
                for (int i = 0; i < list.size(); i++) {
                    com.google.gson.JsonElement converted = legacyIngredient(list.get(i), components);
                    if (converted != null) {
                        list.set(i, converted);
                        changed = true;
                    }
                }
            }
            return changed ? root.toString().getBytes(StandardCharsets.UTF_8) : json;
        } catch (RuntimeException e) {
            return json;   // unparseable or unexpected shape: leave the file alone
        }
    }

    /** The string form of one legacy ingredient, or null when it is not a legacy ingredient. */
    private static com.google.gson.JsonElement legacyIngredient(com.google.gson.JsonElement value,
                                                                 ComponentIngredients components) {
        if (value == null) return null;
        if (value.isJsonObject()) {
            String id = legacyIngredientId(value.getAsJsonObject());
            // An object that is not a plain item or tag may still be an item with components.
            if (id == null && components != null) return components.convert(value.getAsJsonObject());
            return id == null ? null : new com.google.gson.JsonPrimitive(id);
        }
        // An array converts only when every entry is a plain item.
        if (value.isJsonArray() && !value.getAsJsonArray().isEmpty()) {
            com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
            for (com.google.gson.JsonElement alternative : value.getAsJsonArray()) {
                if (!alternative.isJsonObject()) return null;
                String id = legacyIngredientId(alternative.getAsJsonObject());
                if (id == null || id.startsWith("#")) return null; // a tag cannot join a list
                ids.add(id);
            }
            return ids;
        }
        return null;
    }

    /** Turns an item ingredient with components or NBT into the target loader's component ingredient. */
    private static final class ComponentIngredients {
        private final MigrationContext.Loader loader;
        private final String source;
        private final String target;

        ComponentIngredients(MigrationContext.Loader loader, String source, String target) {
            this.loader = loader;
            this.source = source;
            this.target = target;
        }

        /** The loader's component ingredient, or null when there is none or the shape is other. */
        com.google.gson.JsonElement convert(com.google.gson.JsonObject ingredient) {
            // Vanilla read only the item and ignored the rest, and from 1.21.2 it rejects the object.
            if (ingredient.size() != 2
                    || !(ingredient.get("item") instanceof com.google.gson.JsonPrimitive item)
                    || !item.isString()) {
                return null;
            }
            // {"item", "components"} was written for 1.20.5 to 1.21.1, and {"item", "nbt"} before that.
            com.google.gson.JsonObject components = null;
            if (ingredient.get("components") instanceof com.google.gson.JsonObject given && !given.isEmpty()) {
                components = given;
            } else if (ingredient.has("nbt")) {
                LegacyItemNbt.Compound tag = LegacyItemNbt.parseField(ingredient.get("nbt"));
                components = tag == null ? null
                        : ItemStackComponents.componentize(item.getAsString(), tag, source, target);
            }
            if (components == null || components.isEmpty()) return null;
            // NeoForge and Fabric API each read a component ingredient that matches the item with
            // those components. Checked in NeoForge 21.11.42 and 26.3.0.43, and in Fabric API
            // 0.141.4 for 1.21.11 and for 26.3.
            com.google.gson.JsonObject converted = new com.google.gson.JsonObject();
            if (loader == MigrationContext.Loader.NEOFORGE) {
                converted.addProperty("neoforge:ingredient_type", "neoforge:components");
                converted.add("items", item);
            } else if (loader.isFabricFamily()) {
                converted.addProperty("fabric:type", "fabric:components");
                converted.add("base", item);
            } else {
                return null;
            }
            converted.add("components", components);
            return converted;
        }
    }

    private static String legacyIngredientId(com.google.gson.JsonObject ingredient) {
        // Only an object whose sole key is item or tag. A loader's custom ingredient, anything
        // with a type, is left alone.
        if (ingredient.size() != 1) return null;
        com.google.gson.JsonElement item = ingredient.get("item");
        if (item != null && item.isJsonPrimitive()) return item.getAsString();
        com.google.gson.JsonElement tag = ingredient.get("tag");
        if (tag != null && tag.isJsonPrimitive()) return "#" + tag.getAsString();
        return null;
    }

    // The trigger condition fields 26.3 renamed, by trigger, read from the 26.2 and 26.3 trigger
    // classes and their vanilla advancements.
    private static final java.util.Map<String, java.util.Map<String, String>> TRIGGER_FIELDS_RENAMED_IN_26_3 =
            java.util.Map.of(
                    "minecraft:recipe_unlocked", java.util.Map.of("recipe", "recipes"),
                    "minecraft:recipe_crafted", java.util.Map.of("recipe_id", "recipes"),
                    "minecraft:crafter_recipe_crafted", java.util.Map.of("recipe_id", "recipes"),
                    "minecraft:enter_block", java.util.Map.of("block", "blocks"),
                    "minecraft:slide_down_block", java.util.Map.of("block", "blocks"),
                    "minecraft:bee_nest_destroyed", java.util.Map.of("block", "blocks"),
                    "minecraft:player_generates_container_loot", java.util.Map.of("loot_table", "loot_tables"));

    /** Renames the trigger condition fields 26.3 renamed, or returns the same array when none matched. */
    static byte[] migrateRenamedTriggerFields(byte[] json) {
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser
                    .parseString(new String(json, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("criteria")
                    || !root.getAsJsonObject().get("criteria").isJsonObject()) {
                return json;
            }
            boolean changed = false;
            for (var entry : root.getAsJsonObject().getAsJsonObject("criteria").entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                com.google.gson.JsonObject criterion = entry.getValue().getAsJsonObject();
                com.google.gson.JsonElement trigger = criterion.get("trigger");
                if (trigger == null || !trigger.isJsonPrimitive()) continue;
                String id = trigger.getAsString();
                // A trigger id without a namespace is a vanilla one.
                var renamed = TRIGGER_FIELDS_RENAMED_IN_26_3.get(id.indexOf(':') < 0 ? "minecraft:" + id : id);
                if (renamed == null) continue;
                com.google.gson.JsonElement conditions = criterion.get("conditions");
                if (conditions == null || !conditions.isJsonObject()) continue;
                com.google.gson.JsonObject c = conditions.getAsJsonObject();
                // Each new field takes one id as well as a list or a tag, so the old single value
                // carries over as it is.
                for (var field : renamed.entrySet()) {
                    if (c.has(field.getKey()) && !c.has(field.getValue())) {
                        c.add(field.getValue(), c.remove(field.getKey()));
                        changed = true;
                    }
                }
            }
            return changed ? root.toString().getBytes(StandardCharsets.UTF_8) : json;
        } catch (RuntimeException e) {
            return json;   // unparseable or unexpected shape: leave the file alone
        }
    }

    /**
     * Rewrite 1.21.x {@code entity_properties} predicates to the 26.x registry-keyed form.
     * Returns the input array unchanged when nothing matched or the JSON doesn't parse.
     */
    static byte[] migrateEntityPredicates(byte[] json) {
        return migrateEntityPredicates(json, false);
    }

    static byte[] migrateEntityPredicates(byte[] json, boolean advancementMode) {
        try {
            JsonSecurity.validate(json, MAX_MIGRATABLE_FILE_BYTES, MAX_JSON_DEPTH,
                    "Data pack JSON");
        } catch (IOException refused) {
            return json;
        }
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser
                    .parseString(new String(json, StandardCharsets.UTF_8));
            boolean[] changed = {false};
            rewriteEntityPredicates(root, null, changed, advancementMode);
            if (!changed[0]) return json;
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return json;   // unparseable or unexpected shape: leave the file alone
        }
    }

    // Advancement criteria embed EntityPredicates BARE under these keys (no entity_properties
    // wrapper): "victims": [{"type": "#aer:fish"}] etc. Subset-guarded: an object under one of
    // these slots is only rewritten when its keys all belong to the EntityPredicate vocabulary,
    // so an unrelated {"type": ...} object (an effect, a provider) can never be touched.
    private static final java.util.Set<String> ENTITY_PREDICATE_SLOTS = java.util.Set.of(
            "victims", "entity", "projectile", "direct_entity", "source_entity",
            "shooter", "cause", "parent", "partner", "child", "zombie", "villager");

    private static final java.util.Set<String> ENTITY_PREDICATE_VOCAB;
    static {
        java.util.Set<String> v = new java.util.HashSet<>(ENTITY_PREDICATE_KEYS.keySet());
        v.addAll(ENTITY_PREDICATE_KEYS.values());
        v.addAll(java.util.Set.of("nbt", "team", "effects", "slots", "components",
                "targeted_entity", "passenger", "type_specific"));
        ENTITY_PREDICATE_VOCAB = v;
    }

    private static void rewriteEntityPredicates(com.google.gson.JsonElement el,
            String parentCondition, boolean[] changed, boolean advancementMode) {
        if (el.isJsonArray()) {
            for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
                rewriteEntityPredicates(child, parentCondition, changed, advancementMode);
            }
            return;
        }
        if (!el.isJsonObject()) return;
        com.google.gson.JsonObject obj = el.getAsJsonObject();
        String condition = obj.has("condition") && obj.get("condition").isJsonPrimitive()
                ? obj.get("condition").getAsString() : parentCondition;
        if ("minecraft:entity_properties".equals(condition)
                && obj.has("predicate") && obj.get("predicate").isJsonObject()) {
            obj.add("predicate", rewritePredicateKeys(obj.getAsJsonObject("predicate"), changed));
        }
        if (advancementMode) {
            for (String slot : ENTITY_PREDICATE_SLOTS) {
                if (!obj.has(slot)) continue;
                com.google.gson.JsonElement v = obj.get(slot);
                if (v.isJsonObject() && looksLikeEntityPredicate(v.getAsJsonObject())) {
                    obj.add(slot, rewritePredicateKeys(v.getAsJsonObject(), changed));
                } else if (v.isJsonArray()) {
                    com.google.gson.JsonArray arr = v.getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        com.google.gson.JsonElement e = arr.get(i);
                        if (e.isJsonObject() && looksLikeEntityPredicate(e.getAsJsonObject())) {
                            arr.set(i, rewritePredicateKeys(e.getAsJsonObject(), changed));
                        }
                    }
                }
            }
        }
        for (String key : new java.util.ArrayList<>(obj.keySet())) {
            rewriteEntityPredicates(obj.get(key), condition, changed, advancementMode);
        }
    }

    /** Whether every key names an entity predicate field, in the old or the 26.2 spelling. */
    static boolean isEntityPredicateVocabulary(java.util.Set<String> keys) {
        return ENTITY_PREDICATE_VOCAB.containsAll(keys);
    }

    /** At least one legacy key present, and every key within the EntityPredicate vocabulary. */
    private static boolean looksLikeEntityPredicate(com.google.gson.JsonObject obj) {
        boolean anyLegacy = false;
        for (String k : obj.keySet()) {
            if (ENTITY_PREDICATE_KEYS.containsKey(k)) anyLegacy = true;
            else if (!ENTITY_PREDICATE_VOCAB.contains(k)) return false;
        }
        return anyLegacy;
    }

    private static com.google.gson.JsonObject rewritePredicateKeys(
            com.google.gson.JsonObject predicate, boolean[] changed) {
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        for (String key : predicate.keySet()) {
            String mapped = ENTITY_PREDICATE_KEYS.getOrDefault(key, key);
            com.google.gson.JsonElement value = predicate.get(key);
            // vehicle/targeted_entity values are nested EntityPredicates - migrate them too
            if (value.isJsonObject()
                    && ("vehicle".equals(key) || "targeted_entity".equals(key)
                        || "passenger".equals(key))) {
                value = rewritePredicateKeys(value.getAsJsonObject(), changed);
            }
            if (!mapped.equals(key)) changed[0] = true;
            out.add(mapped, value);
        }
        return out;
    }

    /** The client item definitions to add for a mod's entry names, one per item model without one. */
    public static java.util.Map<String, byte[]> synthesizeItemDefinitionEntries(
            java.util.Set<String> entryNames, String targetMcVersion) {
        return synthesizeItemDefinitionEntries(entryNames, targetMcVersion, null);
    }

    /** The same, limited to {@code ownNamespaces}, or every namespace but the shared ones when null. */
    public static java.util.Map<String, byte[]> synthesizeItemDefinitionEntries(
            java.util.Set<String> entryNames, String targetMcVersion,
            java.util.Set<String> ownNamespaces) {
        // Client item definitions exist from 1.21.4, not just 26.x. 1.21.4 split them out of item
        // models: an item without assets/<ns>/items/<id>.json renders as the missing model, and a
        // pre-1.21.4 mod ships only models/item/<id>.json.
        if (RetromodVersion.mcVersionExceeds("1.21.4", targetMcVersion)) return java.util.Map.of();
        java.util.Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        java.util.Set<String> keptVanilla = new java.util.TreeSet<>();
        // One definition per direct child of models/item/ with no items/<id>.json. A definition for
        // a model that is not really an item is never queried, so writing too many is harmless.
        for (String name : entryNames) {
            if (!name.startsWith("assets/") || !name.endsWith(".json")) continue;
            int m = name.indexOf("/models/item/");
            if (m < 0) continue;
            String ns = name.substring("assets/".length(), m);
            if (!ns.matches("[a-z0-9_.\\-]+")) continue;         // valid Identifier namespace
            String file = name.substring(m + "/models/item/".length());
            if (file.indexOf('/') >= 0) continue;                 // ids have no '/': a parent or template
            String id = file.substring(0, file.length() - ".json".length());
            if (!id.matches("[a-z0-9_.\\-]+")) continue;          // must be a valid Identifier path
            // Never write in the minecraft namespace. Vanilla's own definitions, often a condition
            // or select on the item's state, are replaced rather than merged: a plain one for a
            // mod's models/item/fishing_rod.json stopped every fishing rod from showing its cast model.
            if (MigrationContext.SHARED_NAMESPACES.contains(ns)) {
                if (ns.equals("minecraft")) keptVanilla.add(ns + ":" + id);
                continue;
            }
            // A mod that ships models for another mod's items never shadows that mod's definitions.
            if (ownNamespaces != null && !ownNamespaces.contains(ns)) continue;
            String defName = "assets/" + ns + "/items/" + id + ".json";
            if (entryNames.contains(defName)) continue;           // the mod already ships one
            String json = "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": \""
                    + ns + ":item/" + id + "\"\n  }\n}\n";
            out.put(defName, json.getBytes(StandardCharsets.UTF_8));
        }
        if (!keptVanilla.isEmpty()) {
            LOGGER.info("Kept vanilla's item definitions for {}; the mod's own models for them "
                    + "still apply, but their old overrides are not translated", keptVanilla);
        }
        return out;
    }
}
