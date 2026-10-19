/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.retromod.util.JsonSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewrites 1.12 blockstates and Forge's own blockstate format into the vanilla format newer games read. */
public final class LegacyBlockStateMigrator implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-BlockStates");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    // 1.13 reads "" for 1.12's normal variant, full model paths (1.12's oldmod:ruby_ore meant
    // oldmod:block/ruby_ore), and no inventory variant. Checked in the 1.12.2 and 1.13 client jars.
    static final String FIRST_FLAT_BLOCKSTATE_HOST = "1.13";
    // Forge 1.12.2 and 1.13.2 prefix forge_marker model names with block/, and 1.14.4 does not.
    static final String FIRST_FORGE_WITHOUT_BLOCK_PREFIX = "1.14";
    // The first Forge with no reader for forge_marker (defaults, per-property maps, textures, OBJ
    // models, submodels). 1.14.4's universal jar ships ForgeBlockStateV1, and 1.15.2's does not.
    // Fabric never read it.
    static final String FIRST_HOST_WITHOUT_FORGE_MARKER = "1.15";
    // Per the client jars, 1.21.6 and 26.3 parse blockstates, items, atlases, equipment and post
    // effects with StrictJsonParser, so a comment or trailing comma drops the file. 1.21.5 was lenient.
    static final String FIRST_STRICT_ASSET_JSON_HOST = "1.21.6";
    // The first host whose OBJ loader takes the id and flip_v option written here, checked in Forge
    // 1.20.1 and 26.2 and NeoForge 20.2, 21.1 and 26.3. Older hosts spelled the option differently.
    static final String FIRST_OBJ_LOADER_HOST = "1.20";
    /** The first NeoForge whose OBJ loader is {@code neoforge:obj}. */
    static final String FIRST_NEOFORGE_NAMESPACE_HOST = "1.20.2";

    /** Where generated models go, beside the mod's own, under a folder no mod uses. */
    static final String GENERATED_MODEL_FOLDER = "block/retromod/";

    /** Forge expands per-property maps into every combination; past this the file is left alone. */
    private static final int MAX_STATE_KEYS = 4096;
    private static final long MAX_JSON_BYTES = JsonSecurity.DEFAULT_MAX_BYTES;
    private static final int MAX_JSON_DEPTH = JsonSecurity.DEFAULT_MAX_DEPTH;

    // Blockstates at the jar's root are converted; strict assets are cleaned at any depth.
    private static final Pattern ROOT_BLOCKSTATE = Pattern.compile(
            "assets/([a-z0-9_.-]+)/blockstates/(.+)\\.json");
    private static final Pattern STRICT_ASSET = Pattern.compile(
            "(?:.+/)?assets/([^/]+)/(blockstates|items|atlases|equipment|post_effect)/.+\\.json");
    /** The one strict asset folder a pack adds to rather than replaces, checked in 1.21.6 and 26.3. */
    private static final String ADDITIVE_ASSET_FOLDER = "atlases";
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern IDENTIFIER_PATH = Pattern.compile("[a-z0-9_./-]+");

    /** The transform names Forge 1.12 accepted as a string. */
    private static final Set<String> TRANSFORM_PRESETS = Set.of(
            "identity", "forge:default-block", "forge:default-item", "forge:default-tool");
    /** Transform keys that only change how an item is held or shown, never the placed block. */
    private static final Set<String> PERSPECTIVE_KEYS = Set.of(
            "thirdperson", "thirdperson_righthand", "thirdperson_lefthand", "firstperson",
            "firstperson_righthand", "firstperson_lefthand", "head", "gui", "ground", "fixed");

    @Override
    public String name() {
        return "1.12 and Forge v1 blockstates";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return convertsVanillaBlockStates(context) || convertsForgeBlockStates(context)
                || parsesAssetsStrictly(context);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        int changed = 0;
        if (convertsVanillaBlockStates(context) || convertsForgeBlockStates(context)) {
            changed += new Conversion(tree, context).run();
        }
        if (parsesAssetsStrictly(context)) {
            changed += normalizeStrictAssets(tree);
        }
        return changed;
    }

    /** Whether the mod predates the 1.13 blockstate rules and the host follows them. */
    static boolean convertsVanillaBlockStates(MigrationContext context) {
        return context.sourceBefore(FIRST_FLAT_BLOCKSTATE_HOST)
                && context.targetAtLeast(FIRST_FLAT_BLOCKSTATE_HOST);
    }

    /** Whether the host can no longer read the mod's {@code forge_marker} blockstates. */
    static boolean convertsForgeBlockStates(MigrationContext context) {
        if (!context.sourceBefore(FIRST_HOST_WITHOUT_FORGE_MARKER)
                || !context.targetAtLeast(FIRST_FLAT_BLOCKSTATE_HOST)) {
            return false;
        }
        // Forge 1.13 to 1.14.4 still read the format, so it stays for a Forge-family host there. A
        // Fabric host never read it, and an unknown loader is treated as one that might.
        return context.targetAtLeast(FIRST_HOST_WITHOUT_FORGE_MARKER)
                || context.targetLoader().isFabricFamily();
    }

    static boolean parsesAssetsStrictly(MigrationContext context) {
        return context.targetAtLeast(FIRST_STRICT_ASSET_JSON_HOST);
    }

    /** Strips comments and trailing commas from the asset JSON a strict host would drop. */
    private static int normalizeStrictAssets(ResourceTree tree) throws IOException {
        int normalized = 0;
        for (String name : tree.names()) {
            Matcher asset = STRICT_ASSET.matcher(name);
            if (!asset.matches()) continue;
            // A file in a shared namespace stands in for vanilla's. A broken copy is ignored today
            // while vanilla's still loads, so a repair would newly replace it. Atlases are the
            // exception: the game reads every pack's copy and only adds their sources.
            boolean replacesShared = MigrationContext.SHARED_NAMESPACES.contains(asset.group(1))
                    && !asset.group(2).equals(ADDITIVE_ASSET_FOLDER);
            if (replacesShared) continue;
            byte[] bytes = tree.read(name);
            byte[] strict = ModDataMigrator.normalizeLenientJson(bytes);
            if (strict != bytes) {
                tree.write(name, strict);
                normalized++;
            }
        }
        if (normalized > 0) {
            LOGGER.info("Removed comments or trailing commas from {} blockstate, item, atlas, "
                    + "equipment or post effect file(s) that strict hosts reject", normalized);
        }
        return normalized;
    }

    /** One pass over a mod's blockstates. */
    private static final class Conversion {
        private final ResourceTree tree;
        private final MigrationContext context;
        private final boolean vanilla;
        private final boolean forge;
        private final boolean forgePrefixesBlock;
        private final String objLoader;
        private final Map<String, List<String>> leftAlone = new TreeMap<>();
        private final Map<String, Integer> droppedVariants = new TreeMap<>();
        private int converted;
        private int itemModels;
        private int generatedModels;

        Conversion(ResourceTree tree, MigrationContext context) {
            this.tree = tree;
            this.context = context;
            this.vanilla = convertsVanillaBlockStates(context);
            this.forge = convertsForgeBlockStates(context);
            this.forgePrefixesBlock = context.sourceBefore(FIRST_FORGE_WITHOUT_BLOCK_PREFIX);
            this.objLoader = objLoader(context);
        }

        int run() throws IOException {
            int written = 0;
            for (String name : tree.names()) {
                Matcher blockstate = ROOT_BLOCKSTATE.matcher(name);
                if (!blockstate.matches()) continue;
                // Only the mod's own namespaces are converted, never minecraft or another shared one.
                String namespace = blockstate.group(1);
                if (!context.ownNamespaces().contains(namespace)) continue;
                String path = blockstate.group(2);
                if (!IDENTIFIER_PATH.matcher(path).matches()) {
                    leaveAlone(name, "its name is not a valid identifier");
                    continue;
                }
                JsonObject root = readObject(name);
                if (root == null) {
                    leaveAlone(name, "it is not a JSON object");
                    continue;
                }
                // A file whose meaning cannot be carried over exactly, such as a transform that
                // moves the block or weighted submodels, is left as it was and logged.
                Result result;
                try {
                    if (root.has("forge_marker")) {
                        if (!forge) continue;
                        result = new ForgeFile(namespace, path, root).convert();
                    } else {
                        if (!vanilla) continue;
                        result = new VanillaFile(namespace, path, root).convert();
                    }
                } catch (Unsupported unsupported) {
                    leaveAlone(name, unsupported.getMessage());
                    continue;
                }
                written += write(name, namespace, path, root, result);
            }
            report();
            return written;
        }

        /** Writes generated models, the item model, then the blockstate, so none names a missing model. */
        private int write(String name, String namespace, String path, JsonObject original,
                          Result result) throws IOException {
            // A generated path the mod already uses for something else leaves the whole file alone.
            for (Map.Entry<String, JsonObject> model : result.models().entrySet()) {
                if (tree.exists(model.getKey()) && !model.getValue().equals(readObject(model.getKey()))) {
                    leaveAlone(name, "a generated model path is already taken");
                    return 0;
                }
            }
            // A model already there is identical, as checked above, so it is kept.
            int written = 0;
            for (Map.Entry<String, JsonObject> model : result.models().entrySet()) {
                if (tree.exists(model.getKey())) continue;
                tree.write(model.getKey(), json(model.getValue()));
                generatedModels++;
                written++;
            }
            // An inventory variant becomes the item model, unless the mod ships one there.
            if (result.itemModel() != null) {
                String itemModel = "assets/" + namespace + "/models/item/" + path + ".json";
                if (!tree.exists(itemModel)) {
                    tree.write(itemModel, json(result.itemModel()));
                    itemModels++;
                    written++;
                }
            }
            if (result.blockState() != null && !result.blockState().equals(original)) {
                tree.write(name, json(result.blockState()));
                converted++;
                written++;
            }
            for (String reason : result.dropped()) {
                droppedVariants.merge(reason, 1, Integer::sum);
            }
            return written;
        }

        /** Logs what was converted, and what was left out or left alone and why. */
        private void report() {
            if (converted + itemModels > 0) {
                LOGGER.info("Converted {} old blockstate(s) for Minecraft {}, with {} generated "
                                + "model(s) and {} item model(s) from inventory variants",
                        converted, context.targetMcVersion(), generatedModels, itemModels);
            }
            if (!droppedVariants.isEmpty()) {
                LOGGER.info("Left out old blockstate variants no {} host can load, by reason: {}",
                        context.targetMcVersion(), droppedVariants);
            }
            for (Map.Entry<String, List<String>> reason : leftAlone.entrySet()) {
                List<String> files = reason.getValue();
                LOGGER.info("Left {} old blockstate(s) unconverted because {}: {}{}", files.size(),
                        reason.getKey(), files.subList(0, Math.min(5, files.size())),
                        files.size() > 5 ? " and more" : "");
            }
        }

        private void leaveAlone(String name, String reason) {
            leftAlone.computeIfAbsent(reason, ignored -> new ArrayList<>()).add(name);
        }

        /** One blockstate file, with the models it generates and the variants it leaves out, and why. */
        private abstract class BlockStateFile {
            final String namespace;
            final String path;
            final JsonObject root;
            final Map<String, JsonObject> models = new LinkedHashMap<>();
            private final Map<String, String> modelIdsByContent = new LinkedHashMap<>();
            final List<String> dropped = new ArrayList<>();

            BlockStateFile(String namespace, String path, JsonObject root) {
                this.namespace = namespace;
                this.path = path;
                this.root = root;
            }

            abstract Result convert() throws Unsupported;

            /** Why no 1.13+ host can load this model from here, or null when one can. */
            String unsupportedReason(ModelId model) {
                if (!model.isValid()) return "a model name that is not a valid identifier";
                if (model.path().endsWith(".b3d")) return "a B3D model, which no 1.13+ loader reads";
                if (model.namespace().equals("forge")) return "a Forge built-in model such as forge:fluid";
                if (model.isObj()) {
                    if (objLoader == null) {
                        return "an OBJ model, which " + context.targetLoader() + " "
                                + context.targetMcVersion() + " has no loader for";
                    }
                    if (!tree.exists("assets/" + model.namespace() + "/models/" + model.path())) {
                        return "an OBJ model whose file is missing";
                    }
                }
                return null;
            }

            /** The loader model for an OBJ file; both formats build it here, so it comes out the same. */
            JsonObject objModel(ModelId model, Map<String, JsonElement> custom, Map<String, String> textures,
                                String parent) throws Unsupported {
                JsonObject obj = new JsonObject();
                if (parent != null) obj.addProperty("parent", parent);
                obj.addProperty("loader", objLoader);
                obj.addProperty("model", model.namespace() + ":models/" + model.path());
                // Only the 1.12 flip-v option carries over, as flip_v; any other leaves the file alone.
                for (Map.Entry<String, JsonElement> option : custom.entrySet()) {
                    if (!option.getKey().equals("flip-v")) {
                        throw new Unsupported("an OBJ model has the option '" + option.getKey() + "'");
                    }
                    if (option.getValue() instanceof JsonPrimitive flip && flip.isBoolean()
                            && flip.getAsBoolean()) {
                        obj.addProperty("flip_v", true);
                    }
                }
                if (!textures.isEmpty()) obj.add("textures", texturesJson(textures));
                return obj;
            }

            /** The id of a generated model with this content, reusing one already made for it. */
            String generated(JsonObject model) {
                String content = model.toString();
                String known = modelIdsByContent.get(content);
                if (known != null) return known;
                // Each distinct model takes the next number in this blockstate's own folder.
                String relative = GENERATED_MODEL_FOLDER + path + "/" + modelIdsByContent.size();
                String id = namespace + ":" + relative;
                modelIdsByContent.put(content, id);
                models.put("assets/" + namespace + "/models/" + relative + ".json", model);
                return id;
            }
        }

        // ---- vanilla 1.12 blockstates

        /** One blockstate in the vanilla 1.12 format, which Forge 1.12 also read OBJ models from. */
        private final class VanillaFile extends BlockStateFile {

            VanillaFile(String namespace, String path, JsonObject root) {
                super(namespace, path, root);
            }

            @Override
            Result convert() throws Unsupported {
                JsonObject out = root.deepCopy();
                JsonObject itemModel = null;
                boolean hasMultipart = out.get("multipart") instanceof JsonArray;
                if (out.get("variants") instanceof JsonObject variants) {
                    // Forge's inventory variant was the item model, which 1.13+ reads from models/item/.
                    JsonElement inventory = variants.remove("inventory");
                    if (inventory != null) itemModel = itemModel(inventory);
                    // Each variant gets 1.13 model names; one with no loadable model is dropped.
                    JsonObject placed = new JsonObject();
                    for (Map.Entry<String, JsonElement> variant : withoutNormalKey(variants).entrySet()) {
                        JsonElement converted = place(variant.getValue());
                        if (converted != null) placed.add(variant.getKey(), converted);
                    }
                    if (placed.size() > 0) {
                        out.add("variants", placed);
                    } else if (hasMultipart) {
                        // 26.3 rejects an empty variants map even beside multipart parts.
                        out.remove("variants");
                    } else if (variants.size() > 0) {
                        throw new Unsupported("none of its models has a 1.13+ equivalent");
                    } else {
                        // Only the item used this file; an empty variants map fails to parse.
                        return new Result(null, models, itemModel, dropped);
                    }
                }
                // Multipart parts get the same model names, and a part with no loadable model is dropped.
                if (hasMultipart) {
                    JsonArray parts = new JsonArray();
                    for (JsonElement part : out.getAsJsonArray("multipart")) {
                        if (part instanceof JsonObject object && object.has("apply")) {
                            JsonElement apply = place(object.get("apply"));
                            if (apply == null) continue;
                            object.add("apply", apply);
                        }
                        parts.add(part);
                    }
                    if (!parts.isEmpty()) {
                        out.add("multipart", parts);
                    } else if (out.has("variants")) {
                        out.remove("multipart");
                    } else {
                        throw new Unsupported("none of its models has a 1.13+ equivalent");
                    }
                }
                return new Result(out, models, itemModel, dropped);
            }

            /** Renames {@code normal}, the state of a block without properties, to {@code ""}. */
            private JsonObject withoutNormalKey(JsonObject variants) {
                if (!variants.has("normal")) return variants;
                JsonObject renamed = new JsonObject();
                // Beside other keys it would overlap every one of them on 1.13+, where keys match
                // as conditions, so there it is dropped.
                boolean alone = variants.size() == 1 || (variants.size() == 2 && variants.has(""));
                for (Map.Entry<String, JsonElement> variant : variants.entrySet()) {
                    if (!variant.getKey().equals("normal")) {
                        renamed.add(variant.getKey(), variant.getValue());
                    } else if (alone && !variants.has("")) {
                        renamed.add("", variant.getValue());
                    }
                }
                return renamed;
            }

            /** The variant with 1.13 model names, or null when none of its models loads on 1.13+. */
            private JsonElement place(JsonElement variant) throws Unsupported {
                // A weighted list keeps the options that still load.
                if (variant instanceof JsonArray weighted) {
                    JsonArray kept = new JsonArray();
                    for (JsonElement option : weighted) {
                        JsonElement placed = place(option);
                        if (placed != null) kept.add(placed);
                    }
                    return kept.isEmpty() && !weighted.isEmpty() ? null : kept;
                }
                if (variant instanceof JsonObject object
                        && object.get("model") instanceof JsonPrimitive model && model.isString()) {
                    // A JSON model only needs its new name. An OBJ or B3D file needs a generated loader
                    // model.
                    String ref = vanillaModelRef(model.getAsString());
                    ModelId located = ModelId.parse(ref);
                    if (located == null || !located.usesForgeLoader()) {
                        object.addProperty("model", ref);
                        return object;
                    }
                    JsonObject loaderModel = forgeLoaderModel(located);
                    if (loaderModel == null) return null;
                    object.addProperty("model", generated(loaderModel));
                    return object;
                }
                return variant;
            }

            /** The 1.13 name of a 1.12 variant model, which 1.12.2 read under {@code models/block/}. */
            private String vanillaModelRef(String written) {
                ModelId id = ModelId.parse(written);
                if (id == null) return written;
                if (modelExists(id.namespace(), "block/" + id.path())) return id.withPath("block/" + id.path());
                // A name that resolves as written, or starts with a 1.13 folder, is kept, which also
                // stops a second run from adding the folder twice.
                if (modelExists(id.namespace(), id.path()) || id.namesCurrentFolder()) return written;
                return id.withPath("block/" + id.path());
            }

            /** The loader model for an OBJ or B3D file in a plain variant, which Forge 1.12 loaded itself. */
            private JsonObject forgeLoaderModel(ModelId model) throws Unsupported {
                String unsupported = unsupportedReason(model);
                if (unsupported != null) {
                    dropped.add(unsupported);
                    return null;
                }
                // This format has no place for options, so the model gets none.
                return objModel(model, Map.of(), Map.of(), null);
            }

            /** The item model an inventory variant named; an OBJ model is written out in full. */
            private JsonObject itemModel(JsonElement inventory) throws Unsupported {
                // The first option of a weighted list stands for the item.
                JsonElement first = inventory instanceof JsonArray weighted && weighted.size() > 0
                        ? weighted.get(0) : inventory;
                if (!(first instanceof JsonObject variant)
                        || !(variant.get("model") instanceof JsonPrimitive model) || !model.isString()) {
                    return null;
                }
                String located = vanillaModelRef(model.getAsString());
                ModelId id = ModelId.parse(located);
                if (id != null && id.usesForgeLoader()) return forgeLoaderModel(id);
                JsonObject item = new JsonObject();
                item.addProperty("parent", located);
                return item;
            }
        }

        /** Whether the mod ships this model; OBJ and B3D names carry their own extension. */
        private boolean modelExists(String namespace, String path) {
            boolean hasExtension = path.endsWith(".obj") || path.endsWith(".b3d");
            return tree.exists("assets/" + namespace + "/models/" + path + (hasExtension ? "" : ".json"));
        }

        // ---- Forge v1 blockstates

        /** One {@code forge_marker} file, expanded the way Forge 1.12 read it. */
        private final class ForgeFile extends BlockStateFile {

            ForgeFile(String namespace, String path, JsonObject root) {
                super(namespace, path, root);
            }

            @Override
            Result convert() throws Unsupported {
                if (!(root.get("forge_marker") instanceof JsonPrimitive marker)
                        || !marker.isNumber() || marker.getAsInt() != 1) {
                    throw new Unsupported("its forge_marker version is not 1");
                }
                if (root.has("multipart")) throw new Unsupported("it mixes forge_marker and multipart");
                Map<String, List<ForgeVariant>> states = ForgeVariant.expand(root);

                // The inventory variant becomes the item model; the other keys are block states.
                List<ForgeVariant> inventory = states.remove("inventory");
                JsonObject itemModel = inventory == null || inventory.isEmpty()
                        ? null : itemModel(inventory.get(0));
                List<String> blockKeys = blockKeys(states.keySet());
                if (blockKeys.isEmpty()) return new Result(null, models, itemModel, dropped);

                // Submodels need multipart; otherwise the states become plain variants.
                boolean parts = false;
                for (String key : blockKeys) {
                    for (ForgeVariant variant : states.get(key)) parts |= !variant.submodels.isEmpty();
                }
                JsonObject blockState = parts
                        ? multipart(blockKeys, states) : variants(blockKeys, states);
                return new Result(blockState, models, itemModel, dropped);
            }

            /** The keys that name block states; ones that could match the same state are checked later. */
            private List<String> blockKeys(Set<String> keys) {
                List<String> blockKeys = new ArrayList<>();
                for (String key : keys) {
                    if (key.equals("normal")) continue;
                    if (StateKey.parse(key) == null) {
                        dropped.add("a key that is not property=value pairs");
                        continue;
                    }
                    blockKeys.add(key);
                }
                // normal only works alone; beside property keys it would match every state.
                if (keys.contains("normal")) {
                    if (blockKeys.isEmpty()) blockKeys.add("normal");
                    else dropped.add("'normal' beside property keys");
                }
                return blockKeys;
            }

            /** Writes plain variants: one entry, or a weighted list, per state key. */
            private JsonObject variants(List<String> blockKeys, Map<String, List<ForgeVariant>> states)
                    throws Unsupported {
                Map<String, JsonArray> entriesByKey = new LinkedHashMap<>();
                Map<String, String> renderings = new LinkedHashMap<>();
                for (String key : blockKeys) {
                    List<ForgeVariant> options = states.get(key);
                    JsonArray entries = new JsonArray();
                    for (ForgeVariant option : options) {
                        String model = modelFor(option);
                        if (model == null) continue;
                        JsonObject entry = placement(model, option.blockRotation(), option.uvlock);
                        // A weight only matters when there is more than one option.
                        if (options.size() > 1 && option.weight != null) {
                            entry.addProperty("weight", option.weight);
                        }
                        entries.add(entry);
                    }
                    entriesByKey.put(key, entries);
                    renderings.put(key, entries.toString());
                }
                // Keys whose output a more general key already gives are left out.
                JsonObject variants = new JsonObject();
                for (String key : StateKey.distinct(renderings)) {
                    JsonArray entries = entriesByKey.get(key);
                    if (entries.isEmpty()) continue;
                    variants.add(StateKey.vanillaKey(key), entries.size() == 1 ? entries.get(0) : entries);
                }
                if (variants.size() == 0) throw new Unsupported("none of its models has a 1.13+ equivalent");
                JsonObject blockState = new JsonObject();
                blockState.add("variants", variants);
                return blockState;
            }

            /** Turns states with submodels into multipart parts. */
            private JsonObject multipart(List<String> blockKeys, Map<String, List<ForgeVariant>> states)
                    throws Unsupported {
                Map<String, List<JsonObject>> partsByKey = new LinkedHashMap<>();
                Map<String, String> renderings = new LinkedHashMap<>();
                for (String key : blockKeys) {
                    List<ForgeVariant> options = states.get(key);
                    if (options.size() != 1) throw new Unsupported("it has weighted variants with submodels");
                    ForgeVariant state = options.get(0);
                    Rotation rotation = state.blockRotation();
                    List<JsonObject> parts = new ArrayList<>();
                    if (state.model != null) {
                        String model = modelFor(state);
                        if (model != null) parts.add(placement(model, rotation, state.uvlock));
                    }
                    for (Map.Entry<String, List<ForgeVariant>> submodel : state.submodels.entrySet()) {
                        if (submodel.getValue().size() != 1) {
                            throw new Unsupported("it has weighted submodels");
                        }
                        // Forge composed a part's rotation with its state's. The part keeps that
                        // when the two combine into one vanilla rotation.
                        ForgeVariant part = submodel.getValue().get(0);
                        Rotation combined = rotation.then(part.blockRotation());
                        if (combined == null) {
                            throw new Unsupported("a submodel rotation has no single x and y equivalent");
                        }
                        String model = modelFor(part);
                        if (model == null) continue;
                        parts.add(placement(model, combined, part.uvlock));
                    }
                    partsByKey.put(key, parts);
                    // Every part of a state is drawn, so their order does not change what it shows.
                    Set<String> drawn = new TreeSet<>();
                    for (JsonObject part : parts) drawn.add(part.toString());
                    renderings.put(key, drawn.toString());
                }
                // Identical parts share one entry, with a condition for each state that draws them.
                Map<String, JsonObject> applies = new LinkedHashMap<>();
                Map<String, List<Map<String, String>>> conditions = new LinkedHashMap<>();
                for (String key : StateKey.distinct(renderings)) {
                    for (JsonObject part : partsByKey.get(key)) {
                        addPart(applies, conditions, part, StateKey.parse(key));
                    }
                }
                if (applies.isEmpty()) throw new Unsupported("none of its models has a 1.13+ equivalent");
                JsonArray multipart = new JsonArray();
                for (Map.Entry<String, JsonObject> apply : applies.entrySet()) {
                    JsonObject entry = new JsonObject();
                    JsonObject when = StateKey.when(conditions.get(apply.getKey()));
                    if (when != null) entry.add("when", when);
                    entry.add("apply", apply.getValue());
                    multipart.add(entry);
                }
                JsonObject blockState = new JsonObject();
                blockState.add("multipart", multipart);
                return blockState;
            }

            /** Adds a part, or one more condition to an identical part already added. */
            private void addPart(Map<String, JsonObject> applies,
                                 Map<String, List<Map<String, String>>> conditions,
                                 JsonObject apply, Map<String, String> condition) {
                String key = apply.toString();
                applies.putIfAbsent(key, apply);
                conditions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(condition);
            }

            /** A variant entry: the model, with its rotation and uvlock only when they are set. */
            private JsonObject placement(String model, Rotation rotation, Boolean uvlock) {
                JsonObject entry = new JsonObject();
                entry.addProperty("model", model);
                if (rotation.x() != 0) entry.addProperty("x", rotation.x());
                if (rotation.y() != 0) entry.addProperty("y", rotation.y());
                if (Boolean.TRUE.equals(uvlock)) entry.addProperty("uvlock", true);
                return entry;
            }

            /** The model a variant renders with, or null, noting why, when no 1.13+ host can load it. */
            private String modelFor(ForgeVariant variant) throws Unsupported {
                ModelId model = forgeModel(variant.model);
                if (model == null) return null;
                String unsupported = unsupportedReason(model);
                if (unsupported != null) {
                    dropped.add(unsupported);
                    return null;
                }
                Map<String, String> textures = variant.textureOverrides(model.isObj());
                if (model.isObj()) {
                    return generated(objModel(model, variant.custom, textures, null));
                }
                if (model.isItemLayers()) {
                    dropped.add("an item layer model as a block model");
                    return null;
                }
                // Forge 1.12 handed custom data only to its own model loaders; a JSON model
                // ignored it, so it is ignored here too.
                if (textures.isEmpty()) return model.id();
                // Textures go in a small child of the original model, so the mod's own models are
                // never edited. It keeps texture paths as written, 1.12 ones such as blocks/ruby
                // included; the texture converters later in the order repoint them.
                JsonObject child = new JsonObject();
                child.addProperty("parent", model.id());
                child.add("textures", texturesJson(textures));
                return generated(child);
            }

            /** The item model an {@code inventory} variant described. */
            private JsonObject itemModel(ForgeVariant variant) throws Unsupported {
                ModelId model = forgeModel(variant.model);
                if (model == null) return null;
                String unsupported = unsupportedReason(model);
                if (unsupported != null) {
                    dropped.add(unsupported);
                    return null;
                }
                // Forge's transform presets become the vanilla parent with the same display, never
                // copied numbers. A custom transform is dropped, so the item keeps its model's own.
                String preset = variant.placement == null ? null : variant.placement.preset();
                if (model.isItemLayers()) {
                    String parent = switch (preset == null ? "identity" : preset) {
                        case "forge:default-item" -> "minecraft:item/generated";
                        case "forge:default-tool" -> "minecraft:item/handheld";
                        case "identity" -> "minecraft:builtin/generated";
                        default -> null;
                    };
                    if (parent == null) {
                        dropped.add("an item layer model with a block transform");
                        return null;
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("parent", parent);
                    Map<String, String> textures = variant.textureOverrides(false);
                    if (!textures.isEmpty()) item.add("textures", texturesJson(textures));
                    return item;
                }
                // An OBJ item model is written out in full; any other model becomes the item's parent.
                if (model.isObj()) {
                    String parent = "forge:default-block".equals(preset) ? "minecraft:block/block" : null;
                    return objModel(model, variant.custom, variant.textureOverrides(true), parent);
                }
                String generated = modelFor(variant);
                if (generated == null) return null;
                JsonObject item = new JsonObject();
                item.addProperty("parent", generated);
                return item;
            }

            /** Forge 1.12 and 1.13 read a v1 model name under models/block/, 1.14 as written. */
            private ModelId forgeModel(String written) {
                if (written == null) return null;
                ModelId id = ModelId.parse(written);
                if (id == null || !forgePrefixesBlock) return id;
                // Forge registered block/builtin/generated as its item layer model.
                if (id.isItemLayers()) return id;
                return new ModelId(id.namespace(), "block/" + id.path());
            }
        }

        /** The OBJ loader id on this host, or null when it has none this converter can write for. */
        private static String objLoader(MigrationContext context) {
            if (!context.targetAtLeast(FIRST_OBJ_LOADER_HOST)) return null;
            // NeoForge took its own namespace with 20.2; its 1.20.1 build still registers forge:obj.
            return switch (context.targetLoader()) {
                case NEOFORGE -> context.targetAtLeast(FIRST_NEOFORGE_NAMESPACE_HOST)
                        ? "neoforge:obj" : "forge:obj";
                case FORGE -> "forge:obj";
                default -> null;
            };
        }

        /** Reads a file leniently, as the old game did, or returns null when it is not a JSON object. */
        private JsonObject readObject(String name) {
            try {
                byte[] bytes = ModDataMigrator.normalizeLenientJson(tree.read(name, MAX_JSON_BYTES));
                JsonSecurity.validate(bytes, MAX_JSON_BYTES, MAX_JSON_DEPTH, "Blockstate JSON " + name);
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                JsonElement root = JsonParser.parseString(text);
                return root.isJsonObject() ? root.getAsJsonObject() : null;
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }
    }

    private static JsonObject texturesJson(Map<String, String> textures) {
        JsonObject json = new JsonObject();
        textures.forEach(json::addProperty);
        return json;
    }

    private static byte[] json(JsonObject value) {
        return (GSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** What one blockstate becomes: null fields are left as they are. */
    private record Result(JsonObject blockState, Map<String, JsonObject> models, JsonObject itemModel,
                          List<String> dropped) {}

    /** A shape Forge read that has no exact 1.13+ equivalent, so the file stays as shipped. */
    private static final class Unsupported extends Exception {
        Unsupported(String reason) {
            // No stack trace: this is an expected outcome, and its message is the reason logged.
            super(reason, null, false, false);
        }
    }

    /** A model name split into namespace and path; an unnamespaced name means minecraft. */
    private record ModelId(String namespace, String path) {
        static ModelId parse(String written) {
            if (written == null || written.isEmpty()) return null;
            int colon = written.indexOf(':');
            return colon < 0 ? new ModelId("minecraft", written)
                    : new ModelId(written.substring(0, colon), written.substring(colon + 1));
        }

        String id() {
            return namespace + ":" + path;
        }

        /** The same namespace, written out, with another path. */
        String withPath(String newPath) {
            return namespace + ":" + newPath;
        }

        boolean isValid() {
            return NAMESPACE.matcher(namespace).matches() && IDENTIFIER_PATH.matcher(path).matches();
        }

        boolean isObj() {
            return path.endsWith(".obj");
        }

        /** Whether Forge 1.12 loaded this file through one of its own model loaders. */
        boolean usesForgeLoader() {
            return isObj() || path.endsWith(".b3d");
        }

        /** Whether this is {@code builtin/generated}, the item layer model. */
        boolean isItemLayers() {
            return namespace.equals("minecraft") && path.equals("builtin/generated");
        }

        /** Whether the path already starts with a 1.13 model folder. */
        boolean namesCurrentFolder() {
            return path.startsWith("block/") || namespace.equals("minecraft")
                    && (path.startsWith("item/") || path.startsWith("builtin/"));
        }
    }

    /** Where a Forge variant puts its model: an x and y rotation, or a transform. */
    private record Placement(Rotation rotation, String preset, boolean movesBlock) {
        static Placement parse(JsonObject json, Placement current) throws Unsupported {
            Placement placement = current;
            if (json.has("x") || json.has("y")) {
                placement = new Placement(Rotation.of(intValue(json, "x"), intValue(json, "y")), null, false);
            }
            // Forge kept both in one slot, so a transform replaces the rotation of the same variant.
            // A transform that only changes how an item is held or shown leaves the block in place.
            if (json.has("transform")) {
                JsonElement transform = json.get("transform");
                if (transform instanceof JsonPrimitive name && name.isString()) {
                    if (!TRANSFORM_PRESETS.contains(name.getAsString())) {
                        throw new Unsupported("it names an unknown transform " + name.getAsString());
                    }
                    placement = new Placement(Rotation.NONE, name.getAsString(), false);
                } else if (transform instanceof JsonObject object) {
                    boolean moves = !PERSPECTIVE_KEYS.containsAll(object.keySet());
                    placement = new Placement(Rotation.NONE, null, moves);
                } else {
                    placement = new Placement(Rotation.NONE, null, true);
                }
            }
            return placement;
        }

        private static int intValue(JsonObject json, String key) throws Unsupported {
            if (!json.has(key)) return 0;
            try {
                return json.get(key).getAsInt();
            } catch (RuntimeException notANumber) {
                throw new Unsupported("a rotation is not a number");
            }
        }
    }

    /** A rotation in 90 degree steps about x, then y, as vanilla and Forge apply it. */
    private record Rotation(int x, int y) {
        static final Rotation NONE = new Rotation(0, 0);

        static Rotation of(int x, int y) throws Unsupported {
            int normalX = Math.floorMod(x, 360);
            int normalY = Math.floorMod(y, 360);
            if (normalX % 90 != 0 || normalY % 90 != 0) {
                throw new Unsupported("a rotation is not a multiple of 90 degrees");
            }
            return new Rotation(normalX, normalY);
        }

        /** This rotation after {@code inner} as one vanilla rotation, or null when it needs a z rotation. */
        Rotation then(Rotation inner) {
            int[] combined = multiply(matrix(), inner.matrix());
            // Find the x and y pair whose matrix matches the combined one.
            for (int cx = 0; cx < 360; cx += 90) {
                for (int cy = 0; cy < 360; cy += 90) {
                    if (Arrays.equals(new Rotation(cx, cy).matrix(), combined)) return new Rotation(cx, cy);
                }
            }
            return null;
        }

        /** The 3 by 3 rotation matrix, row by row: x first, then y. */
        private int[] matrix() {
            return multiply(aboutY(y), aboutX(x));
        }

        private static int[] aboutX(int degrees) {
            int c = cos(degrees);
            int s = sin(degrees);
            return new int[] {1, 0, 0, 0, c, -s, 0, s, c};
        }

        private static int[] aboutY(int degrees) {
            int c = cos(degrees);
            int s = sin(degrees);
            return new int[] {c, 0, s, 0, 1, 0, -s, 0, c};
        }

        private static int cos(int degrees) {
            return switch (degrees) {
                case 0 -> 1;
                case 180 -> -1;
                default -> 0;
            };
        }

        private static int sin(int degrees) {
            return switch (degrees) {
                case 90 -> 1;
                case 270 -> -1;
                default -> 0;
            };
        }

        private static int[] multiply(int[] a, int[] b) {
            int[] product = new int[9];
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    int sum = 0;
                    for (int k = 0; k < 3; k++) sum += a[row * 3 + k] * b[k * 3 + column];
                    product[row * 3 + column] = sum;
                }
            }
            return product;
        }
    }

    /** Variant keys, which Forge matched as exact strings and 1.13+ matches as conditions. */
    private static final class StateKey {
        private StateKey() {
        }

        /** The property values a key names, or null for a key that is not property=value pairs. */
        static Map<String, String> parse(String key) {
            Map<String, String> values = new LinkedHashMap<>();
            if (key.equals("normal") || key.isEmpty()) return values;
            for (String pair : key.split(",", -1)) {
                int equals = pair.indexOf('=');
                if (equals <= 0 || equals == pair.length() - 1) return null;
                if (values.put(pair.substring(0, equals), pair.substring(equals + 1)) != null) return null;
            }
            return values;
        }

        static String vanillaKey(String key) {
            return key.equals("normal") ? "" : key;
        }

        /** The keys to write, given what each one renders. */
        static List<String> distinct(Map<String, String> renderings) throws Unsupported {
            // 1.13+ refuses two keys that match one state even when they agree. So a key is dropped
            // when a more general key, one whose every property value it repeats, renders the same:
            // each state it matched still gets that output.
            List<String> kept = new ArrayList<>();
            for (Map.Entry<String, String> key : renderings.entrySet()) {
                Map<String, String> values = parse(key.getKey());
                boolean covered = false;
                for (Map.Entry<String, String> other : renderings.entrySet()) {
                    Map<String, String> general = parse(other.getKey());
                    covered |= general.size() < values.size()
                            && values.entrySet().containsAll(general.entrySet())
                            && other.getValue().equals(key.getValue());
                }
                if (!covered) kept.add(key.getKey());
            }
            // Any other overlap, such as keys that render differently, leaves the file alone.
            requireNoOverlap(kept);
            return kept;
        }

        /** Two keys overlap when no property they share has different values in them. */
        private static void requireNoOverlap(List<String> keys) throws Unsupported {
            List<Map<String, String>> parsed = new ArrayList<>();
            for (String key : keys) parsed.add(parse(key));
            for (int i = 0; i < parsed.size(); i++) {
                for (int j = i + 1; j < parsed.size(); j++) {
                    if (overlap(parsed.get(i), parsed.get(j))) {
                        throw new Unsupported("its variant keys '" + keys.get(i) + "' and '"
                                + keys.get(j) + "' would both match one state");
                    }
                }
            }
        }

        private static boolean overlap(Map<String, String> first, Map<String, String> second) {
            for (Map.Entry<String, String> value : first.entrySet()) {
                String other = second.get(value.getKey());
                if (other != null && !other.equals(value.getValue())) return false;
            }
            return true;
        }

        /** A multipart condition matching any of {@code conditions}, or null for every state. */
        static JsonObject when(List<Map<String, String>> conditions) {
            List<JsonObject> terms = new ArrayList<>();
            // An empty condition matches every state, so the part needs no condition at all.
            for (Map<String, String> condition : conditions) {
                if (condition.isEmpty()) return null;
                JsonObject term = new JsonObject();
                condition.forEach(term::addProperty);
                terms.add(term);
            }
            if (terms.size() == 1) return terms.get(0);
            JsonArray any = new JsonArray();
            terms.forEach(any::add);
            JsonObject or = new JsonObject();
            or.add("OR", any);
            return or;
        }
    }

    /** One Forge v1 variant: what its JSON object sets, then what it inherits. */
    private static final class ForgeVariant {
        String model;
        // Whether the JSON named a model, even null, so a parent's model does not replace it.
        boolean modelSet;
        Placement placement;
        Boolean uvlock;
        Integer weight;
        final Map<String, String> textures = new LinkedHashMap<>();
        final Map<String, JsonElement> custom = new LinkedHashMap<>();
        /** Named parts; a null list removes a part the variant would otherwise inherit. */
        final Map<String, List<ForgeVariant>> submodels = new LinkedHashMap<>();
        /** Parts declared with the short {@code "submodel": "name"} form, by state key. */
        final Set<String> simpleSubmodels = new LinkedHashSet<>();

        /** A deep copy, so inheriting into it never changes the original. */
        ForgeVariant copy() {
            ForgeVariant copy = new ForgeVariant();
            copy.model = model;
            copy.modelSet = modelSet;
            copy.placement = placement;
            copy.uvlock = uvlock;
            copy.weight = weight;
            copy.textures.putAll(textures);
            copy.custom.putAll(custom);
            submodels.forEach((name, parts) -> copy.submodels.put(name, copyAll(parts)));
            copy.simpleSubmodels.addAll(simpleSubmodels);
            return copy;
        }

        /** Fills every field this variant left unset from {@code parent}, as Forge's own merge did. */
        ForgeVariant inherit(ForgeVariant parent) {
            if (!modelSet) {
                model = parent.model;
                modelSet = parent.modelSet;
            }
            if (placement == null) placement = parent.placement;
            if (uvlock == null) uvlock = parent.uvlock;
            if (weight == null) weight = parent.weight;
            parent.textures.forEach(textures::putIfAbsent);
            parent.submodels.forEach((name, parts) -> {
                if (!submodels.containsKey(name)) submodels.put(name, copyAll(parts));
            });
            simpleSubmodels.addAll(parent.simpleSubmodels);
            parent.custom.forEach(custom::putIfAbsent);
            return this;
        }

        /** The rotation of the placed block; a transform that moves it has no vanilla form. */
        Rotation blockRotation() throws Unsupported {
            if (placement == null) return Rotation.NONE;
            if (placement.movesBlock()) throw new Unsupported("a transform moves the placed block");
            return placement.rotation();
        }

        /** The textures a generated model sets. */
        Map<String, String> textureOverrides(boolean obj) {
            // A null texture removed one in Forge, which a child model cannot do, so it is skipped.
            // OBJ material overrides (#name) belong in the material file and are skipped too.
            Map<String, String> overrides = new LinkedHashMap<>();
            textures.forEach((key, value) -> {
                if (!value.isEmpty() && !(obj && key.startsWith("#"))) overrides.put(key, value);
            });
            return overrides;
        }

        private static List<ForgeVariant> copyAll(List<ForgeVariant> parts) {
            if (parts == null) return null;
            List<ForgeVariant> copies = new ArrayList<>();
            for (ForgeVariant part : parts) copies.add(part.copy());
            return copies;
        }

        /** Expands a v1 file into its final variants by state key. */
        static Map<String, List<ForgeVariant>> expand(JsonObject root) throws Unsupported {
            ForgeVariant defaults = null;
            if (root.has("defaults")) {
                defaults = parse(root.get("defaults"), null);
                if (!defaults.simpleSubmodels.isEmpty()) {
                    throw new Unsupported("its defaults declare a simple submodel");
                }
            }
            if (!(root.get("variants") instanceof JsonObject variants)) {
                throw new Unsupported("it has no variants object");
            }
            // An array lists the options of one fully written key. A map whose first value is an
            // object lists the values of one property. Any other object is one fully written key.
            Map<String, Map<String, ForgeVariant>> properties = new LinkedHashMap<>();
            Map<String, List<ForgeVariant>> written = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                if (value instanceof JsonArray options) {
                    List<ForgeVariant> parsed = written.computeIfAbsent(key, ignored -> new ArrayList<>());
                    for (JsonElement option : options) parsed.add(parse(option, key));
                } else if (value instanceof JsonObject object && object.size() > 0
                        && object.entrySet().iterator().next().getValue().isJsonObject()) {
                    Map<String, ForgeVariant> values = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> option : object.entrySet()) {
                        values.put(option.getKey(), parse(option.getValue(), key + "=" + option.getKey()));
                    }
                    properties.put(key, values);
                } else if (value instanceof JsonObject object && object.size() > 0) {
                    written.computeIfAbsent(key, ignored -> new ArrayList<>()).add(parse(object, key));
                } else {
                    throw new Unsupported("its variant '" + key + "' is not a variant or property map");
                }
            }

            // Every combination of the property maps is a state. A fully written key then takes
            // what it leaves unset from the matching combination.
            Map<String, List<ForgeVariant>> states = combinations(properties);
            for (Map.Entry<String, List<ForgeVariant>> full : written.entrySet()) {
                List<ForgeVariant> base = states.get(full.getKey());
                List<ForgeVariant> merged = new ArrayList<>();
                for (ForgeVariant option : full.getValue()) {
                    if (base == null || base.isEmpty()) {
                        merged.add(option);
                    } else {
                        for (ForgeVariant combination : base) merged.add(option.copy().inherit(combination));
                    }
                }
                states.put(full.getKey(), merged);
            }
            // The defaults fill the rest last.
            for (List<ForgeVariant> options : states.values()) {
                for (ForgeVariant state : options) state.finish(defaults);
            }
            return states;
        }

        /** Every combination of the property maps, keyed like {@code a=1,b=2}. */
        private static Map<String, List<ForgeVariant>> combinations(
                Map<String, Map<String, ForgeVariant>> properties) throws Unsupported {
            long count = 1;
            for (Map<String, ForgeVariant> values : properties.values()) {
                count *= Math.max(1, values.size());
                if (count > MAX_STATE_KEYS) throw new Unsupported("it has more than " + MAX_STATE_KEYS + " states");
            }
            // Properties go in name order, and the earlier one wins a field both set.
            List<String> names = new ArrayList<>(properties.keySet());
            Collections.sort(names);
            Map<String, List<ForgeVariant>> states = new LinkedHashMap<>();
            if (!names.isEmpty()) combine(names, properties, 0, "", null, states);
            return states;
        }

        /** Builds the combinations depth first, one property per level. */
        private static void combine(List<String> names, Map<String, Map<String, ForgeVariant>> properties,
                                    int depth, String prefix, ForgeVariant head,
                                    Map<String, List<ForgeVariant>> states) {
            if (depth == names.size()) {
                if (head != null) states.computeIfAbsent(prefix, ignored -> new ArrayList<>()).add(head);
                return;
            }
            String name = names.get(depth);
            for (Map.Entry<String, ForgeVariant> value : properties.get(name).entrySet()) {
                ForgeVariant next = head == null ? value.getValue().copy()
                        : head.copy().inherit(value.getValue());
                combine(names, properties, depth + 1,
                        prefix + (depth == 0 ? "" : ",") + name + "=" + value.getKey(), next, states);
            }
        }

        /** The last steps Forge took per state. */
        private void finish(ForgeVariant defaults) throws Unsupported {
            // The defaults first, then each short-form part takes the state's fields but keeps its
            // own rotation. Forge 1.12 ran these two only for a file with defaults; without them a
            // short-form part keeps just what its own object sets.
            if (defaults != null) {
                inherit(defaults);
                for (String simple : simpleSubmodels) {
                    List<ForgeVariant> parts = submodels.get(simple);
                    if (parts == null || parts.size() != 1) continue;
                    ForgeVariant part = parts.get(0);
                    Placement own = part.placement;
                    part.inherit(withoutParts());
                    part.placement = own;
                }
            }
            // Parts removed with null are gone now that nothing inherits any more.
            simpleSubmodels.clear();
            submodels.values().removeIf(parts -> parts == null);
            // Textures named as #key are looked up in this state's own textures.
            for (Map.Entry<String, String> texture : textures.entrySet()) {
                String value = texture.getValue();
                if (!value.startsWith("#")) continue;
                String resolved = textures.get(value.substring(1));
                if (resolved == null) throw new Unsupported("a texture names " + value + ", which it never sets");
                texture.setValue(resolved);
            }
            // Every part takes the state's textures it does not set.
            for (List<ForgeVariant> parts : submodels.values()) {
                for (ForgeVariant part : parts) textures.forEach(part.textures::putIfAbsent);
            }
        }

        /** A copy of this variant's own fields, without its parts. */
        private ForgeVariant withoutParts() {
            ForgeVariant fields = copy();
            fields.submodels.clear();
            fields.simpleSubmodels.clear();
            return fields;
        }

        /** Reads one variant object. */
        static ForgeVariant parse(JsonElement element, String stateKey) throws Unsupported {
            if (!(element instanceof JsonObject json)) throw new Unsupported("a variant is not an object");
            if (json.has("smooth_lighting") || json.has("gui3d")) {
                throw new Unsupported("it sets smooth_lighting or gui3d");
            }
            ForgeVariant variant = new ForgeVariant();
            if (json.has("model")) {
                JsonElement model = json.get("model");
                variant.model = model.isJsonNull() ? null : string(model, "a model name");
                variant.modelSet = true;
            }
            if (json.has("textures")) {
                if (!(json.get("textures") instanceof JsonObject textures)) {
                    throw new Unsupported("its textures are not an object");
                }
                // A null texture is kept as empty, which marks one Forge removed.
                for (Map.Entry<String, JsonElement> texture : textures.entrySet()) {
                    variant.textures.put(texture.getKey(), texture.getValue().isJsonNull()
                            ? "" : string(texture.getValue(), "a texture"));
                }
            }
            variant.placement = Placement.parse(json, null);
            if (json.has("uvlock")) variant.uvlock = bool(json.get("uvlock"));
            if (json.has("weight")) variant.weight = integer(json.get("weight"));
            if (json.has("submodel")) {
                JsonElement submodel = json.get("submodel");
                // A short-form submodel ("submodel": "name") turns the object into a part named after
                // its state key, as Forge did. Forge stopped reading the object there, so its custom
                // data is ignored.
                if (submodel instanceof JsonPrimitive simple) {
                    if (stateKey == null) throw new Unsupported("a short-form submodel is outside a state");
                    variant.model = simple.getAsString();
                    variant.modelSet = true;
                    ForgeVariant holder = new ForgeVariant();
                    holder.submodels.put(stateKey, new ArrayList<>(List.of(variant)));
                    holder.simpleSubmodels.add(stateKey);
                    return holder;
                }
                if (!(submodel instanceof JsonObject parts)) throw new Unsupported("a submodel is not an object");
                for (Map.Entry<String, JsonElement> part : parts.entrySet()) {
                    variant.submodels.put(part.getKey(), parts(part.getValue()));
                }
            }
            if (json.has("custom")) {
                if (!(json.get("custom") instanceof JsonObject custom)) {
                    throw new Unsupported("its custom data is not an object");
                }
                custom.entrySet().forEach(entry -> variant.custom.put(entry.getKey(),
                        entry.getValue() == null ? JsonNull.INSTANCE : entry.getValue()));
            }
            return variant;
        }

        /** A submodel's parts: one object or a weighted list, or null to remove an inherited part. */
        private static List<ForgeVariant> parts(JsonElement value) throws Unsupported {
            if (value.isJsonNull()) return null;
            List<ForgeVariant> parts = new ArrayList<>();
            if (value instanceof JsonArray options) {
                for (JsonElement option : options) parts.add(parse(option, null));
            } else {
                parts.add(parse(value, null));
            }
            for (ForgeVariant part : parts) {
                if (!part.submodels.isEmpty()) throw new Unsupported("it nests submodels");
            }
            return parts;
        }

        private static String string(JsonElement value, String what) throws Unsupported {
            if (value instanceof JsonPrimitive primitive) return primitive.getAsString();
            throw new Unsupported(what + " is not a string");
        }

        private static boolean bool(JsonElement value) throws Unsupported {
            if (value instanceof JsonPrimitive primitive && primitive.isBoolean()) return primitive.getAsBoolean();
            throw new Unsupported("uvlock is not true or false");
        }

        private static int integer(JsonElement value) throws Unsupported {
            try {
                return value.getAsInt();
            } catch (RuntimeException notANumber) {
                throw new Unsupported("a weight is not a number");
            }
        }
    }
}
