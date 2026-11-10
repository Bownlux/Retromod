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
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.retromod.util.JsonSecurity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rewrites worldgen features, carvers and biome feature order that 1.21.11 to 26.3 changed into the shapes the host reads. */
final class LegacyWorldgenFeatures implements ModResourceConverter {

    // The first host with each change, read from the worldgen data in the 1.21.11, 26.1.2, 26.2 and
    // 26.3 client jars. Each rule writes what vanilla's own data generator wrote for the same change.
    static final String FIRST_PLACEMENT_TAG_HOST = "26.1";
    // The 1.21.11 jar is the first in reach whose biomes place pumpkin patches before sugar cane.
    static final String FIRST_PUMPKIN_FIRST_HOST = "1.21.11";
    static final String FIRST_SPELEOTHEM_HOST = "26.2";
    static final String FIRST_REBUILT_CARVER_HOST = "26.3";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 256;

    // The tag each placement rule became, checked against the 26.1.2 tag contents: the old
    // hardcoded checks and the new tags allow the same blocks.
    private static final Map<String, String> PLACEMENT_TAGS = Map.of(
            "minecraft:forest_rock", "minecraft:forest_rock_can_place_on",
            "minecraft:huge_brown_mushroom", "minecraft:huge_brown_mushroom_can_place_on",
            "minecraft:huge_red_mushroom", "minecraft:huge_red_mushroom_can_place_on");

    private static final Map<String, String> SPELEOTHEM_CLUSTER_FIELDS = Map.of(
            "chance_of_dripstone_column_at_max_distance_from_center",
            "chance_of_speleothem_at_max_distance_from_center",
            "dripstone_block_layer_thickness", "speleothem_block_layer_thickness",
            "max_distance_from_edge_affecting_chance_of_dripstone_column",
            "max_distance_from_edge_affecting_chance_of_speleothem");

    private static final String DRIPSTONE_REPLACEABLE = "#minecraft:dripstone_replaceable_blocks";
    // A vanilla block tag mods still name after it became wool_carpets; the 1.20.1 jar no longer has it.
    private static final String STALE_BLOCK_TAG = "minecraft:carpets";
    private static final String STALE_BLOCK_TAG_FILE = "data/minecraft/tags/block/carpets.json";
    private static final String STALE_TAG_GONE_BY = "1.20";
    /** What a vanilla patch feature placed on, beside needing air. */
    private enum Ground { ANY, GRASS_BLOCK, NOT_PODZOL }

    /** One vanilla patch feature 26.1 removed: the feature it placed, now named on its own, and its old patch. */
    private record VanillaPatch(String feature, int tries, int xzSpread, int ySpread, Ground ground) {
    }

