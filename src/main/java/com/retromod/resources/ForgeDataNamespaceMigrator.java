/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.retromod.util.JsonSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Moves a Forge mod's data to the names NeoForge reads, and to the names newer Forge reads. */
final class ForgeDataNamespaceMigrator implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    // NeoForge renamed Forge's data when it forked, and never reads the old names. A Forge mod's ores
    // did not generate there, its mobs did not spawn, its loot modifiers never fired, and a recipe it
    // guarded for another mod loaded unguarded. Each version below is the newest loader build of that
    // Minecraft version, checked in the NeoForge and Forge universal jars on both sides of the change.
    // 1.20.2 is NeoForge's first own line. Its 1.20.1 build is a Forge fork that reads Forge's names.
    static final String FIRST_NEOFORGE_NAMES_HOST = "1.20.2";
    /** Forge reads one forge:condition, not the conditions or forge:conditions list 1.20.1 read. */
    static final String FIRST_SINGLE_FORGE_CONDITION_HOST = "1.20.2";
    /** NeoForge renamed {@code can_tool_perform_action} and its {@code action} field. */
    static final String FIRST_ITEM_ABILITY_HOST = "1.21";
    /** NeoForge renamed the {@code item_exists}, {@code true} and {@code false} conditions. */
    static final String FIRST_REGISTERED_CONDITION_HOST = "1.21.4";
    /** NeoForge loads every loot modifier file with no list, and dropped the {@code none} biome modifier. */
    static final String FIRST_LOOT_MODIFIER_FOLDER_HOST = "26.1.2";
    /** NeoForge dropped the {@code none} structure modifier. */
    static final String FIRST_HOST_WITHOUT_NONE_STRUCTURE_MODIFIER = "26.3";
    /** A pre-1.13 Forge mod had no data packs. The 1.12 recipe converter owns its recipe conditions. */
    private static final String FIRST_DATA_PACK_SOURCE = "1.13";

    // A file larger or deeper than this is left as shipped.
    private static final long MAX_JSON_BYTES = 16L * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 256;

    /** The biome modifier types Forge registers, each of which NeoForge registers under its name. */
    private static final Set<String> BIOME_MODIFIER_TYPES = Set.of(
            "add_features", "remove_features", "add_spawns", "remove_spawns");
    /** Forge's holder set types, which NeoForge registers under {@code neoforge:}. */
    private static final Set<String> HOLDER_SET_TYPES = Set.of("any", "and", "or", "not");
    /** Every load condition Forge registers. Any other type belongs to a mod. */
    private static final Set<String> CONDITION_TYPES = Set.of(
            "mod_loaded", "item_exists", "tag_empty", "not", "and", "or", "true", "false");

    // Any folders before data/, so a pack nested in the jar is handled too.
    private static final String ROOT = "((?:[^/]+/)*?)";
    private static final Pattern MODIFIER = Pattern.compile(
            ROOT + "data/([a-z0-9_.-]+)/forge/(biome_modifier|structure_modifier)/(.+\\.json)");
    private static final Pattern LOOT_MODIFIER_LIST = Pattern.compile(
            ROOT + "data/forge/loot_modifiers/global_loot_modifiers\\.json");
    private static final Pattern LOOT_MODIFIER = Pattern.compile(
            ROOT + "data/([a-z0-9_.-]+)/loot_modifiers/(.+)\\.json");
    private static final Pattern FORGE_TAG = Pattern.compile(ROOT
            + "data/forge/tags/(blocks|items|fluids|entity_types|block|item|fluid|entity_type|worldgen/biome)/(.+)\\.json");
    private static final Pattern DATA_JSON = Pattern.compile(ROOT + "data/[a-z0-9_.-]+/.+\\.json");

    /** Each tag folder, by either of its names, to its 1.21 name. */
    private static final Map<String, String> TAG_KIND = Map.of(
            "blocks", "block", "items", "item", "fluids", "fluid", "entity_types", "entity_type",
            "block", "block", "item", "item", "fluid", "fluid", "entity_type", "entity_type",
            "worldgen/biome", "worldgen/biome");
    /** Each 1.21 tag folder to the plural name older hosts read. */
    private static final Map<String, String> PLURAL_TAG_FOLDER = Map.of(
            "block", "blocks", "item", "items", "fluid", "fluids", "entity_type", "entity_types",
            "worldgen/biome", "worldgen/biome");

    @Override
    public String name() {
        return "Forge data namespaces";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        if (context.sourceLoader() != MigrationContext.Loader.FORGE
                || context.sourceBefore(FIRST_DATA_PACK_SOURCE)) {
            return false;
        }
        // A 1.20.1 host of either loader still reads Forge's names, so it needs nothing.
        Host host = Host.of(context);
        return host.neoForge() || host.singleForgeCondition();
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        Host host = Host.of(context);
        int moved = 0;
        // Modifiers and the loot modifier list move, since NeoForge never reads the old folders.
        if (host.neoForge()) {
            moved += moveModifiers(tree, host);
            moved += migrateLootModifierList(tree, host);
        }
        int rewritten = rewriteData(tree, host);
        // Tags are copied, not moved, since the mod's own code still reads its forge: tags.
        int copiedTags = host.tags() ? copyConventionTags(tree, host) : 0;
        if (moved + rewritten + copiedTags > 0) {
            LOGGER.info("Updated Forge data for {} {}: {} moved, {} rewritten, {} c: tag(s) added",
                    context.targetLoader(), context.targetMcVersion(), moved, rewritten, copiedTags);
        }
        return moved + rewritten + copiedTags;
    }

    /** What the host reads, decided once per mod from the loader and version it runs. */
    private record Host(MigrationContext.Loader loader, String targetMcVersion, boolean neoForge,
                        boolean singleForgeCondition, boolean itemAbility, boolean registeredConditions,
                        boolean lootModifierFolder, boolean noneBiomeModifier,
                        boolean noneStructureModifier, boolean spawnCount, boolean tags,
                        boolean singularTagFolders) {

        static Host of(MigrationContext context) {
            MigrationContext.Loader loader = context.targetLoader();
            boolean neoForge = loader == MigrationContext.Loader.NEOFORGE
                    && context.targetAtLeast(FIRST_NEOFORGE_NAMES_HOST);
            return new Host(loader, context.targetMcVersion(), neoForge,
                    loader == MigrationContext.Loader.FORGE
                            && context.targetAtLeast(FIRST_SINGLE_FORGE_CONDITION_HOST),
                    context.targetAtLeast(FIRST_ITEM_ABILITY_HOST),
                    context.targetAtLeast(FIRST_REGISTERED_CONDITION_HOST),
                    context.targetAtLeast(FIRST_LOOT_MODIFIER_FOLDER_HOST),
                    !context.targetAtLeast(FIRST_LOOT_MODIFIER_FOLDER_HOST),
                    !context.targetAtLeast(FIRST_HOST_WITHOUT_NONE_STRUCTURE_MODIFIER),
                    context.targetAtLeast(SpawnerDataMigrator.FIRST_COUNT_HOST),
                    ForgeConventionTags.appliesTo(loader, context.targetMcVersion()),
                    context.targetAtLeast(ModDataLayoutMigrator.FIRST_SINGULAR_FOLDER_HOST));
        }
    }

    // ---------------------------------------------------------------- biome and structure modifiers

    /** Moves each Forge biome and structure modifier the host can load into NeoForge's folder. */
    private static int moveModifiers(ResourceTree tree, Host host) throws IOException {
        int moved = 0;
        for (String name : tree.names()) {
            Matcher match = MODIFIER.matcher(name);
            if (!match.matches() || underAssets(match.group(1))) continue;
            String namespace = match.group(2);
            if (MigrationContext.SHARED_NAMESPACES.contains(namespace)) continue;
            boolean biome = match.group(3).equals("biome_modifier");
            // NeoForge reads modifiers from data/<ns>/neoforge/. Skip one the jar already ships there.
            String destination = match.group(1) + "data/" + namespace + "/neoforge/"
                    + match.group(3) + "/" + match.group(4);
            if (tree.exists(destination)) continue;
            JsonObject modifier = parseObject(tree, name);
            String type = modifier == null ? null : string(modifier.get("type"));
            // One with the mod's own type stays inert: a Forge 1.20.1 modifier class does not fit
            // NeoForge's codec registry.
            String neoForgeType = neoForgeModifierType(type, biome, host);
            if (neoForgeType == null) {
                LOGGER.debug("Left {} inert: NeoForge {} has no modifier type for {}", name,
                        host.targetMcVersion(), type);
                continue;
            }
            String inertReason = activate(modifier, neoForgeType, host);
            if (inertReason != null) {
                LOGGER.debug("Left {} inert on NeoForge {}: {}", name, host.targetMcVersion(), inertReason);
                continue;
            }
            tree.write(destination, json(modifier));
            tree.delete(name);
            moved++;
        }
        return moved;
    }

    /** Gives a modifier NeoForge's type and the shape the host decodes, or says why it stays inert. */
    private static String activate(JsonObject modifier, String neoForgeType, Host host) {
        // Moving it is what makes the host load it, and a modifier that fails to decode stops the
        // world, so one that cannot be made loadable stays in the Forge folder as before.
        if (hasUnconvertibleGuard(modifier)) {
            return "its Forge load conditions name a type NeoForge does not register";
        }
        // On 26.3 add_spawns entries need the one-count shape it reads.
        if (host.spawnCount() && neoForgeType.equals("neoforge:add_spawns")) {
            JsonElement counted = countShapedSpawners(modifier.get("spawners"));
            if (counted == null) return "a spawn entry has no counts that convert to one count";
            modifier.add("spawners", counted);
        }
        modifier.addProperty("type", neoForgeType);
        return null;
    }

    /** Whether the modifier carries a Forge guard the content step must leave under its old key. */
    private static boolean hasUnconvertibleGuard(JsonObject modifier) {
        // NeoForge never reads the old key, so the modifier would run unguarded. Such a guard usually
        // keeps a reference to another mod's feature from being unbound when that mod is missing.
        if (modifier.has("neoforge:conditions")) return false;
        JsonElement list = modifier.get("forge:conditions");
        boolean emptyList = list instanceof JsonArray array && array.isEmpty();
        if (list != null && !emptyList && !isForgeConditionList(list)) return true;
        JsonElement single = modifier.get("forge:condition");
        return single != null && !isForgeCondition(single);
    }

    /** The add_spawns entries with one count each, as 26.3 reads them, or null if one cannot convert. */
    private static JsonElement countShapedSpawners(JsonElement spawners) {
        // A modifier holds one entry or a list of them.
        if (spawners instanceof JsonObject entry) return countShapedEntry(entry);
        if (!(spawners instanceof JsonArray entries)) return null;
        JsonArray converted = new JsonArray();
        for (JsonElement entry : entries) {
            JsonObject counted = entry instanceof JsonObject object ? countShapedEntry(object) : null;
            if (counted == null) return null;
            converted.add(counted);
        }
        return converted;
    }

    /** One entry with count in place of minCount and maxCount, or null when it does not convert. */
    private static JsonObject countShapedEntry(JsonObject entry) {
        // An entry that already has only a count is kept.
        boolean oldCounts = entry.has("minCount") || entry.has("maxCount");
        if (!oldCounts) return entry.has("count") ? entry : null;
        return SpawnerDataMigrator.countShapedEntry(entry);
    }

    /** The NeoForge type for a Forge modifier type, or null when the host would not load it. */
    private static String neoForgeModifierType(String type, boolean biome, Host host) {
        if (type == null || !type.startsWith("forge:")) return null;
        String path = type.substring("forge:".length());
        // NeoForge dropped the none biome modifier in 26.1.2 and the none structure modifier in 26.3.
        if (path.equals("none")) {
            boolean kept = biome ? host.noneBiomeModifier() : host.noneStructureModifier();
            return kept ? "neoforge:none" : null;
        }
        // Otherwise only Forge's own biome modifier types have a NeoForge twin.
        return biome && BIOME_MODIFIER_TYPES.contains(path) ? "neoforge:" + path : null;
    }

    // ---------------------------------------------------------------- global loot modifiers

    /** Moves Forge's global loot modifier list to NeoForge's folder, or from 26.1.2 replaces it. */
    private static int migrateLootModifierList(ResourceTree tree, Host host) throws IOException {
        int changed = 0;
        for (String name : tree.names()) {
            Matcher match = LOOT_MODIFIER_LIST.matcher(name);
            if (!match.matches() || underAssets(match.group(1))) continue;
            String root = match.group(1);
            // Before 26.1.2 NeoForge reads the same list from data/neoforge/loot_modifiers/.
            if (!host.lootModifierFolder()) {
                String destination = root + "data/neoforge/loot_modifiers/global_loot_modifiers.json";
                if (tree.exists(destination)) continue;
                byte[] list;
                try {
                    list = tree.read(name, MAX_JSON_BYTES);
                } catch (IOException tooLarge) {
                    // Too large: left where it was, inert as before.
                    continue;
                }
                tree.write(destination, list);
            } else {
                // The list chose which modifiers ran. The host now runs every file in the folder,
                // so a file the list left out is switched off to keep it from starting to fire.
                changed += disableUnlisted(tree, root, parseObject(tree, name));
            }
            // The old list goes either way. From 26.1.2 it would load as a loot modifier with no type.
            tree.delete(name);
            changed++;
        }
        return changed;
    }

    /** Switches off each loot modifier file the old list did not name, and returns how many. */
    private static int disableUnlisted(ResourceTree tree, String root, JsonObject list)
            throws IOException {
        if (list == null || !(list.get("entries") instanceof JsonArray entries)) return 0;
        Set<String> listed = new HashSet<>();
        for (JsonElement entry : entries) {
            String id = string(entry);
            if (id != null) listed.add(id.contains(":") ? id : "minecraft:" + id);
        }
        int disabled = 0;
        for (String name : tree.names()) {
            Matcher match = LOOT_MODIFIER.matcher(name);
            if (!match.matches() || !match.group(1).equals(root)) continue;
            String namespace = match.group(2);
            if (MigrationContext.SHARED_NAMESPACES.contains(namespace)
                    || listed.contains(namespace + ":" + match.group(3))) {
                continue;
            }
            JsonObject modifier = parseObject(tree, name);
            if (modifier == null) continue;
            // A neoforge:never condition keeps the file loading but stops it from firing.
            JsonArray conditions = modifier.get("neoforge:conditions") instanceof JsonArray existing
                    ? existing : new JsonArray();
            JsonObject never = new JsonObject();
            never.addProperty("type", "neoforge:never");
            conditions.add(never);
            modifier.add("neoforge:conditions", conditions);
            tree.write(name, json(modifier));
            disabled++;
        }
        return disabled;
    }

    // ---------------------------------------------------------------- content

    /** Rewrites conditions, types and tag references in every data file that names a Forge id. */
    private static int rewriteData(ResourceTree tree, Host host) throws IOException {
        int rewritten = 0;
        for (String name : tree.names()) {
            Matcher match = DATA_JSON.matcher(name);
            if (!match.matches() || underAssets(match.group(1))) continue;
            byte[] bytes;
            try {
                bytes = tree.read(name, MAX_JSON_BYTES);
            } catch (IOException tooLarge) {
                // One oversized file stays as shipped.
                continue;
            }
            // Only a file that names a Forge id needs a rewrite.
            if (!new String(bytes, StandardCharsets.UTF_8).contains("forge:")) continue;
            JsonElement original = parse(bytes);
            if (original == null) continue;
            JsonElement migrated = rewrite(original, host, true);
            if (migrated.equals(original)) continue;
            tree.write(name, json(migrated));
            rewritten++;
        }
        return rewritten;
    }

    /** Rewrites one value. Only the file's top level can hold load conditions. */
    private static JsonElement rewrite(JsonElement element, Host host, boolean topLevel) {
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) out.add(rewrite(item, host, false));
            return out;
        }
        // A string may be a #forge: tag reference.
        if (!element.isJsonObject()) {
            String reference = commonTagReference(string(element), host);
            return reference == null ? element : new JsonPrimitive(reference);
        }
        JsonObject object = element.getAsJsonObject();
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> field : object.entrySet()) {
            String key = field.getKey();
            JsonElement value = field.getValue();
            // A top-level load condition moves to the key this host reads.
            String conditionKey = topLevel ? loadConditionKey(key, value, object, out, host) : null;
            if (conditionKey != null) {
                out.add(conditionKey, rewrite(convertLoadConditions(value, host), host, false));
                continue;
            }
            String tagId = namesTag(key, object) ? commonTagId(string(value), host) : null;
            out.add(key, tagId != null ? new JsonPrimitive(tagId) : rewrite(value, host, false));
        }
        // NeoForge also renamed Forge's holder set and loot condition types.
        return host.neoForge() ? renameNeoForgeTypes(out, host) : out;
    }

    /** Fields naming a tag without its #: ingredients, predicates, rule tests and tag loot entries. */
    private static boolean namesTag(String key, JsonObject owner) {
        if (key.equals("tag")) return true;
        String type = string(owner.get("type"));
        return key.equals("name") && ("minecraft:tag".equals(type) || "tag".equals(type));
    }

    /** Holder set and loot condition ids NeoForge registers under its own namespace. */
    private static JsonObject renameNeoForgeTypes(JsonObject object, Host host) {
        String type = string(object.get("type"));
        if (type != null && type.startsWith("forge:")
                && HOLDER_SET_TYPES.contains(type.substring("forge:".length()))) {
            object.addProperty("type", "neoforge:" + type.substring("forge:".length()));
        }
        // From 1.21 NeoForge calls can_tool_perform_action can_item_perform_ability, and its action
        // field ability.
        for (String key : new String[]{"condition", "type"}) {
            String id = string(object.get(key));
            if ("forge:loot_table_id".equals(id)) {
                object.addProperty(key, "neoforge:loot_table_id");
            } else if ("forge:can_tool_perform_action".equals(id)) {
                if (!host.itemAbility()) {
                    object.addProperty(key, "neoforge:can_tool_perform_action");
                } else {
                    object.addProperty(key, "neoforge:can_item_perform_ability");
                    return renamed(object, "action", "ability");
                }
            }
        }
        return object;
    }

    /** A copy of {@code object} with the field {@code from} renamed to {@code to}, in the same place. */
    private static JsonObject renamed(JsonObject object, String from, String to) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> field : object.entrySet()) {
            out.add(field.getKey().equals(from) ? to : field.getKey(), field.getValue());
        }
        return out;
    }

    // ---------------------------------------------------------------- load conditions

    /** The key a top-level load condition field moves to on this host, or null when it stays. */
    private static String loadConditionKey(String key, JsonElement value, JsonObject original,
                                           JsonObject written, Host host) {
        // Only Forge's own conditions convert, because a mod's own Forge condition type is not
        // registered on the host.
        boolean forgeList = (key.equals("conditions") || key.equals("forge:conditions"))
                && isForgeConditionList(value);
        boolean forgeSingle = key.equals("forge:condition") && isForgeCondition(value);
        // NeoForge reads neoforge:conditions and Forge 1.20.2 and newer one forge:condition. A file
        // that already has that key keeps it as shipped.
        String target;
        if (host.neoForge() && (forgeList || forgeSingle)) {
            target = "neoforge:conditions";
        } else if (host.singleForgeCondition() && forgeList) {
            target = "forge:condition";
        } else {
            return null;
        }
        return original.has(target) || written.has(target) ? null : target;
    }

    /** The load conditions in the shape this host reads them. */
    private static JsonElement convertLoadConditions(JsonElement value, Host host) {
        JsonArray conditions = new JsonArray();
        if (value.isJsonArray()) {
            for (JsonElement condition : value.getAsJsonArray()) conditions.add(condition.deepCopy());
        } else {
            conditions.add(value.deepCopy());
        }
        // NeoForge reads a list, with each condition under its names.
        if (host.neoForge()) {
            JsonArray converted = new JsonArray();
            for (JsonElement condition : conditions) {
                converted.add(neoForgeCondition(condition.getAsJsonObject(), host));
            }
            return converted;
        }
        // Forge reads one condition, so several are wrapped in forge:and.
        if (conditions.size() == 1) return conditions.get(0);
        JsonObject all = new JsonObject();
        all.addProperty("type", "forge:and");
        all.add("values", conditions);
        return all;
    }

    /** One Forge condition under NeoForge's names, nested conditions included. */
    private static JsonObject neoForgeCondition(JsonObject condition, Host host) {
        String kind = string(condition.get("type")).substring("forge:".length());
        JsonObject out = new JsonObject();
        out.addProperty("type", "neoforge:" + neoForgeConditionName(kind, host));
        for (Map.Entry<String, JsonElement> field : condition.entrySet()) {
            String key = field.getKey();
            JsonElement value = field.getValue();
            if (key.equals("type")) continue;
            if (kind.equals("not") && key.equals("value")) {
                out.add(key, neoForgeCondition(value.getAsJsonObject(), host));
            } else if ((kind.equals("and") || kind.equals("or")) && key.equals("values")) {
                JsonArray values = new JsonArray();
                for (JsonElement child : value.getAsJsonArray()) {
                    values.add(neoForgeCondition(child.getAsJsonObject(), host));
                }
                out.add(key, values);
            } else if (kind.equals("item_exists") && key.equals("item") && host.registeredConditions()) {
                // registered reads its id from value, and defaults to the item registry.
                out.add("value", value);
            } else {
                out.add(key, value);
            }
        }
        return out;
    }

    /** The NeoForge name of a Forge condition. 1.21.4 renamed item_exists, true and false. */
    private static String neoForgeConditionName(String forgeKind, Host host) {
        if (!host.registeredConditions()) return forgeKind;
        return switch (forgeKind) {
            case "item_exists" -> "registered";
            case "true" -> "always";
            case "false" -> "never";
            default -> forgeKind;
        };
    }

    /** A non-empty list made only of Forge's own conditions. */
    private static boolean isForgeConditionList(JsonElement value) {
        if (!value.isJsonArray() || value.getAsJsonArray().isEmpty()) return false;
        for (JsonElement condition : value.getAsJsonArray()) {
            if (!isForgeCondition(condition)) return false;
        }
        return true;
    }

    /** One of Forge's own conditions, with any nested ones Forge's own too. */
    private static boolean isForgeCondition(JsonElement element) {
        if (!element.isJsonObject()) return false;
        JsonObject condition = element.getAsJsonObject();
        String type = string(condition.get("type"));
        if (type == null || !type.startsWith("forge:")) return false;
        String kind = type.substring("forge:".length());
        if (!CONDITION_TYPES.contains(kind)) return false;
        if (kind.equals("not")) return isForgeCondition(nonNull(condition.get("value")));
        if (kind.equals("and") || kind.equals("or")) return isForgeConditionList(nonNull(condition.get("values")));
        return true;
    }

    // ---------------------------------------------------------------- convention tags

    // NeoForge 1.20.5 and Forge 26.1 replaced the forge: convention tags with c: ones, which
    // ForgeConventionTags maps.

    /** {@code #forge:<path>} as the host's {@code #c:} reference, or null when it keeps its name. */
    private static String commonTagReference(String value, Host host) {
        if (value == null || !value.startsWith("#forge:")) return null;
        String id = commonTagId(value.substring(1), host);
        return id == null ? null : "#" + id;
    }

    /** {@code forge:<path>} as the host's {@code c:} id, or null when it keeps its name. */
    private static String commonTagId(String value, Host host) {
        if (!host.tags() || value == null || !value.startsWith("forge:")) return null;
        String path = ForgeConventionTags.commonPath(value.substring("forge:".length()),
                host.loader(), host.targetMcVersion());
        return path == null ? null : "c:" + path;
    }

    /** Adds the mod's entries in each mapped {@code forge:} tag to the {@code c:} tag that replaced it. */
    private static int copyConventionTags(ResourceTree tree, Host host) throws IOException {
        // Several forge: files can map to one c: tag, so their entries merge before anything is written.
        Map<String, TagCopy> copies = new LinkedHashMap<>();
        for (String name : tree.names()) {
            Matcher match = FORGE_TAG.matcher(name);
            if (!match.matches() || underAssets(match.group(1))) continue;
            String commonPath = ForgeConventionTags.commonPath(match.group(3), host.loader(),
                    host.targetMcVersion());
            if (commonPath == null) continue;
            String kind = TAG_KIND.get(match.group(2));
            // Hosts before 1.21 read plural tag folders. A c: tag the jar ships itself wins.
            String folder = host.singularTagFolders() ? kind : PLURAL_TAG_FOLDER.get(kind);
            String destination = match.group(1) + "data/c/tags/" + folder + "/" + commonPath + ".json";
            if (tree.exists(destination)) continue;
            JsonObject tag = parseObject(tree, name);
            if (tag == null) continue;
            copies.computeIfAbsent(destination, ignored -> new TagCopy("#c:" + commonPath))
                    .add(tag, host);
        }
        int written = 0;
        for (Map.Entry<String, TagCopy> copy : copies.entrySet()) {
            JsonObject tag = copy.getValue().toJson();
            if (tag == null) continue;
            tree.write(copy.getKey(), json(tag));
            written++;
        }
        return written;
    }

    /** The merged entries of every {@code forge:} tag file that maps to one {@code c:} tag. */
    private static final class TagCopy {
        private final String self;
        private final Map<String, JsonObject> values = new LinkedHashMap<>();
        private final Map<String, JsonObject> removed = new LinkedHashMap<>();

        TagCopy(String self) {
            this.self = self;
        }

        void add(JsonObject tag, Host host) {
            addEntries(tag.get("values"), values, host);
            addEntries(tag.get("remove"), removed, host);
        }

        private void addEntries(JsonElement list, Map<String, JsonObject> into, Host host) {
            if (list == null || !list.isJsonArray()) return;
            for (JsonElement entry : list.getAsJsonArray()) {
                String id = entry.isJsonObject() ? string(entry.getAsJsonObject().get("id")) : string(entry);
                if (id == null) continue;
                String reference = commonTagReference(id, host);
                if (reference != null) id = reference;
                // A tag that names itself fails to load.
                if (id.equals(self)) continue;
                // Every entry is optional, because the c: tag is shared and one missing entry
                // would fail it for every mod.
                JsonObject optional = new JsonObject();
                optional.addProperty("id", id);
                optional.addProperty("required", false);
                into.putIfAbsent(id, optional);
            }
        }

        JsonObject toJson() {
            if (values.isEmpty() && removed.isEmpty()) return null;
            // No replace field: the c: tag is shared, and a replace would empty it for every other mod.
            JsonObject tag = new JsonObject();
            JsonArray valueArray = new JsonArray();
            values.values().forEach(valueArray::add);
            tag.add("values", valueArray);
            if (!removed.isEmpty()) {
                JsonArray removeArray = new JsonArray();
                removed.values().forEach(removeArray::add);
                tag.add("remove", removeArray);
            }
            return tag;
        }
    }

    // ---------------------------------------------------------------- JSON helpers

    /** Whether a pack root sits inside an {@code assets/} folder, where nothing is data. */
    private static boolean underAssets(String root) {
        for (String segment : root.split("/")) {
            if (segment.equals("assets")) return true;
        }
        return false;
    }

    /** The file's JSON object, or null when it is unreadable or not an object. */
    private static JsonObject parseObject(ResourceTree tree, String name) {
        try {
            JsonElement element = parse(tree.read(name, MAX_JSON_BYTES));
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The JSON in a mod file as a strict host reads it, or null, which leaves the file as shipped. */
    private static JsonElement parse(byte[] bytes) {
        try {
            // Strip comments and trailing commas first, then read strictly, as the host does.
            byte[] normalized = ModDataMigrator.normalizeLenientJson(bytes);
            JsonSecurity.validate(normalized, MAX_JSON_BYTES, MAX_JSON_DEPTH, "Forge data JSON");
            String text = new String(normalized, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(false);
            JsonElement root = GSON.getAdapter(JsonElement.class).read(reader);
            return reader.peek() == JsonToken.END_DOCUMENT ? root : null;
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    private static byte[] json(JsonElement element) {
        return (GSON.toJson(element) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String string(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString() : null;
    }

    private static JsonElement nonNull(JsonElement element) {
        return element == null ? com.google.gson.JsonNull.INSTANCE : element;
    }
}
