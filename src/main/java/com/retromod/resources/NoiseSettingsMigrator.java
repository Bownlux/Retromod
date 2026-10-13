/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.retromod.core.RetromodVersion;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites a mod's noise settings and density functions for the terrain format of the target.
 *
 * <p>Three changes broke old terrain data. 1.21.9 replaced the noise router's
 * {@code initial_density_without_jaggedness} with {@code preliminary_surface_level}, a height found
 * by scanning a density down from the top. 26.2 removed {@code weird_scaled_sampler}. 26.3 rebuilt
 * the system: density functions name their inputs {@code input}, {@code left}, and {@code right},
 * the caches merged into one, interpolation states its cell size, the surface rule became the
 * {@code material_rule}, aquifers left the router for their own object, and ore veins became
 * material rules. One old mod dimension then failed to parse, which stops the registry load and
 * every world with it.
 *
 * <p>Each old piece becomes the new piece that computes the same thing. Two parts are close rather
 * than exact. Ore veins use vanilla's copper and iron vein rules instead of the mod's own vein
 * functions, which nearly every mod copied from vanilla anyway. A density function file outside
 * noise settings interpolates over the overworld cell size, because its cell size used to come from
 * whichever settings read it.
 */
public final class NoiseSettingsMigrator {

    /** The density the old generator compared against when it looked for the surface. */
    private static final double OLD_SURFACE_THRESHOLD = 0.390625;
    private static final Cells OVERWORLD_CELLS = new Cells(4, 8);

    private static final Set<String> BINARY = Set.of("minecraft:add", "minecraft:mul", "minecraft:min", "minecraft:max");
    private static final Set<String> UNARY = Set.of("minecraft:abs", "minecraft:square", "minecraft:cube",
            "minecraft:half_negative", "minecraft:quarter_negative", "minecraft:squeeze", "minecraft:blend_density");
    private static final Set<String> OLD_CACHES = Set.of("minecraft:flat_cache", "minecraft:cache_2d",
            "minecraft:cache_once", "minecraft:cache_all_in_cell");
    private static final Set<String> SHIFTS = Set.of("minecraft:shift_a", "minecraft:shift_b", "minecraft:shift");

    /** Old climate parameter, and the router function that supplied it. */
    private static final Map<String, String> SPAWN_PARAMETERS = Map.of(
            "temperature", "temperature", "humidity", "vegetation", "continentalness", "continents",
            "erosion", "erosion", "weirdness", "ridges");
    private static final List<String> CLIMATE_ROUTER = List.of(
            "temperature", "vegetation", "continents", "erosion", "depth", "ridges", "final_density");
    private static final List<String> AQUIFER_ROUTER = List.of(
            "barrier", "fluid_level_floodedness", "fluid_level_spread", "lava");

    private NoiseSettingsMigrator() {}

    /** Interpolation cell size in blocks: four times the old {@code size_horizontal} and {@code size_vertical}. */
    private record Cells(int xz, int y) {}

    static boolean isTerrainData(String entryName) {
        if (!entryName.startsWith("data/") || !entryName.endsWith(".json")) return false;
        return entryName.contains("/worldgen/noise_settings/") || entryName.contains("/worldgen/density_function/")
                || entryName.contains("/worldgen/world_preset/") || entryName.contains("/dimension/");
    }

    /** The file rewritten for the target, or the same array when nothing changed. */
    static byte[] migrate(String entryName, byte[] json, String targetMcVersion) {
        JsonElement before;
        try {
            before = com.google.gson.JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        } catch (RuntimeException unparsable) {
            return json;
        }
        JsonElement after = migrate(entryName, before.deepCopy(), targetMcVersion);
        if (after.equals(before)) return json;
        return new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(after)
                .getBytes(StandardCharsets.UTF_8);
    }