    // 26.1 renamed these configured features to the single placement they patched, and moved the
    // patch into vanilla's placed features. Tries, spreads and placement checks were read from the
    // 1.21.11 jar's own patch features; cactus, melon and sugar cane checked more and are not listed.
    private static final Map<String, VanillaPatch> VANILLA_PATCHES = Map.ofEntries(
            Map.entry("minecraft:patch_berry_bush", new VanillaPatch("minecraft:berry_bush", 96, 7, 3, Ground.GRASS_BLOCK)),
            Map.entry("minecraft:patch_brown_mushroom", new VanillaPatch("minecraft:brown_mushroom", 96, 7, 3, Ground.ANY)),
            Map.entry("minecraft:patch_bush", new VanillaPatch("minecraft:bush", 24, 5, 3, Ground.ANY)),
            Map.entry("minecraft:patch_crimson_roots", new VanillaPatch("minecraft:crimson_roots", 96, 7, 3, Ground.ANY)),
            Map.entry("minecraft:patch_dead_bush", new VanillaPatch("minecraft:dead_bush", 4, 7, 3, Ground.ANY)),
            Map.entry("minecraft:patch_dry_grass", new VanillaPatch("minecraft:dry_grass", 64, 7, 3, Ground.ANY)),
            Map.entry("minecraft:patch_firefly_bush", new VanillaPatch("minecraft:firefly_bush", 20, 4, 3, Ground.ANY)),
            Map.entry("minecraft:patch_grass", new VanillaPatch("minecraft:grass", 32, 7, 3, Ground.ANY)),
            Map.entry("minecraft:patch_grass_jungle", new VanillaPatch("minecraft:grass_jungle", 32, 7, 3, Ground.NOT_PODZOL)),
            Map.entry("minecraft:patch_large_fern", new VanillaPatch("minecraft:large_fern", 96, 7, 3, Ground.ANY)),
            Map.entry("minecraft:patch_leaf_litter", new VanillaPatch("minecraft:leaf_litter", 32, 7, 3, Ground.GRASS_BLOCK)),
            Map.entry("minecraft:patch_pumpkin", new VanillaPatch("minecraft:pumpkin", 96, 7, 3, Ground.GRASS_BLOCK)),
            Map.entry("minecraft:patch_red_mushroom", new VanillaPatch("minecraft:red_mushroom", 96, 7, 3, Ground.ANY)),
            Map.entry("minecraft:patch_sunflower", new VanillaPatch("minecraft:sunflower", 96, 7, 3, Ground.ANY)),
            Map.entry("minecraft:patch_taiga_grass", new VanillaPatch("minecraft:taiga_grass", 32, 7, 3, Ground.ANY)),
            Map.entry("minecraft:patch_tall_grass", new VanillaPatch("minecraft:tall_grass", 96, 7, 3, Ground.ANY)),
            Map.entry("minecraft:patch_waterlily", new VanillaPatch("minecraft:waterlily", 10, 7, 3, Ground.ANY)));

    /** Fields every carver read before 26.3 and that 26.3 no longer has. */
    private static final Set<String> REMOVED_CARVER_FIELDS = Set.of("yScale", "lava_level", "replaceable",
            "debug_settings");

    @Override
    public String name() {
        return "legacy worldgen features";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.targetAtLeast(FIRST_PUMPKIN_FIRST_HOST)
                && context.sourceBefore(FIRST_REBUILT_CARVER_HOST);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        int changed = 0;
        boolean namesStaleTag = false;
        // Every biome the jar ships, in its base data or in a pack overlay, for the surface rule check.
        Set<String> shippedBiomes = new java.util.HashSet<>();
        for (String name : tree.names()) {
            String path = ModDataMigrator.dataPath(name);
            if (path.contains("/worldgen/biome/")) shippedBiomes.add(path);
        }
        for (String name : tree.names()) {
            if (!isWorldgen(name)) continue;
            byte[] before = tree.read(name, MAX_FILE_BYTES);
            namesStaleTag |= new String(before, StandardCharsets.UTF_8).contains("\"#" + STALE_BLOCK_TAG + "\"");
            JsonElement root = parse(before);
            if (root == null) continue; // a malformed file is left for the steps after this one
            Rewriter rewriter = new Rewriter(tree, context, ModDataMigrator.dataPath(name), shippedBiomes);
            JsonElement after = rewriter.rewrite(root);
            if (!rewriter.changed) continue;
            tree.write(name, GSON.toJson(after).getBytes(StandardCharsets.UTF_8));
            changed++;
        }
        if (namesStaleTag && keepsStaleTagEmpty(context) && !tree.exists(STALE_BLOCK_TAG_FILE)) {
            tree.write(STALE_BLOCK_TAG_FILE, "{\"values\":[]}".getBytes(StandardCharsets.UTF_8));
            changed++;
        }
        return changed;
    }

    /**
     * Whether the mod was built after vanilla dropped the carpets block tag. Its own version then read
     * the tag as empty, and 26.3 rejects a worldgen file that names a missing tag.
     */
    private static boolean keepsStaleTagEmpty(MigrationContext context) {
        String source = context.sourceMcVersion();
        return context.targetAtLeast(FIRST_REBUILT_CARVER_HOST) && source != null
                && source.matches("\\d+\\.\\d+.*") && !context.sourceBefore(STALE_TAG_GONE_BY);
    }

