/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyEquipmentBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes the equipment assets that worn armor renders from since 1.21.2. */
public final class EquipmentAssetMigrator implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");

    /** The first host that reads {@code equipment/}. 1.21.2 and 1.21.3 read {@code models/equipment/}. */
    static final String FIRST_EQUIPMENT_FOLDER_HOST = "1.21.4";
    /** The first version with equipment assets at all. */
    static final String FIRST_EQUIPMENT_ASSET_VERSION = "1.21.2";

    private static final long MAX_ASSET_BYTES = 1024 * 1024;
    private static final Pattern OLD_EQUIPMENT_ASSET =
            Pattern.compile("assets/([a-z0-9_.-]+)/models/equipment/([a-z0-9_./-]+)\\.json");

    @Override
    public String name() {
        return "armor equipment assets";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.targetAtLeast(FIRST_EQUIPMENT_FOLDER_HOST) || context.equipmentAssets();
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        if (tree.names().stream().noneMatch(name -> name.startsWith("assets/"))) return 0;
        // 1.21.4 moved 1.21.2 and 1.21.3 assets to a new folder.
        int written = copyMovedEquipmentAssets(tree, context);
        // The rest follow the armor bridge, which gives the item the equipment asset these files
        // describe. Without it the item is not armor on the host and an asset would be inert.
        if (!context.equipmentAssets()) return written;

        // Classes come from the tree, or from the context on streaming paths whose tree holds
        // only resources.
        Set<String> classNames = new LinkedHashSet<>();
        for (String name : tree.names()) {
            if (name.endsWith(".class")) classNames.add(name);
        }
        classNames.addAll(context.classNames());
        Function<String, byte[]> readClass = name -> tree.exists(name)
                ? readQuietly(tree, name, ResourceTree.DEFAULT_MAX_READ_BYTES) : context.readClass(name);
        // Textures an old Forge armor item chose in getArmorTexture. Their assets stay inert for
        // now: they only resolve 1.12 materials, and no bridge on 1.21.5 or newer builds 1.12 armor yet.
        Map<String, Map<String, String>> chosen =
                inOwnNamespaces(ArmorTextureOverrides.scan(classNames, readClass), context.ownNamespaces());
        // Build assets for old textures/models/armor/<name>_layer_N.png textures and the chosen ones.
        Map<String, byte[]> added = LegacyEquipmentBridge.armorAssetEntries(tree.names(),
                name -> readQuietly(tree, name, ResourceTree.DEFAULT_MAX_READ_BYTES),
                context.targetMcVersion(), chosen);
        for (Map.Entry<String, byte[]> asset : added.entrySet()) {
            tree.write(asset.getKey(), asset.getValue());
        }
        return written + added.size();
    }

    /** The chosen assets whose namespace this jar writes in. */
    private static Map<String, Map<String, String>> inOwnNamespaces(Map<String, Map<String, String>> chosen,
                                                                     Set<String> ownNamespaces) {
        // A 1.12 material may name another mod's namespace, as an addon reusing its parent's armor
        // does. An asset written there would shadow that mod's own equipment/<id>.json for every player.
        Map<String, Map<String, String>> own = new LinkedHashMap<>();
        Set<String> foreign = new TreeSet<>();
        chosen.forEach((asset, layers) -> {
            if (ownNamespaces.contains(asset.substring(0, asset.indexOf(':')))) {
                own.put(asset, layers);
            } else {
                foreign.add(asset);
            }
        });
        if (!foreign.isEmpty()) {
            LOGGER.info("Wrote no equipment asset for {} armor material(s) named in another mod's namespace, "
                    + "whose own assets decide how that armor looks: {}", foreign.size(), foreign);
        }
        return own;
    }

    /** Copies a 1.21.2 or 1.21.3 mod's {@code models/equipment/} assets to {@code equipment/}. */
    private static int copyMovedEquipmentAssets(ResourceTree tree, MigrationContext context) throws IOException {
        if (!context.targetAtLeast(FIRST_EQUIPMENT_FOLDER_HOST)) return 0;
        // 1.21.4 changed the folder, not the format. An unknown source version still gets checked.
        String source = context.sourceMcVersion();
        if (isVersion(source) && (RetromodVersion.compareMcVersions(source, FIRST_EQUIPMENT_ASSET_VERSION) < 0
                || RetromodVersion.compareMcVersions(source, FIRST_EQUIPMENT_FOLDER_HOST) >= 0)) {
            return 0;
        }
        int copied = 0;
        for (String name : tree.names()) {
            Matcher old = OLD_EQUIPMENT_ASSET.matcher(name);
            if (!old.matches() || !context.ownNamespaces().contains(old.group(1))) continue;
            // The old file stays, as a mod's code may read it.
            String moved = "assets/" + old.group(1) + "/equipment/" + old.group(2) + ".json";
            if (tree.exists(moved)) continue;
            // Only a file shaped like an equipment asset moves: older mods used models/equipment/
            // for their own models.
            byte[] bytes = readQuietly(tree, name, MAX_ASSET_BYTES);
            if (!isEquipmentAsset(ClientItemDefinitionMigrator.parseObject(bytes))) continue;
            tree.write(moved, bytes);
            copied++;
        }
        return copied;
    }

    /** {@code {"layers": {"<type>": [{"texture": "..."}, ...], ...}}}, the only shape both versions read. */
    static boolean isEquipmentAsset(JsonObject asset) {
        if (asset == null || !(asset.get("layers") instanceof JsonObject layers) || layers.isEmpty()) return false;
        for (Map.Entry<String, JsonElement> layer : layers.entrySet()) {
            if (!layer.getValue().isJsonArray()) return false;
            for (JsonElement entry : layer.getValue().getAsJsonArray()) {
                if (!(entry instanceof JsonObject object) || !(object.get("texture") instanceof JsonPrimitive texture)
                        || !texture.isString()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isVersion(String version) {
        return version != null && version.matches("\\d+\\.\\d+.*");
    }

    private static byte[] readQuietly(ResourceTree tree, String name, long maxBytes) {
        try {
            return tree.read(name, maxBytes);
        } catch (IOException unreadable) {
            return null;
        }
    }
}