    static JsonElement migrate(String entryName, JsonElement file, String targetMcVersion) {
        if (entryName.contains("/worldgen/density_function/")) {
            return densityFunction(file, OVERWORLD_CELLS, targetMcVersion);
        }
        if (!(file instanceof JsonObject object)) return file;
        if (entryName.contains("/worldgen/noise_settings/")) return settings(object, targetMcVersion);
        if (entryName.contains("/worldgen/world_preset/")) {
            if (object.get("dimensions") instanceof JsonObject dimensions) {
                for (Map.Entry<String, JsonElement> dimension : dimensions.entrySet()) {
                    if (dimension.getValue() instanceof JsonObject stem) migrateGenerator(stem, targetMcVersion);
                }
            }
            return object;
        }
        migrateGenerator(object, targetMcVersion);
        return object;
    }

    private static void migrateGenerator(JsonObject dimension, String targetMcVersion) {
        if (dimension.get("generator") instanceof JsonObject generator
                && generator.get("settings") instanceof JsonObject settings) {
            generator.add("settings", settings(settings, targetMcVersion));
        }
    }

    /** Noise settings in the target's shape. */
    static JsonObject settings(JsonObject settings, String targetMcVersion) {
        if (settings.has("material_rule") || !(settings.get("noise_router") instanceof JsonObject router)) {
            return settings;
        }
        Cells cells = cells(settings.get("noise"));
        for (Map.Entry<String, JsonElement> function : router.entrySet()) {
            function.setValue(legacyFunction(function.getValue(), targetMcVersion));
        }
        if (atLeast(targetMcVersion, "1.21.9") && router.has("initial_density_without_jaggedness")
                && !router.has("preliminary_surface_level")) {
            router.add("preliminary_surface_level",
                    surfaceLevel(router.remove("initial_density_without_jaggedness"), settings.get("noise"), cells));
        }
        return atLeast(targetMcVersion, "26.3") ? materialSystemSettings(settings, router, cells) : settings;
    }

    /** A density function in the target's shape. */
    static JsonElement densityFunction(JsonElement function, String targetMcVersion) {
        return densityFunction(function, OVERWORLD_CELLS, targetMcVersion);
    }

    private static JsonElement densityFunction(JsonElement function, Cells cells, String targetMcVersion) {
        JsonElement legacy = legacyFunction(function, targetMcVersion);
        return atLeast(targetMcVersion, "26.3") ? modernFunction(legacy, cells) : legacy;
    }

    private static JsonElement legacyFunction(JsonElement function, String targetMcVersion) {
        return atLeast(targetMcVersion, "26.2") ? withoutWeirdSampler(function) : function;
    }

    /**
     * The old surface scan as a {@code find_top_surface}: from the top of the world down, one cell at a
     * time, the first height where the old density passed the threshold. A constant density never
     * varied by column, and vanilla gave those dimensions a flat 0.
     */
    private static JsonElement surfaceLevel(JsonElement initialDensity, JsonElement noise, Cells cells) {
        if (initialDensity == null || initialDensity.isJsonPrimitive()) return new JsonPrimitive(0.0);
        int minY = intField(noise, "min_y", -64);
        int height = intField(noise, "height", 384);
        JsonObject density = new JsonObject();
        density.addProperty("type", "minecraft:add");
        density.addProperty("argument1", -OLD_SURFACE_THRESHOLD);
        density.add("argument2", initialDensity);
        JsonObject scan = new JsonObject();
        scan.addProperty("type", "minecraft:find_top_surface");
        scan.add("density", density);
        scan.addProperty("upper_bound", (double) (minY + height));
        scan.addProperty("lower_bound", minY);
        scan.addProperty("cell_height", cells.y());
        return scan;
    }

