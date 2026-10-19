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
import com.google.gson.stream.JsonReader;
import com.retromod.core.RetromodVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes and repairs client item definitions for mods whose item models predate 1.21.4. */
public final class ClientItemDefinitionMigrator implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");

    /** The first host that reads client item definitions and ignores model overrides. */
    static final String FIRST_DEFINITION_HOST = "1.21.4";
    /** The first host without {@code item/template_spawn_egg} and its two textures. */
    static final String FIRST_HOST_WITHOUT_EGG_TEMPLATE = "1.21.5";
    /** The first host without the {@code minecraft:map_color} tint. */
    static final String FIRST_HOST_WITHOUT_MAP_COLOR = "26.3";

    private static final String TEMPLATE_SPAWN_EGG = "minecraft:item/template_spawn_egg";
    private static final String BUILTIN_ENTITY = "minecraft:builtin/entity";
    private static final String MAP_COLOR = "minecraft:map_color";

    // The item model type ids every host from 1.21.4 to 26.3 registers, read from the ItemModels class
    // of the 1.21.4, 1.21.5, 1.21.6, 1.21.9, 1.21.11, 26.1, 26.2 and 26.3 client jars. A minecraft:
    // type outside this set fails the whole definition.
    private static final Set<String> MODEL_TYPES = Set.of("minecraft:model", "minecraft:condition",
            "minecraft:select", "minecraft:range_dispatch", "minecraft:composite", "minecraft:special",
            "minecraft:empty", "minecraft:bundle/selected_item");

    /** A neutral shell and darker spots, for an egg whose colors its code does not show. */
    static final int[] NEUTRAL_EGG = {0xFFB4AFA5, 0xFF6B675F};

    private static final long MAX_MODEL_BYTES = 4L * 1024 * 1024;
    private static final int MAX_PARENT_DEPTH = 32;
    private static final Pattern ITEM_MODEL =
            Pattern.compile("assets/([a-z0-9_.-]+)/models/item/([a-z0-9_.-]+)\\.json");
    private static final Pattern ITEM_DEFINITION =
            Pattern.compile("assets/([a-z0-9_.-]+)/items/([a-z0-9_./-]+)\\.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public String name() {
        return "item definitions";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.targetAtLeast(FIRST_DEFINITION_HOST);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        // The jar's classes and any the context can read, for the spawn egg colors set in code.
        Set<String> classNames = new LinkedHashSet<>();
        for (String name : tree.names()) {
            if (name.endsWith(".class")) classNames.add(name);
        }
        classNames.addAll(context.classNames());
        Function<String, byte[]> readClass = name -> tree.exists(name)
                ? readQuietly(tree, name, ResourceTree.DEFAULT_MAX_READ_BYTES) : context.readClass(name);
        // A mod built for 1.21.4 or newer already saw its overrides ignored, so translating them
        // would change how it looked on its own version. The missing definition the converter
        // writes for is itself evidence of an older mod, so an unknown source still translates.
        boolean translateOverrides = !atLeast(context.sourceMcVersion(), FIRST_DEFINITION_HOST);
        // A definition shipped by a mod known to predate 1.21.4 was never read on its own version,
        // where the model's overrides decided the look, so a plain one may give way to them.
        boolean shippedOverridesUnseen = context.sourceBefore(FIRST_DEFINITION_HOST);
        return new Run(tree, context.targetMcVersion(), context.ownNamespaces(), translateOverrides,
                shippedOverridesUnseen, classNames, readClass).write();
    }

    /** Writes the definitions for a caller without a {@link MigrationContext}, so with no egg colors. */
    static int write(ResourceTree tree, String targetMcVersion, Set<String> ownNamespaces) throws IOException {
        if (!atLeast(targetMcVersion, FIRST_DEFINITION_HOST)) return 0;
        // A null ownNamespaces writes in every namespace but the shared ones.
        return new Run(tree, targetMcVersion, ownNamespaces, true, false, Set.of(), name -> null).write();
    }

    /** A model reference as a full id. */
    static String modelId(String reference) {
        // Old models and overrides wrote vanilla references without a namespace, as item/generated.
        String id = reference.trim().toLowerCase(java.util.Locale.ROOT);
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    /** One mod's definitions, with the models it read and the files it will add. */
    private static final class Run {
        private final ResourceTree tree;
        private final String target;
        private final Set<String> ownNamespaces;
        private final boolean translateOverrides;
        private final boolean shippedOverridesUnseen;
        private final Collection<String> classNames;
        private final Function<String, byte[]> readClass;

        private final Map<String, JsonObject> models = new HashMap<>();
        private SortedSet<String> names = new TreeSet<>();
        private final Map<String, byte[]> generated = new LinkedHashMap<>();
        private final Set<String> eggIds = new TreeSet<>();
        private Map<String, int[]> eggColors = Map.of();
        private boolean eggArtUsed;
        /** Texture id to the item models whose layer0 or particle shows it, built on first use. */
        private Map<String, Set<String>> iconUsers;

        // What the run did and could not do, for the log at the end.
        private final Set<String> translated = new TreeSet<>();
        private final Map<String, Set<String>> lostVariants = new TreeMap<>();
        private final Map<String, Set<String>> codeRenderedVariants = new TreeMap<>();
        private final Set<String> eggs = new TreeSet<>();
        private final Set<String> ownEggArt = new TreeSet<>();
        private final Set<String> icons = new TreeSet<>();
        private final Set<String> withoutIcon = new TreeSet<>();
        private final Set<String> sharedParticles = new TreeSet<>();
        private final Set<String> repaired = new TreeSet<>();
        private final Set<String> notDefinitions = new TreeSet<>();

        Run(ResourceTree tree, String target, Set<String> ownNamespaces, boolean translateOverrides,
            boolean shippedOverridesUnseen, Collection<String> classNames, Function<String, byte[]> readClass) {
            this.tree = tree;
            this.target = target;
            this.ownNamespaces = ownNamespaces;
            this.translateOverrides = translateOverrides;
            this.shippedOverridesUnseen = shippedOverridesUnseen;
            this.classNames = classNames;
            this.readClass = readClass;
        }

        /** Writes every definition and generated file, and returns how many files it wrote. */
        int write() throws IOException {
            // 1.21.4 needs assets/<ns>/items/<id>.json for every item, and an item without one shows
            // the missing model. An old mod ships only models/item/<id>.json, so each such model gets one.
            names = tree.names();
            Map<String, String> missing = new TreeMap<>();
            Map<String, String> shipped = new TreeMap<>();
            Set<String> keptVanilla = new TreeSet<>();
            for (String name : names) {
                Matcher model = ITEM_MODEL.matcher(name);
                if (model.matches()) {
                    String namespace = model.group(1);
                    String definition = "assets/" + namespace + "/items/" + model.group(2) + ".json";
                    if (namespace.equals("minecraft")) keptVanilla.add("minecraft:" + model.group(2));
                    if (writable(namespace) && !names.contains(definition)) {
                        missing.put(namespace + ":" + model.group(2), definition);
                    }
                }
                Matcher definition = ITEM_DEFINITION.matcher(name);
                if (definition.matches() && writable(definition.group(1))) {
                    shipped.put(definition.group(1) + ":" + definition.group(2), name);
                }
            }
            // Spawn eggs on the old template take their colors from the mod's code, read in one pass.
            for (String item : missing.keySet()) {
                if (chain(itemModel(item)).spawnEggTemplate()) eggIds.add(item);
            }
            for (String item : shipped.keySet()) {
                if (chain(itemModel(item)).spawnEggTemplate()) eggIds.add(item);
            }
            if (!eggIds.isEmpty() && !classNames.isEmpty()) {
                eggColors = SpawnEggColors.scan(classNames, readClass, eggIds);
            }

            // New definitions for missing ones, and repairs for the ones the mod ships.
            Map<String, byte[]> definitions = new LinkedHashMap<>();
            for (Map.Entry<String, String> item : missing.entrySet()) {
                JsonObject model = definitionModel(item.getKey());
                if (model != null) definitions.put(item.getValue(), definition(model));
            }
            for (Map.Entry<String, String> item : shipped.entrySet()) {
                byte[] fixed = repair(item.getValue(), item.getKey());
                if (fixed != null) definitions.put(item.getValue(), fixed);
            }

            // Generated models first. The mod's own models are never edited.
            int written = 0;
            for (Map.Entry<String, byte[]> file : generated.entrySet()) {
                if (tree.exists(file.getKey())) continue; // the mod's own file wins
                tree.write(file.getKey(), file.getValue());
                written++;
            }
            // Retromod's own egg art, under retromod_compat, only when an egg uses it.
            if (eggArtUsed) {
                written += writeIfAbsent(SpawnEggTextures.BASE_FILE, SpawnEggTextures.base());
                written += writeIfAbsent(SpawnEggTextures.SPOTS_FILE, SpawnEggTextures.spots());
            }
            for (Map.Entry<String, byte[]> file : definitions.entrySet()) {
                tree.write(file.getKey(), file.getValue());
                written++;
            }
            report(keptVanilla);
            return written;
        }

        private int writeIfAbsent(String name, byte[] bytes) throws IOException {
            if (tree.exists(name)) return 0;
            tree.write(name, bytes);
            return 1;
        }

        /** The model tree for an item without a definition, or null if it would be the missing model. */
        private JsonObject definitionModel(String item) {
            String base = itemModel(item);
            JsonObject overrides = translateOverrides ? translatedOverrides(item) : null;
            if (overrides != null) return overrides;
            // A code-drawn item with no icon gets no definition, since it could only show the missing model.
            JsonObject leaf = leaf(base, item, null);
            if (leaf == null && chain(base).builtinEntity()) withoutIcon.add(item);
            return leaf;
        }

        /** The item model's overrides as a definition tree, or null when none changes what the item shows. */
        private JsonObject translatedOverrides(String item) {
            String base = itemModel(item);
            JsonObject model = model(base);
            if (model == null || !(model.get("overrides") instanceof JsonArray overrides) || overrides.isEmpty()) {
                return null;
            }
            // A state whose model only code could draw shows the item's own model instead of the
            // missing one, so an item whose own model cannot show gets no tree.
            JsonObject baseLeaf = leaf(base, item, null);
            if (baseLeaf == null) return null;
            // 1.21.4 no longer reads overrides. They become a condition, select or range dispatch
            // tree, so a bow animates and a shield blocks.
            Set<String> codeRendered = new TreeSet<>();
            LegacyItemOverrides.Translation translation = LegacyItemOverrides.translate(overrides, base, reference -> {
                JsonObject leaf = leaf(reference, item, null);
                if (leaf != null) return leaf;
                codeRendered.add(reference);
                return baseLeaf;
            });
            if (!translation.droppedProperties().isEmpty()) {
                lostVariants.put(item, translation.droppedProperties());
            }
            if (!codeRendered.isEmpty()) codeRenderedVariants.put(item, codeRendered);
            if (translation.model() == null || translation.model().equals(baseLeaf)) return null;
            translated.add(item);
            return translation.model();
        }

        /** The node that shows {@code model} for {@code item}, or null when nothing can show it. */
        private JsonObject leaf(String model, String item, JsonArray tints) {
            Chain chain = chain(model);
            if (chain.spawnEggTemplate()) {
                // 1.21.4 moved the egg colors from code into the definition, as tints.
                JsonArray colors = tints != null ? tints : eggTints(item);
                if (colors == null) colors = constantTints(NEUTRAL_EGG);
                if (!atLeast(target, FIRST_HOST_WITHOUT_EGG_TEMPLATE)) {
                    // The template and its textures are still there; vanilla 1.21.4 tints them the same way.
                    return plain(model, colors);
                }
                // 1.21.5 gave each vanilla egg its own full-color texture at this path. A jar that
                // ships one drew its egg already, and tints would recolor it.
                String ownTexture = shippedTexture(itemModel(item));
                if (ownTexture != null) {
                    String egg = generatedModel(itemModel(item), item, "spawn_egg_texture");
                    if (egg == null) return null;
                    generated.putIfAbsent(modelFile(egg), json(iconModel(ownTexture)));
                    ownEggArt.add(item);
                    return plain(egg, null);
                }
                // Otherwise a generated model on Retromod's own egg art, with the tints.
                String egg = generatedModel(model, item, "spawn_egg");
                if (egg == null) return null;
                generated.putIfAbsent(modelFile(egg), json(eggModel()));
                eggArtUsed = true;
                eggs.add(item);
                return plain(egg, colors);
            }
            // 1.21.4 removed builtin/entity. A 3D render needs a renderer registered in code, so the
            // item shows a flat icon from its old model instead.
            if (chain.builtinEntity()) {
                String icon = icon(model, chain, item);
                String flat = icon == null ? null : generatedModel(model, item, "builtin_entity");
                if (flat == null) return null;
                generated.putIfAbsent(modelFile(flat), json(iconModel(icon)));
                icons.add(item);
                return plain(flat, tints);
            }
            return plain(model, tints);
        }

        /** A flat icon for an item drawn by code, or null when the jar ships none that fits. */
        private String icon(String model, Chain chain, String item) {
            // Only a texture the jar ships counts. Code-rendered models often name textures that never
            // existed, such as a mod texture written without its namespace, and an icon on one would
            // still show the missing texture. Try the old model's layer0, then textures/item/<name>.png.
            String layer0 = shippedTexture(chain.texture("layer0"));
            if (layer0 != null) return layer0;
            String namespace = namespace(model);
            String name = model.substring(model.lastIndexOf('/') + 1);
            String named = shippedTexture(namespace + ":item/" + name);
            if (named != null) return named;
            // Last, the particle texture the mod chose to stand for the item when it breaks. It is often
            // borrowed, such as a net's texture for the creatures the net catches, so one another item
            // shows too would make this item pass for that one.
            String particle = shippedTexture(chain.texture("particle"));
            if (particle != null && shownByAnotherItem(particle, item, model)) {
                sharedParticles.add(item);
                return null;
            }
            return particle;
        }

        /** Whether another item's model shows {@code texture} as its layer0 or particle. */
        private boolean shownByAnotherItem(String texture, String item, String model) {
            // Index every item model's layer0 and particle once.
            if (iconUsers == null) {
                iconUsers = new HashMap<>();
                for (String name : names) {
                    Matcher entry = ITEM_MODEL.matcher(name);
                    if (!entry.matches()) continue;
                    String id = entry.group(1) + ":item/" + entry.group(2);
                    Chain chain = chain(id);
                    for (String key : List.of("layer0", "particle")) {
                        String shown = chain.texture(key);
                        if (shown != null) iconUsers.computeIfAbsent(shown, k -> new TreeSet<>()).add(id);
                    }
                }
            }
            // This item's own model, the given model and its overrides' models are not another item.
            Set<String> own = new java.util.HashSet<>(List.of(itemModel(item), model));
            JsonObject base = model(itemModel(item));
            if (base != null && base.get("overrides") instanceof JsonArray overrides) {
                for (JsonElement override : overrides) {
                    if (override instanceof JsonObject entry && entry.get("model") instanceof JsonPrimitive target
                            && target.isString()) {
                        own.add(modelId(target.getAsString()));
                    }
                }
            }
            for (String user : iconUsers.getOrDefault(texture, Set.of())) {
                if (!own.contains(user)) return true;
            }
            return false;
        }

        /** The texture id when the jar ships its png, else null. */
        private String shippedTexture(String id) {
            if (id == null) return null;
            int colon = id.indexOf(':');
            String file = "assets/" + id.substring(0, colon) + "/textures/" + id.substring(colon + 1) + ".png";
            return tree.exists(file) ? id : null;
        }

        private JsonArray eggTints(String item) {
            int[] colors = eggColors.get(item);
            return colors == null ? null : constantTints(colors);
        }

        /** A shipped definition made loadable on the host, or null if it needs nothing or is not one. */
        private byte[] repair(String entry, String item) {
            JsonObject definition = parseObject(readQuietly(tree, entry, MAX_MODEL_BYTES));
            if (definition == null || !(definition.get("model") instanceof JsonObject model)) {
                // Some old mods kept their own JSON here. The mod's code may read it from this path,
                // so it stays, and a definition cannot be written in its place.
                notDefinitions.add(item);
                return null;
            }
            // A mod from before 1.21.4 never saw its definition used, so one naming only the item's
            // own model gets that model's overrides instead.
            JsonObject overrides = shippedOverridesUnseen && namesOnlyItsModel(model, item)
                    ? translatedOverrides(item) : null;
            if (overrides != null) {
                definition.add("model", overrides);
                repaired.add(item);
                return json(definition);
            }
            // Otherwise the definition is kept, with only what the host cannot load or render fixed.
            boolean[] changed = {false};
            JsonElement fixed = repairNode(model, item, changed);
            if (!changed[0]) return null;
            definition.add("model", fixed);
            repaired.add(item);
            return json(definition);
        }

        /** A shipped definition that only shows the item's own model, untinted. */
        private boolean namesOnlyItsModel(JsonObject node, String item) {
            return "minecraft:model".equals(typeOf(node)) && !node.has("tints")
                    && node.get("model") instanceof JsonPrimitive model && model.isString()
                    && modelId(model.getAsString()).equals(itemModel(item));
        }

        /** One node of a shipped definition, repaired for the host, with {@code changed} set on any edit. */
        private JsonElement repairNode(JsonElement element, String item, boolean[] changed) {
            if (!(element instanceof JsonObject node)) return element;
            // A tint type the host removed is dropped: 26.3 has no minecraft:map_color.
            if (atLeast(target, FIRST_HOST_WITHOUT_MAP_COLOR) && node.get("tints") instanceof JsonArray tints) {
                JsonArray kept = new JsonArray();
                for (JsonElement tint : tints) {
                    if (tint instanceof JsonObject source && MAP_COLOR.equals(typeOf(source))) continue;
                    kept.add(tint);
                }
                if (kept.size() != tints.size()) {
                    node.add("tints", kept);
                    changed[0] = true;
                }
            }
            // A model type no host registers is replaced by what its model would get here.
            String type = typeOf(node);
            JsonArray tints = node.get("tints") instanceof JsonArray array ? array : null;
            String reference = node.get("model") instanceof JsonPrimitive model && model.isString()
                    ? modelId(model.getAsString()) : null;
            if (type != null && type.startsWith("minecraft:") && !MODEL_TYPES.contains(type)) {
                JsonObject leaf = reference != null && model(reference) != null ? leaf(reference, item, tints) : null;
                if (leaf == null) return node;
                changed[0] = true;
                return leaf;
            }
            // A model on a removed parent gets the same replacement as a synthesized one.
            if ("minecraft:model".equals(type) && reference != null) {
                Chain chain = chain(reference);
                // The template still renders on 1.21.4, but untinted unless the definition tints it.
                // A mod from before 1.21.4 colored its egg in code and never saw this definition used.
                boolean eggNeedsColors = tints == null && shippedOverridesUnseen;
                if ((chain.spawnEggTemplate() && (atLeast(target, FIRST_HOST_WITHOUT_EGG_TEMPLATE) || eggNeedsColors))
                        || chain.builtinEntity()) {
                    JsonObject leaf = leaf(reference, item, tints);
                    if (leaf != null && !leaf.equals(node)) {
                        changed[0] = true;
                        return leaf;
                    }
                }
                return node;
            }
            if ("minecraft:special".equals(type)) return node; // its "model" is a renderer, not a node
            // Every child node of a condition, select, range dispatch or composite node.
            for (String key : List.of("on_true", "on_false", "fallback")) {
                if (node.has(key)) node.add(key, repairNode(node.get(key), item, changed));
            }
            for (String key : List.of("cases", "entries")) {
                if (!(node.get(key) instanceof JsonArray list)) continue;
                for (JsonElement entry : list) {
                    if (entry instanceof JsonObject holder && holder.has("model")) {
                        holder.add("model", repairNode(holder.get("model"), item, changed));
                    }
                }
            }
            if (node.get("models") instanceof JsonArray parts) {
                for (int i = 0; i < parts.size(); i++) parts.set(i, repairNode(parts.get(i), item, changed));
            }
            return node;
        }

        /** What a model's parent chain inside the jar ends in, and the textures it sets on the way. */
        private record Chain(boolean spawnEggTemplate, boolean builtinEntity, Map<String, String> textures) {
            /** A texture variable, followed through {@code #} references, as a full id. */
            String texture(String key) {
                String value = textures.get(key);
                for (int hops = 0; value != null && value.startsWith("#") && hops < 8; hops++) {
                    value = textures.get(value.substring(1));
                }
                return value == null || value.isEmpty() || value.startsWith("#") ? null : modelId(value);
            }
        }

        /** Follows a model's parents through the jar, collecting the textures set on the way. */
        private Chain chain(String id) {
            Map<String, String> textures = new LinkedHashMap<>();
            Set<String> seen = new java.util.HashSet<>();
            String current = id;
            for (int depth = 0; depth < MAX_PARENT_DEPTH && seen.add(current); depth++) {
                JsonObject model = model(current);
                // A parent the jar does not ship is vanilla's or another mod's, so the chain ends
                // there, and only the removed vanilla parents matter.
                if (model == null) {
                    return new Chain(current.equals(TEMPLATE_SPAWN_EGG), current.equals(BUILTIN_ENTITY),
                            textures);
                }
                // The nearest model's texture wins over its parents'.
                if (model.get("textures") instanceof JsonObject declared) {
                    for (Map.Entry<String, JsonElement> texture : declared.entrySet()) {
                        String value = textureValue(texture.getValue());
                        if (value != null) textures.putIfAbsent(texture.getKey(), value);
                    }
                }
                if (!(model.get("parent") instanceof JsonPrimitive parent) || !parent.isString()) break;
                current = modelId(parent.getAsString());
            }
            return new Chain(false, false, textures);
        }

        /** A model the jar ships, parsed once, or null. */
        private JsonObject model(String id) {
            if (models.containsKey(id)) return models.get(id);
            String file = modelFile(id);
            JsonObject model = file == null || !tree.exists(file) ? null
                    : parseObject(readQuietly(tree, file, MAX_MODEL_BYTES));
            models.put(id, model);
            return model;
        }

        /** Whether this run may write in {@code namespace}. */
        private boolean writable(String namespace) {
            // Never in a shared namespace. A minecraft definition would replace vanilla's for every item
            // of that id, where the mod's own model still applies through vanilla's definition.
            return !MigrationContext.SHARED_NAMESPACES.contains(namespace)
                    && (ownNamespaces == null || ownNamespaces.contains(namespace));
        }

        /** {@code <ns>:item/retromod/<kind>/<path>}, or null outside the namespaces this run writes. */
        private String generatedModel(String model, String item, String kind) {
            // The model's namespace, or the item's when the model is vanilla's or another mod's.
            String namespace = writable(namespace(model)) ? namespace(model) : namespace(item);
            if (!writable(namespace)) return null;
            String path = model.substring(model.indexOf(':') + 1);
            if (path.startsWith("item/")) path = path.substring("item/".length());
            // Under item/retromod/, which no item id can reach.
            String id = namespace + ":item/retromod/" + kind + "/" + path;
            return modelFile(id) == null ? null : id;
        }

        /** Logs what the run changed and what it left as it was. */
        private void report(Set<String> keptVanilla) {
            if (!keptVanilla.isEmpty()) {
                LOGGER.info("Kept vanilla's item definitions for {}; the mod's own models for them "
                        + "still apply, but their old overrides are not translated", keptVanilla);
            }
            if (!translated.isEmpty()) {
                LOGGER.info("Translated the model overrides of {} item(s) into item definitions", translated.size());
            }
            if (!lostVariants.isEmpty()) {
                LOGGER.info("{} item(s) lose the variants that depend on properties only their code computed, "
                        + "which nothing reads since 1.21.4: {}", lostVariants.size(), sample(lostVariants.toString()));
            }
            if (!codeRenderedVariants.isEmpty()) {
                LOGGER.info("{} item(s) show their own model in place of override models only their code could "
                        + "draw: {}", codeRenderedVariants.size(), sample(codeRenderedVariants.toString()));
            }
            if (!eggs.isEmpty()) {
                long colored = eggs.stream().filter(eggColors::containsKey).count();
                LOGGER.info("Gave {} spawn egg(s) Retromod's egg model, {} with the colors from the mod's code "
                        + "and the rest in a neutral color", eggs.size(), colored);
            }
            if (!ownEggArt.isEmpty()) {
                LOGGER.info("Gave {} spawn egg(s) a model on the egg texture the jar ships, since the egg "
                        + "template is gone: {}", ownEggArt.size(), sample(ownEggArt.toString()));
            }
            if (!icons.isEmpty()) {
                LOGGER.info("Showing {} code-rendered item(s) as flat icons, since their 3D renderer "
                        + "needs a code bridge", icons.size());
            }
            if (!sharedParticles.isEmpty()) {
                LOGGER.info("Did not use the particle texture of {} code-rendered item(s) as their icon, since "
                        + "other items show it too: {}", sharedParticles.size(), sample(sharedParticles.toString()));
            }
            if (!withoutIcon.isEmpty()) {
                LOGGER.info("Wrote no definition for {} code-rendered item(s) whose model names no icon texture "
                        + "the jar ships, so they keep the missing model: {}",
                        withoutIcon.size(), sample(withoutIcon.toString()));
            }
            if (!notDefinitions.isEmpty()) {
                LOGGER.info("Left {} file(s) under items/ that hold the mod's own JSON, not item definitions. "
                        + "The host cannot load them, and the items they are named after show the missing "
                        + "model: {}", notDefinitions.size(), sample(notDefinitions.toString()));
            }
            if (!repaired.isEmpty()) {
                LOGGER.info("Repaired {} item definition(s) the mod ships that the host could not load "
                        + "or render: {}", repaired.size(), sample(repaired.toString()));
            }
        }
    }

    /** {@code ns:id} as its item model id, {@code ns:item/id}. */
    private static String itemModel(String item) {
        int colon = item.indexOf(':');
        return item.substring(0, colon) + ":item/" + item.substring(colon + 1);
    }

    private static String namespace(String id) {
        return id.substring(0, id.indexOf(':'));
    }

    /** {@code assets/<ns>/models/<path>.json} for a model id, or null for an id that is not one. */
    private static String modelFile(String id) {
        int colon = id.indexOf(':');
        String namespace = id.substring(0, colon);
        String path = id.substring(colon + 1);
        if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_.-]+(/[a-z0-9_.-]+)*")) return null;
        return "assets/" + namespace + "/models/" + path + ".json";
    }

    /** A {@code minecraft:model} node, tinted when {@code tints} is given. */
    private static JsonObject plain(String model, JsonArray tints) {
        JsonObject leaf = new JsonObject();
        leaf.addProperty("type", "minecraft:model");
        leaf.addProperty("model", model);
        if (tints != null) leaf.add("tints", tints.deepCopy());
        return leaf;
    }

    /** One constant tint per color, in layer order. */
    private static JsonArray constantTints(int[] colors) {
        JsonArray tints = new JsonArray();
        for (int color : colors) {
            JsonObject tint = new JsonObject();
            tint.addProperty("type", "minecraft:constant");
            tint.addProperty("value", color);
            tints.add(tint);
        }
        return tints;
    }

    /** Retromod's egg model: its shell and spots textures as two layers for the tints to color. */
    private static JsonObject eggModel() {
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", SpawnEggTextures.BASE);
        textures.addProperty("layer1", SpawnEggTextures.SPOTS);
        JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:item/generated");
        model.add("textures", textures);
        return model;
    }

    /** A flat item model that shows {@code icon}. */
    private static JsonObject iconModel(String icon) {
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", icon);
        JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:item/generated");
        model.add("textures", textures);
        return model;
    }

    private static byte[] definition(JsonObject model) {
        JsonObject definition = new JsonObject();
        definition.add("model", model);
        return json(definition);
    }

    private static byte[] json(JsonObject object) {
        return (GSON.toJson(object) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** A node's type as a full id, or null. */
    private static String typeOf(JsonObject node) {
        return node.get("type") instanceof JsonPrimitive type && type.isString()
                ? modelId(type.getAsString()) : null;
    }

    /** A texture value as written: a plain string, or the {@code sprite} of the newer object form. */
    private static String textureValue(JsonElement value) {
        if (value instanceof JsonPrimitive primitive && primitive.isString()) return primitive.getAsString();
        if (value instanceof JsonObject object && object.get("sprite") instanceof JsonPrimitive sprite
                && sprite.isString()) {
            return sprite.getAsString();
        }
        return null;
    }

    /** Old JSON as old Minecraft read it: comments, trailing commas, and a byte order mark allowed. */
    static JsonObject parseObject(byte[] bytes) {
        if (bytes == null) return null;
        try {
            String text = new String(ModDataMigrator.normalizeLenientJson(bytes), StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(true);
            JsonElement element = JsonParser.parseReader(reader);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** The file's bytes, or null when it cannot be read. */
    private static byte[] readQuietly(ResourceTree tree, String name, long maxBytes) {
        try {
            return tree.read(name, maxBytes);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** A logged list, cut off after 400 characters. */
    private static String sample(String list) {
        return list.length() > 400 ? list.substring(0, 400) + "...]" : list;
    }

    /** Whether {@code version} is a release at or after {@code floor}; an unknown version is not. */
    private static boolean atLeast(String version, String floor) {
        return version != null && version.matches("\\d+\\.\\d+.*")
                && RetromodVersion.compareMcVersions(version, floor) >= 0;
    }
}
