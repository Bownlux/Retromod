/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Points a mod's models at the vanilla templates and texture slots the host uses now. */
public final class LegacyModelParentMigrator implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    // Mod models inherit their geometry from vanilla templates, such as block/half_slab for a slab
    // or block/pane_post for a pane. Releases renamed several, 1.13 most of them, and a missing
    // parent draws the missing model. This table pairs each removed template with the vanilla model
    // that has the same elements.
    private static final String RESOURCE = "/retromod/model-parent-renames.tsv";
    // Some vanilla models stayed but read other slots, as farmland did in 26.3. The parent still
    // loads, but a texture the mod set in the old slot is no longer drawn. This table lists those.
    private static final String SLOT_RESOURCE = "/retromod/model-slot-renames.tsv";
    private static final Pattern MODEL_PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern SLOT = Pattern.compile("[a-z0-9_]+");
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?");
    private static final List<Rename> RENAMES = load();
    private static final List<SlotRename> SLOT_RENAMES = loadSlotRenames();

    /** One removed template: its replacement, and the slots the replacement names differently. */
    record Rename(String removedIn, String oldParent, String newParent, Map<String, String> addedSlots) {
    }

    /** One kept vanilla model whose geometry reads other slots from {@code changedIn} on. */
    record SlotRename(String changedIn, String model, Map<String, String> addedSlots) {
    }

    static List<Rename> renames() {
        return RENAMES;
    }

    static List<SlotRename> slotRenames() {
        return SLOT_RENAMES;
    }

    @Override
    public String name() {
        return "vanilla model parent renames";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return RENAMES.stream().anyMatch(rename -> crosses(context, rename.removedIn()))
                || SLOT_RENAMES.stream().anyMatch(kept -> crosses(context, kept.changedIn()));
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        // Only models in the mod's own namespaces change. Parents are fixed first, then slots.
        List<String> modelFiles = ModModels.modelFiles(tree.names(), context.ownNamespaces());
        Set<String> changed = new TreeSet<>();
        for (String name : modelFiles) {
            if (renameParent(tree, name, context)) changed.add(name);
        }
        // Read after the renames are written, so a renamed parent is what the chains end at.
        ModModels.Cache models = new ModModels.Cache(tree);
        for (String name : modelFiles) {
            if (passOnRenamedSlots(tree, name, models, context)) changed.add(name);
        }
        if (!changed.isEmpty()) {
            LOGGER.info("Updated {} model(s) for the vanilla templates and texture slots Minecraft {} uses",
                    changed.size(), context.targetMcVersion());
        }
        return changed.size();
    }

    /** Moves a model off a vanilla template the host removed after the mod was built. */
    private static boolean renameParent(ResourceTree tree, String name, MigrationContext context) throws IOException {
        JsonObject model = ModModels.read(tree, name);
        if (model == null) return false;
        String written = ModModels.parent(model);
        String parent = ModModels.qualify(written);
        if (parent == null) return false;

        // The renames run in release order, so a template renamed twice ends at its newest name.
        Map<String, String> addedSlots = new LinkedHashMap<>();
        for (Rename rename : RENAMES) {
            String old = ModModels.DEFAULT_NAMESPACE + ":" + rename.oldParent();
            if (!parent.equals(old) || !crosses(context, rename.removedIn())) continue;
            // A jar that ships the template itself is drawing its own copy, which still loads.
            if (tree.exists(ModModels.modelEntry(old))) break;
            parent = ModModels.DEFAULT_NAMESPACE + ":" + rename.newParent();
            addedSlots.putAll(rename.addedSlots());
        }
        if (parent.equals(ModModels.qualify(written))) return false;

        // Write the parent in the mod's own form, then point each slot the new template names
        // differently at the old one, so textures set further down the chain still resolve.
        model.addProperty("parent", ModModels.sameForm(written, parent));
        addSlotReferences(model, addedSlots);
        ModModels.write(tree, name, model);
        return true;
    }

    /** Gives a model that sets an old slot of a kept vanilla model the new slot as well. */
    private static boolean passOnRenamedSlots(ResourceTree tree, String name, ModModels.Cache models,
                                              MigrationContext context) throws IOException {
        List<Map.Entry<String, JsonObject>> chain = models.chain(ModModels.modelId(name));
        if (chain.isEmpty()) return false;
        JsonObject model = chain.get(0).getValue();
        JsonObject textures = ModModels.textures(model);
        if (textures == null) return false;
        // Elements anywhere on the jar's part of the chain replace the vanilla geometry, slot names included.
        if (chain.stream().anyMatch(entry -> entry.getValue().has("elements"))) return false;
        // The last model the jar ships names the vanilla model the chain inherits.
        String inherited = ModModels.qualify(ModModels.parent(chain.get(chain.size() - 1).getValue()));
        if (inherited == null) return false;

        Map<String, String> addedSlots = new LinkedHashMap<>();
        for (SlotRename kept : SLOT_RENAMES) {
            if (!inherited.equals(ModModels.DEFAULT_NAMESPACE + ":" + kept.model())
                    || !crosses(context, kept.changedIn())) continue;
            // Only a model that sets the old slot itself changes. "#old" on a model that leaves the
            // slot to vanilla would point at a slot the host's vanilla model no longer sets.
            for (Map.Entry<String, String> slot : kept.addedSlots().entrySet()) {
                if (textures.has(slot.getValue()) && !chainSets(chain, slot.getKey())) {
                    addedSlots.put(slot.getKey(), slot.getValue());
                }
            }
        }
        if (addedSlots.isEmpty()) return false;
        addSlotReferences(model, addedSlots);
        ModModels.write(tree, name, model);
        return true;
    }

    private static boolean chainSets(List<Map.Entry<String, JsonObject>> chain, String slot) {
        for (Map.Entry<String, JsonObject> entry : chain) {
            JsonObject textures = ModModels.textures(entry.getValue());
            if (textures != null && textures.has(slot)) return true;
        }
        return false;
    }

    /** Whether the mod was built before {@code changedIn} and the host is that release or newer. */
    private static boolean crosses(MigrationContext context, String changedIn) {
        return context.sourceBefore(changedIn) && context.targetAtLeast(changedIn);
    }

    /** Adds {@code "new": "#old"} for each renamed slot the model does not already set. */
    private static void addSlotReferences(JsonObject model, Map<String, String> addedSlots) {
        if (addedSlots.isEmpty()) return;
        JsonObject textures = ModModels.textures(model);
        if (textures == null) {
            textures = new JsonObject();
            model.add("textures", textures);
        }
        for (Map.Entry<String, String> slot : addedSlots.entrySet()) {
            if (!textures.has(slot.getKey())) textures.addProperty(slot.getKey(), "#" + slot.getValue());
        }
    }

    /** Reads the template renames, checking each row, sorted by the release that removed the template. */
    private static List<Rename> load() {
        List<Rename> renames = new ArrayList<>();
        Set<String> sources = new HashSet<>();
        Set<String> destinations = new HashSet<>();
        for (Row row : rows(RESOURCE, 4)) {
            // Columns: release, old template, new template, slot pairs.
            String[] columns = row.columns();
            for (int i = 1; i <= 2; i++) {
                if (!MODEL_PATH.matcher(columns[i]).matches()) throw malformed(RESOURCE, row.number(), "invalid model path");
            }
            if (columns[1].equals(columns[2])) throw malformed(RESOURCE, row.number(), "a row that renames nothing");
            // A name may return with another meaning, but only one row per release can own it.
            if (!sources.add(columns[0] + "\t" + columns[1])) {
                throw malformed(RESOURCE, row.number(), "'" + columns[1] + "' is listed twice for " + columns[0]);
            }
            // Two templates folding into one would hide a geometry difference between them.
            if (!destinations.add(columns[2])) {
                throw malformed(RESOURCE, row.number(), "'" + columns[2] + "' is a destination twice");
            }
            renames.add(new Rename(columns[0], columns[1], columns[2], slots(columns[3], RESOURCE, row.number())));
        }
        // Release order lets renameParent follow a template that was renamed more than once.
        renames.sort((left, right) -> com.retromod.core.RetromodVersion.compareMcVersions(
                left.removedIn(), right.removedIn()));
        return List.copyOf(renames);
    }

    /** Reads the kept models whose slots changed, checking each row. */
    private static List<SlotRename> loadSlotRenames() {
        List<SlotRename> kept = new ArrayList<>();
        Set<String> models = new HashSet<>();
        for (Row row : rows(SLOT_RESOURCE, 3)) {
            // Columns: release, vanilla model, slot pairs. Each model gets one row per release.
            String[] columns = row.columns();
            if (!MODEL_PATH.matcher(columns[1]).matches()) throw malformed(SLOT_RESOURCE, row.number(), "invalid model path");
            if (!models.add(columns[0] + "\t" + columns[1])) {
                throw malformed(SLOT_RESOURCE, row.number(), "'" + columns[1] + "' is listed twice for " + columns[0]);
            }
            Map<String, String> slots = slots(columns[2], SLOT_RESOURCE, row.number());
            if (slots.isEmpty()) throw malformed(SLOT_RESOURCE, row.number(), "a row that renames no slot");
            kept.add(new SlotRename(columns[0], columns[1], slots));
        }
        return List.copyOf(kept);
    }

    private record Row(int number, String[] columns) {
    }

    /** The table's data rows, each with its line number and a checked release in the first column. */
    private static List<Row> rows(String resource, int columnCount) {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = LegacyModelParentMigrator.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing model table: " + resource);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    // Skip blank lines and # comments, and stop the load on a row of the wrong shape.
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] columns = line.split("\t", -1);
                    if (columns.length != columnCount) {
                        throw malformed(resource, number, "expected " + columnCount + " tab-separated columns");
                    }
                    if (!VERSION.matcher(columns[0]).matches()) throw malformed(resource, number, "invalid version");
                    rows.add(new Row(number, columns));
                }
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        return rows;
    }

    /** Parses a comma-separated list of {@code new=old} slot pairs, or {@code -} for none. */
    private static Map<String, String> slots(String column, String resource, int number) {
        if (column.equals("-")) return Map.of();
        Map<String, String> slots = new LinkedHashMap<>();
        for (String pair : column.split(",")) {
            String[] parts = pair.split("=", -1);
            if (parts.length != 2 || !SLOT.matcher(parts[0]).matches() || !SLOT.matcher(parts[1]).matches()) {
                throw malformed(resource, number, "invalid slot pair '" + pair + "'");
            }
            slots.put(parts[0], parts[1]);
        }
        return java.util.Collections.unmodifiableMap(slots);
    }

    private static IllegalStateException malformed(String resource, int number, String reason) {
        return new IllegalStateException("Malformed " + resource + " at line " + number + ": " + reason);
    }
}