    private static JsonObject materialSystemSettings(JsonObject old, JsonObject router, Cells cells) {
        JsonObject settings = new JsonObject();
        for (String key : List.of("sea_level", "disable_mob_generation", "legacy_random_source")) {
            if (old.has(key)) settings.add(key, old.get(key));
        }
        JsonObject noise = new JsonObject();
        noise.addProperty("min_y", intField(old.get("noise"), "min_y", -64));
        noise.addProperty("height", intField(old.get("noise"), "height", 384));
        settings.add("noise", noise);
        if (old.has("default_block")) settings.add("default_block", Worldgen26_3Migrator.migrate(old.get("default_block")));
        if (old.has("default_fluid")) settings.add("default_fluid", Worldgen26_3Migrator.migrate(old.get("default_fluid")));

        JsonElement surface = modernFunction(routerFunction(router, "preliminary_surface_level"), cells);
        JsonObject newRouter = new JsonObject();
        for (String key : CLIMATE_ROUTER) newRouter.add(key, modernFunction(routerFunction(router, key), cells));
        newRouter.add("chunk_surface_level", surface.isJsonPrimitive() ? surface : interpolated(surface.deepCopy(), 16, 1));
        settings.add("noise_router", newRouter);

        if (isTrue(old, "aquifers_enabled")) {
            JsonObject aquifers = new JsonObject();
            for (String key : AQUIFER_ROUTER) aquifers.add(key, modernFunction(routerFunction(router, key), cells));
            aquifers.add("exclusion", deepDarkExclusion(newRouter.get("erosion").deepCopy(), newRouter.get("depth").deepCopy()));
            aquifers.add("surface_level", surface.deepCopy());
            settings.add("aquifers", aquifers);
        }

        JsonElement rule = old.has("surface_rule") ? Worldgen26_3Migrator.migrate(old.get("surface_rule")) : sequence(new JsonArray());
        if (isTrue(old, "ore_veins_enabled")) {
            JsonArray withVeins = new JsonArray();
            withVeins.add("minecraft:overworld/copper_ore_vein");
            withVeins.add("minecraft:overworld/iron_ore_vein");
            withVeins.add(rule);
            rule = sequence(withVeins);
        }
        settings.add("material_rule", rule);
        settings.add("spawn_target", spawnTarget(old.get("spawn_target"), router));
        return settings;
    }

    /**
     * The old aquifer's hardcoded deep dark check as the density 26.3 reads: positive where erosion
     * is below -0.225 and depth above 0.9, where no aquifer fluid was placed.
     */
    private static JsonObject deepDarkExclusion(JsonElement erosion, JsonElement depth) {
        JsonObject depthPart = new JsonObject();
        depthPart.addProperty("type", "minecraft:max");
        depthPart.add("left", binary("minecraft:sub", depth, new JsonPrimitive(0.9)));
        depthPart.addProperty("right", 0.0);
        return binary("minecraft:min", binary("minecraft:sub", new JsonPrimitive(-0.225), erosion), depthPart);
    }

    /**
     * Old spawn targets were climate points; 26.3 keys each range by the density function that
     * produces it, so a parameter carries over when the router named a registered function.
     */
    private static JsonArray spawnTarget(JsonElement oldTarget, JsonObject router) {
        JsonArray points = new JsonArray();
        if (!(oldTarget instanceof JsonArray oldPoints)) return points;
        for (JsonElement element : oldPoints) {
            if (!(element instanceof JsonObject oldPoint)) continue;
            JsonObject point = new JsonObject();
            for (Map.Entry<String, String> parameter : SPAWN_PARAMETERS.entrySet()) {
                JsonElement source = router.get(parameter.getValue());
                JsonElement range = oldPoint.get(parameter.getKey());
                if (range == null || source == null || !source.isJsonPrimitive() || !source.getAsJsonPrimitive().isString()) {
                    continue;
                }
                point.add(source.getAsString(), asRange(range));
            }
            if (!point.isEmpty()) points.add(point);
        }
        return points;
    }

    private static JsonElement asRange(JsonElement value) {
        if (value instanceof JsonArray) return value;
        JsonArray range = new JsonArray();
        range.add(value);
        range.add(value);
        return range;
    }