    /** Worldgen JSON in the jar's data, or in a pack overlay's data that a later host may apply. */
    static boolean isWorldgen(String name) {
        String path = ModDataMigrator.dataPath(name);
        return path.startsWith("data/") && path.contains("/worldgen/") && path.endsWith(".json");
    }

    private static JsonElement parse(byte[] json) {
        try {
            String text = new String(json, StandardCharsets.UTF_8);
            JsonSecurity.validate(text, MAX_FILE_BYTES, MAX_JSON_DEPTH, "Worldgen JSON");
            return JsonParser.parseString(text);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** One file's rewrite, with the rules its source and target versions allow. */
    private static final class Rewriter {
        private final ResourceTree tree;
        private final MigrationContext context;
        private final boolean noiseSettings;
        private final boolean biome;
        private final Set<String> shippedBiomes;
        boolean changed;

        Rewriter(ResourceTree tree, MigrationContext context, String path, Set<String> shippedBiomes) {
            this.tree = tree;
            this.context = context;
            this.noiseSettings = path.contains("/worldgen/noise_settings/");
            this.biome = path.contains("/worldgen/biome/");
            this.shippedBiomes = shippedBiomes;
        }

        /** Whether data from the mod's version still needs the change {@code version} made. */
        private boolean changedIn(String version) {
            return context.targetAtLeast(version) && context.sourceBefore(version);
        }

        JsonElement rewrite(JsonElement element) {
            if (element instanceof JsonArray array) {
                for (int i = 0; i < array.size(); i++) array.set(i, rewrite(array.get(i)));
                return array;
            }
            if (!(element instanceof JsonObject object)) return element;
            // Children first, so a rule sees nested features in their final shape.
            for (Map.Entry<String, JsonElement> field : object.entrySet()) field.setValue(rewrite(field.getValue()));
            if (changedIn(FIRST_PLACEMENT_TAG_HOST)) vanillaPatchReference(object);
            if (changedIn(FIRST_REBUILT_CARVER_HOST)) dropIgnoredStateKeys(object);
            if (noiseSettings && changedIn(FIRST_REBUILT_CARVER_HOST)) dropMissingOwnBiomes(object);
            if (biome && changedIn(FIRST_PUMPKIN_FIRST_HOST)) pumpkinBeforeSugarCane(object);
            String type = object.get("type") instanceof JsonPrimitive primitive && primitive.isString()
                    ? namespaced(primitive.getAsString()) : null;
            // Only the target keeps 1.21.11 hosts out here; each rule below checks the mod's version itself.
            if (type == null || !(object.get("config") instanceof JsonObject config)
                    || !context.targetAtLeast(FIRST_PLACEMENT_TAG_HOST)) return object;
            // An old configured feature or carver read only its type and config, so a stray key
            // beside them was ignored. Removing it lets the 26.3 rewrite unwrap the config. A
            // namespaced key, such as neoforge:conditions, is read by the loader and stays.
            for (String key : new ArrayList<>(object.keySet())) {
                if (!key.equals("type") && !key.equals("config") && !key.contains(":")) {
                    object.remove(key);
                    changed = true;
                }
            }
            feature(object, type, config);
            return object;
        }

        private void feature(JsonObject feature, String type, JsonObject config) {
            if (changedIn(FIRST_PLACEMENT_TAG_HOST)) {
                // 26.1 moved forest rocks and huge mushrooms' placement checks into tags.
                if (PLACEMENT_TAGS.containsKey(type) && !config.has("can_place_on")) {
                    config.add("can_place_on", blockTag(PLACEMENT_TAGS.get(type)));
                    changed = true;
                }
                if (type.equals("minecraft:forest_rock")) setType(feature, "minecraft:block_blob");
                // 26.1 reads a multiface growth's block every time; before, it defaulted to glow lichen.
                if (type.equals("minecraft:multiface_growth") && !config.has("block")) {
                    config.addProperty("block", "minecraft:glow_lichen");
                    changed = true;
                }
            }
            if (changedIn(FIRST_SPELEOTHEM_HOST)) speleothems(feature, type, config);
            if (changedIn(FIRST_REBUILT_CARVER_HOST)) carver(feature, type, config);
        }

        /**
         * Rewrites a placed feature that names a vanilla patch feature 26.1 removed. The patch comes back
         * inline, in the random_patch shape the later rewrite turns into its 26.1 form, around the
         * feature that patch placed, unless the jar ships a feature under the old id itself.
         */
        private void vanillaPatchReference(JsonObject placed) {
            if (!placed.has("placement") || !(placed.get("feature") instanceof JsonPrimitive id) || !id.isString()) return;
            VanillaPatch patch = VANILLA_PATCHES.get(namespaced(id.getAsString()));
            if (patch == null) return;
            String path = namespaced(id.getAsString()).substring("minecraft:".length());
            if (tree.exists("data/minecraft/worldgen/configured_feature/" + path + ".json")
                    || tree.exists("data/minecraft/worldgen/feature/" + path + ".json")) return;
            JsonObject inner = new JsonObject();
            inner.addProperty("feature", patch.feature());
            JsonArray placement = new JsonArray();
            JsonObject filter = new JsonObject();
            filter.addProperty("type", "minecraft:block_predicate_filter");
            filter.add("predicate", groundCheck(patch.ground()));
            placement.add(filter);
            inner.add("placement", placement);
            JsonObject config = new JsonObject();
            config.addProperty("tries", patch.tries());
            config.addProperty("xz_spread", patch.xzSpread());
            config.addProperty("y_spread", patch.ySpread());
            config.add("feature", inner);
            JsonObject randomPatch = new JsonObject();
            randomPatch.addProperty("type", "minecraft:random_patch");
            randomPatch.add("config", config);
            placed.add("feature", randomPatch);
            changed = true;
        }

        private void speleothems(JsonObject feature, String type, JsonObject config) {
            switch (type) {
                case "minecraft:dripstone_cluster" -> {
                    // 26.2 made dripstone one kind of speleothem and named its blocks in the config.
                    setType(feature, "minecraft:speleothem_cluster");
                    for (Map.Entry<String, String> field : SPELEOTHEM_CLUSTER_FIELDS.entrySet()) rename(config, field.getKey(), field.getValue());
                    addDripstoneBlocks(config);
                }
                case "minecraft:pointed_dripstone" -> {
                    setType(feature, "minecraft:speleothem");
                    rename(config, "chance_of_taller_dripstone", "chance_of_taller_generation");
                    addDripstoneBlocks(config);
                }
                case "minecraft:large_dripstone" -> addIfAbsent(config, "replaceable_blocks", new JsonPrimitive(DRIPSTONE_REPLACEABLE));
                case "minecraft:root_system" -> {
                    // Vanilla's own root systems write 0 for both, which keeps the old level check.
                    addIfAbsent(config, "level_test_distance", new JsonPrimitive(0));
                    addIfAbsent(config, "max_level_deviation", new JsonPrimitive(0));
                }
                default -> {
                }
            }
        }

        /** The dripstone blocks that were hardcoded before 26.2, in the state shape the later steps update. */
        private void addDripstoneBlocks(JsonObject config) {
            JsonObject base = new JsonObject();
            base.addProperty("Name", "minecraft:dripstone_block");
            JsonObject pointed = new JsonObject();
            pointed.addProperty("Name", "minecraft:pointed_dripstone");
            JsonObject properties = new JsonObject();
            properties.addProperty("thickness", "tip");
            properties.addProperty("vertical_direction", "up");
            properties.addProperty("waterlogged", "false");
            pointed.add("Properties", properties);
            addIfAbsent(config, "base_block", base);
            addIfAbsent(config, "pointed_block", pointed);
            addIfAbsent(config, "replaceable_blocks", new JsonPrimitive(DRIPSTONE_REPLACEABLE));
        }

        private void carver(JsonObject carver, String type, JsonObject config) {
            // Only an old configured carver, which always had a yScale, is rewritten.
            if (!config.has("probability") || !config.has("yScale")) return;
            switch (type) {
                case "minecraft:cave", "minecraft:nether_cave" -> {
                    // 26.3 made the counts, thickness and vertical scale that each carver class
                    // hardcoded into fields, and merged the nether cave into the cave.
                    boolean nether = type.equals("minecraft:nether_cave");
                    config.add("room_vertical_radius_multiplier", config.get("yScale"));
                    addIfAbsent(config, "count", veryBiasedToBottom(nether ? 9 : 14));
                    addIfAbsent(config, "thickness", trapezoid(nether ? 6.0 : 3.0, nether ? 2.0 : 1.0));
                    if (nether) {
                        addIfAbsent(config, "start_vertical_radius_multiplier", new JsonPrimitive(5.0));
                    } else {
                        addIfAbsent(config, "weird_thickness_bias", new JsonPrimitive(true));
                    }
                    setType(carver, "minecraft:cave");
                }
                case "minecraft:canyon" -> {
                    // A canyon's vertical scale moved into its shape.
                    if (config.get("shape") instanceof JsonObject shape) addIfAbsent(shape, "y_scale", config.get("yScale"));
                }
                default -> {
                    return;
                }
            }
            for (String removed : REMOVED_CARVER_FIELDS) config.remove(removed);
            changed = true;
        }

        /**
         * Keeps only {@code Name} and {@code Properties} of an old block state. The old codec ignored any
         * other key, such as a misspelled {@code Properites}, which 26.3's rewrite would not recognize.
         */
        private void dropIgnoredStateKeys(JsonObject object) {
            if (!(object.get("Name") instanceof JsonPrimitive name) || !name.isString()) return;
            List<String> ignored = new ArrayList<>();
            for (String key : object.keySet()) {
                if (key.equals("Name") || key.equals("Properties")) continue;
                if (key.isEmpty() || !Character.isUpperCase(key.charAt(0))) return; // not a block state
                ignored.add(key);
            }
            for (String key : ignored) object.remove(key);
            if (!ignored.isEmpty()) changed = true;
        }

        /**
         * Removes biomes under the mod's own declared ids that the jar does not ship from a surface rule's
         * {@code biome_is}. Before 26.3 the rule named biomes by key and an unknown one matched nothing;
         * 26.3 binds them, and one missing biome stops the world from loading.
         */
        private void dropMissingOwnBiomes(JsonObject condition) {
            if (!"minecraft:biome".equals(namespacedType(condition))
                    || !(condition.get("biome_is") instanceof JsonArray biomes)) return;
            JsonArray kept = new JsonArray();
            for (JsonElement biome : biomes) {
                if (!(biome instanceof JsonPrimitive id && id.isString() && isMissingOwnBiome(id.getAsString()))) {
                    kept.add(biome);
                }
            }
            if (kept.size() == biomes.size() || kept.isEmpty()) return; // an empty list is left as shipped
            condition.add("biome_is", kept);
            changed = true;
        }

        private boolean isMissingOwnBiome(String id) {
            int colon = id.indexOf(':');
            if (colon <= 0) return false;
            // Only the mod's declared ids count: a jar can ship data for another mod that may be installed.
            String namespace = id.substring(0, colon);
            if (!context.declaredNamespaces().contains(namespace)) return false;
            return !shippedBiomes.contains("data/" + namespace + "/worldgen/biome/" + id.substring(colon + 1) + ".json");
        }

        /**
         * Swaps a biome's sugar cane patch and the pumpkin patch right after it, as vanilla's own biomes
         * did. The game sorts features across all biomes and stops world creation on an order that
         * contradicts itself, so one old biome beside the host's vanilla biomes is enough to stop it.
         * Only a pair that sits side by side is swapped, which keeps every other order as it was.
         */
        private void pumpkinBeforeSugarCane(JsonObject biomeData) {
            if (!(biomeData.get("features") instanceof JsonArray steps)) return;
            for (JsonElement step : steps) {
                if (!(step instanceof JsonArray features)) continue;
                for (int i = 0; i + 1 < features.size(); i++) {
                    if (isFeature(features.get(i), "minecraft:patch_sugar_cane")
                            && isFeature(features.get(i + 1), "minecraft:patch_pumpkin")) {
                        JsonElement sugarCane = features.get(i);
                        features.set(i, features.get(i + 1));
                        features.set(i + 1, sugarCane);
                        changed = true;
                    }
                }
            }
        }

        private static boolean isFeature(JsonElement entry, String id) {
            return entry instanceof JsonPrimitive primitive && primitive.isString()
                    && namespaced(primitive.getAsString()).equals(id);
        }

        private void setType(JsonObject object, String type) {
            object.addProperty("type", type);
            changed = true;
        }

        private void rename(JsonObject object, String from, String to) {
            if (!object.has(from) || object.has(to)) return;
            object.add(to, object.remove(from));
            changed = true;
        }

        private void addIfAbsent(JsonObject object, String key, JsonElement value) {
            if (object.has(key)) return;
            object.add(key, value);
            changed = true;
        }
    }

    /** The old patch's check for each position: air, and for some patches the block below. */
    private static JsonObject groundCheck(Ground ground) {
        JsonObject air = matchingBlock("minecraft:air", false);
        if (ground == Ground.ANY) return air;
        JsonObject below = matchingBlock(ground == Ground.GRASS_BLOCK ? "minecraft:grass_block" : "minecraft:podzol", true);
        if (ground == Ground.NOT_PODZOL) {
            JsonObject not = new JsonObject();
            not.addProperty("type", "minecraft:not");
            not.add("predicate", below);
            below = not;
        }
        JsonArray both = new JsonArray();
        both.add(air);
        both.add(below);
        JsonObject allOf = new JsonObject();
        allOf.addProperty("type", "minecraft:all_of");
        allOf.add("predicates", both);
        return allOf;
    }

    private static JsonObject matchingBlock(String block, boolean below) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty("type", "minecraft:matching_blocks");
        predicate.addProperty("blocks", block);
        if (below) {
            JsonArray offset = new JsonArray();
            offset.add(0);
            offset.add(-1);
            offset.add(0);
            predicate.add("offset", offset);
        }
        return predicate;
    }

    private static JsonObject blockTag(String tag) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty("type", "minecraft:matching_block_tag");
        predicate.addProperty("tag", tag);
        return predicate;
    }

    /** The old cave bound: a count from 0 to {@code max}, biased to the low end three times over. */
    private static JsonObject veryBiasedToBottom(int max) {
        JsonObject count = new JsonObject();
        count.addProperty("type", "minecraft:very_biased_to_bottom");
        count.addProperty("min_inclusive", 0);
        count.addProperty("max_inclusive", max);
        return count;
    }

    private static JsonObject trapezoid(double max, double plateau) {
        JsonObject thickness = new JsonObject();
        thickness.addProperty("type", "minecraft:trapezoid");
        thickness.addProperty("min", 0.0);
        thickness.addProperty("max", max);
        thickness.addProperty("plateau", plateau);
        return thickness;
    }

    private static String namespacedType(JsonObject object) {
        return object.get("type") instanceof JsonPrimitive primitive && primitive.isString()
                ? namespaced(primitive.getAsString()) : null;
    }

    private static String namespaced(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }
}