    /**
     * 26.2 removed {@code weird_scaled_sampler}. It sampled a noise at a scale picked from bands of its
     * input and returned the absolute value times that scale, which an {@code interval_select} over
     * pre-scaled noises computes the same way.
     */
    static JsonElement withoutWeirdSampler(JsonElement element) {
        if (element instanceof JsonArray array) {
            JsonArray out = new JsonArray();
            for (JsonElement item : array) out.add(withoutWeirdSampler(item));
            return out;
        }
        if (!(element instanceof JsonObject object)) return element;
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> field : object.entrySet()) out.add(field.getKey(), withoutWeirdSampler(field.getValue()));
        if (!"minecraft:weird_scaled_sampler".equals(typeOf(out))) return out;

        boolean rarity2d = out.has("rarity_value_mapper") && "type_2".equals(out.get("rarity_value_mapper").getAsString());
        double[] thresholds = rarity2d ? new double[]{-0.75, -0.5, 0.5, 0.75} : new double[]{-0.5, 0.0, 0.5};
        double[] scales = rarity2d ? new double[]{0.5, 0.75, 1.0, 2.0, 3.0} : new double[]{0.75, 1.0, 1.5, 2.0};
        JsonArray bands = new JsonArray();
        for (double threshold : thresholds) bands.add(threshold);
        JsonArray functions = new JsonArray();
        for (double scale : scales) {
            JsonObject noise = new JsonObject();
            noise.addProperty("type", "minecraft:noise");
            noise.add("noise", out.get("noise"));
            noise.addProperty("xz_scale", 1.0 / scale);
            noise.addProperty("y_scale", 1.0 / scale);
            JsonObject scaled = new JsonObject();
            scaled.addProperty("type", "minecraft:mul");
            scaled.addProperty("argument1", scale);
            scaled.add("argument2", noise);
            functions.add(scaled);
        }
        JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:interval_select");
        select.add("input", out.get("input"));
        select.add("thresholds", bands);
        select.add("functions", functions);
        JsonObject abs = new JsonObject();
        abs.addProperty("type", "minecraft:abs");
        abs.add("argument", select);
        return abs;
    }

    /**
     * A density function in 26.3's names: {@code argument} became {@code input}, {@code argument1} and
     * {@code argument2} became {@code left} and {@code right}, the four caches became {@code cache},
     * {@code invert} became {@code reciprocal}, {@code shifted_noise} folded into {@code noise},
     * {@code y_clamped_gradient} became a y {@code gradient}, and {@code end_islands} became the vanilla
     * function that now describes the same islands. Functions already in the new shape are left as
     * they are.
     */
    static JsonElement modernFunction(JsonElement element, Cells cells) {
        if (element instanceof JsonArray array) {
            JsonArray out = new JsonArray();
            for (JsonElement item : array) out.add(modernFunction(item, cells));
            return out;
        }
        if (!(element instanceof JsonObject object)) return element;
        String type = typeOf(object);
        if (type != null) {
            if (BINARY.contains(type) && object.has("argument1")) {
                return binary(type, modernFunction(object.get("argument1"), cells), modernFunction(object.get("argument2"), cells));
            }
            if (UNARY.contains(type) && object.has("argument")) return unary(type, modernFunction(object.get("argument"), cells));
            if (type.equals("minecraft:invert") && object.has("argument")) {
                return unary("minecraft:reciprocal", modernFunction(object.get("argument"), cells));
            }
            if (OLD_CACHES.contains(type)) return unary("minecraft:cache", modernFunction(object.get("argument"), cells));
            if (type.equals("minecraft:interpolated") && object.has("argument")) {
                return interpolated(modernFunction(object.get("argument"), cells), cells.xz(), cells.y());
            }
            if (SHIFTS.contains(type) && object.has("argument")) {
                JsonObject shift = new JsonObject();
                shift.addProperty("type", type);
                shift.add("noise", object.get("argument"));
                return shift;
            }
            if (type.equals("minecraft:shifted_noise")) return shiftedNoise(object, cells);
            if (type.equals("minecraft:y_clamped_gradient")) return yGradient(object);
            if (type.equals("minecraft:constant") && object.has("argument")) {
                JsonObject constant = new JsonObject();
                constant.addProperty("type", type);
                constant.add("value", object.get("argument"));
                return constant;
            }
            if (type.equals("minecraft:end_islands")) return new JsonPrimitive("minecraft:end/islands");
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> field : object.entrySet()) out.add(field.getKey(), modernFunction(field.getValue(), cells));
        return out;
    }

    private static JsonObject shiftedNoise(JsonObject old, Cells cells) {
        JsonObject noise = new JsonObject();
        noise.addProperty("type", "minecraft:noise");
        for (String key : List.of("noise", "xz_scale", "y_scale")) {
            if (old.has(key)) noise.add(key, old.get(key));
        }
        for (String key : List.of("shift_x", "shift_y", "shift_z")) {
            JsonElement shift = old.get(key);
            if (shift == null || shift.isJsonPrimitive() && shift.getAsJsonPrimitive().isNumber() && shift.getAsDouble() == 0.0) continue;
            noise.add(key, modernFunction(shift, cells));
        }
        return noise;
    }

    private static JsonObject yGradient(JsonObject old) {
        JsonObject gradient = new JsonObject();
        gradient.addProperty("type", "minecraft:gradient");
        gradient.addProperty("axis", "y");
        gradient.add("from_coordinate", old.get("from_y"));
        gradient.add("from_value", old.get("from_value"));
        gradient.add("to_coordinate", old.get("to_y"));
        gradient.add("to_value", old.get("to_value"));
        return gradient;
    }

    private static JsonObject binary(String type, JsonElement left, JsonElement right) {
        JsonObject function = new JsonObject();
        function.addProperty("type", type);
        function.add("left", left);
        function.add("right", right);
        return function;
    }

    private static JsonObject unary(String type, JsonElement input) {
        JsonObject function = new JsonObject();
        function.addProperty("type", type);
        function.add("input", input);
        return function;
    }

    private static JsonObject interpolated(JsonElement input, int cellXz, int cellY) {
        JsonObject function = new JsonObject();
        function.addProperty("type", "minecraft:interpolated");
        function.addProperty("cell_size_xz", cellXz);
        function.addProperty("cell_size_y", cellY);
        function.add("input", input);
        return function;
    }

    private static JsonObject sequence(JsonArray rules) {
        JsonObject sequence = new JsonObject();
        sequence.addProperty("type", "minecraft:sequence");
        sequence.add("sequence", rules);
        return sequence;
    }

    private static JsonElement routerFunction(JsonObject router, String key) {
        return router.has(key) ? router.get(key) : new JsonPrimitive(0.0);
    }

    private static Cells cells(JsonElement noise) {
        return new Cells(intField(noise, "size_horizontal", 1) * 4, intField(noise, "size_vertical", 2) * 4);
    }

    private static int intField(JsonElement object, String key, int fallback) {
        if (object instanceof JsonObject json && json.get(key) instanceof JsonPrimitive value && value.isNumber()) {
            return value.getAsInt();
        }
        return fallback;
    }

    /** The type id with the namespace an old file was allowed to leave out. */
    private static String typeOf(JsonObject object) {
        if (!(object.get("type") instanceof JsonPrimitive type) || !type.isString()) return null;
        String id = type.getAsString();
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    private static boolean isTrue(JsonObject object, String key) {
        return object.get(key) instanceof JsonPrimitive value && value.isBoolean() && value.getAsBoolean();
    }

    private static boolean atLeast(String targetMcVersion, String version) {
        return targetMcVersion != null && RetromodVersion.compareMcVersions(targetMcVersion, version) >= 0;
    }
}
