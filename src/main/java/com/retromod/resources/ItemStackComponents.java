/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.retromod.core.RetromodVersion;
import com.retromod.resources.LegacyItemNbt.Compound;
import com.retromod.resources.LegacyItemNbt.ListTag;
import com.retromod.resources.LegacyItemNbt.Num;
import com.retromod.resources.LegacyItemNbt.Str;
import com.retromod.resources.LegacyItemNbt.Tag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Turns an old item stack's NBT into the data components that replaced it in 1.20.5. */
final class ItemStackComponents {

    // 1.20.5 is the first host that reads item data components instead of item NBT. Every shape here
    // was read from the client jars: 1.20.5 and 1.21.1 for first forms, the host on each constant for
    // the change it names, and 26.3 for the current forms.
    static final String FIRST_COMPONENT_HOST = "1.20.5";
    /** The first host that reads {@code custom_data} as SNBT text as well as a JSON object. */
    static final String FIRST_SNBT_CUSTOM_DATA_HOST = "1.21";
    /** The first host whose {@code custom_model_data} is an object of float lists. */
    static final String FIRST_FLOAT_MODEL_DATA_HOST = "1.21.4";
    /** The first host without per-component {@code show_in_tooltip}, with simpler shapes. */
    static final String FIRST_INLINE_COMPONENT_HOST = "1.21.5";

    /** Keys every item had, which the 1.20.5 world upgrade moved to components. */
    private static final Set<String> GENERIC_KEYS = Set.of("Damage", "RepairCost", "Unbreakable",
            "HideFlags", "display", "Enchantments", "StoredEnchantments", "ench", "AttributeModifiers",
            "CanDestroy", "CanPlaceOn", "CustomModelData", "Trim", "BlockEntityTag", "BlockStateTag",
            "EntityTag", "DebugProperty", "CustomPotionColor", "CustomPotionEffects",
            "custom_potion_effects");

    /** Keys Minecraft read only on some of its own items. On a mod's item they are the mod's own. */
    private static final Set<String> VANILLA_ITEM_KEYS = Set.of("Potion", "Charged",
            "ChargedProjectiles", "Items", "Decorations", "map", "effects", "pages", "filtered_pages",
            "author", "generation", "resolved", "title", "filtered_title", "SkullOwner",
            "LodestonePos", "LodestoneDimension", "LodestoneTracked", "Explosion", "Fireworks",
            "instrument", "BucketVariantTag", "Recipes", "Variant");

    /** Dye color names by their old number, white first. */
    private static final String[] DYE_COLORS = {"white", "orange", "magenta", "light_blue", "yellow",
            "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red",
            "black"};

    // Banner pattern codes to the ids 1.20.5 gave them, in the order the 1.20.3 client registers
    // both. Checked against the hero of the village icon, which 1.20.5 rewrote from codes to ids.
    private static final Map<String, String> BANNER_PATTERNS = pairs(
            "b", "base", "bl", "square_bottom_left", "br", "square_bottom_right",
            "tl", "square_top_left", "tr", "square_top_right", "bs", "stripe_bottom",
            "ts", "stripe_top", "ls", "stripe_left", "rs", "stripe_right", "cs", "stripe_center",
            "ms", "stripe_middle", "drs", "stripe_downright", "dls", "stripe_downleft",
            "ss", "small_stripes", "cr", "cross", "sc", "straight_cross", "bt", "triangle_bottom",
            "tt", "triangle_top", "bts", "triangles_bottom", "tts", "triangles_top",
            "ld", "diagonal_left", "rd", "diagonal_up_right", "lud", "diagonal_up_left",
            "rud", "diagonal_right", "mc", "circle", "mr", "rhombus", "vh", "half_vertical",
            "hh", "half_horizontal", "vhr", "half_vertical_right", "hhb", "half_horizontal_bottom",
            "bo", "border", "cbo", "curly_border", "gra", "gradient", "gru", "gradient_up",
            "bri", "bricks", "glb", "globe", "cre", "creeper", "sku", "skull", "flo", "flower",
            "moj", "mojang", "pig", "piglin");

    // HideFlags bits to the components whose tooltip lines they hid. Bit 32 covered every
    // "additional" line, which 1.21.5's own upgrade expands to this list.
    private static final Map<Integer, List<String>> HIDDEN_BY_FLAG = Map.of(
            1, List.of("minecraft:enchantments"),
            2, List.of("minecraft:attribute_modifiers"),
            4, List.of("minecraft:unbreakable"),
            8, List.of("minecraft:can_break"),
            16, List.of("minecraft:can_place_on"),
            32, List.of("minecraft:stored_enchantments", "minecraft:potion_contents",
                    "minecraft:banner_patterns", "minecraft:bees", "minecraft:block_entity_data",
                    "minecraft:block_state", "minecraft:bundle_contents",
                    "minecraft:charged_projectiles", "minecraft:container",
                    "minecraft:container_loot", "minecraft:firework_explosion", "minecraft:fireworks",
                    "minecraft:instrument", "minecraft:jukebox_playable", "minecraft:map_id",
                    "minecraft:pot_decorations", "minecraft:written_book_content"),
            64, List.of("minecraft:dyed_color"),
            128, List.of("minecraft:trim"));

    /** The first host that names an attribute modifier by an id instead of a UUID and a name. */
    static final String FIRST_MODIFIER_ID_HOST = "1.21";
    /** The first host whose attribute ids lost their {@code generic.} or {@code player.} prefix. */
    static final String FIRST_UNPREFIXED_ATTRIBUTE_HOST = "1.21.2";

    /** The attribute names 1.16 replaced, as 26.3's own world upgrade lists them. */
    private static final Map<String, String> CAMEL_CASE_ATTRIBUTES = pairs(
            "generic.maxHealth", "generic.max_health", "generic.followRange", "generic.follow_range",
            "generic.knockbackResistance", "generic.knockback_resistance",
            "generic.movementSpeed", "generic.movement_speed", "generic.flyingSpeed", "generic.flying_speed",
            "generic.attackDamage", "generic.attack_damage", "generic.attackKnockback", "generic.attack_knockback",
            "generic.attackSpeed", "generic.attack_speed", "generic.armorToughness", "generic.armor_toughness",
            "horse.jumpStrength", "horse.jump_strength", "zombie.spawnReinforcements", "zombie.spawn_reinforcements");

    /** Every attribute an item could name before 1.20.5, in its 1.16 to 1.21.1 spelling. */
    private static final Set<String> ITEM_ATTRIBUTES = Set.of("generic.max_health", "generic.follow_range",
            "generic.knockback_resistance", "generic.movement_speed", "generic.flying_speed",
            "generic.attack_damage", "generic.attack_knockback", "generic.attack_speed", "generic.armor",
            "generic.armor_toughness", "generic.luck", "horse.jump_strength", "zombie.spawn_reinforcements");

    // The two modifier UUIDs a weapon's own damage and speed used. 1.21 gave them fixed ids so the
    // tooltip still adds the player's base value. Every other UUID keeps its own value as its id.
    private static final Map<String, String> BASE_MODIFIER_IDS = Map.of(
            "cb3f55d3-645c-4f38-a497-9c13a33db5cf", "minecraft:base_attack_damage",
            "fa233e1c-4180-4865-b01b-bcce9785aca3", "minecraft:base_attack_speed");

    // Modifier operation names by their old number, and the slots and fields a modifier may have.
    private static final String[] MODIFIER_OPERATIONS = {"add_value", "add_multiplied_base",
            "add_multiplied_total"};
    private static final Set<String> MODIFIER_SLOTS = Set.of("mainhand", "offhand", "head", "chest",
            "legs", "feet");
    private static final Set<String> MODIFIER_KEYS = Set.of("AttributeName", "Name", "Amount", "Operation",
            "UUID", "UUIDMost", "UUIDLeast", "Slot");

    private ItemStackComponents() {
    }

    /** The components for one stack, or null when a Minecraft key cannot carry over. */
    static JsonObject componentize(String itemId, Compound tag, String sourceMcVersion,
                                   String targetMcVersion) {
        // A Minecraft key that cannot carry over exactly, such as CanDestroy or a 1.12 numeric
        // enchantment id, fails the whole stack. The caller then leaves the old NBT in place, so
        // the host skips the file or the load gate keeps it out, instead of the item silently
        // losing what made it special.
        Split split = split(itemId, tag, sourceMcVersion, targetMcVersion);
        if (split == null) return null;
        if (split.customData().entries().isEmpty()) return split.components();
        // The mod's own keys go to minecraft:custom_data unchanged. That is where 1.20.5's own
        // world upgrade put them, and where the mod's bridged code reads them.
        JsonElement customData = customDataValue(split.customData(), targetMcVersion);
        if (customData == null) return null;
        split.components().add("minecraft:custom_data", customData);
        return split.components();
    }

    /** Whether Minecraft reads this top-level key on some item, so it is not the mod's own data. */
    static boolean ownedByMinecraft(String key) {
        return GENERIC_KEYS.contains(key) || VANILLA_ITEM_KEYS.contains(key);
    }

    /** The components a stack's Minecraft keys become, and the mod's own keys left over. */
    record Split(JsonObject components, Compound customData) {
    }

    /** Splits a stack's NBT, or returns null when a Minecraft key cannot be carried over. */
    static Split split(String itemId, Compound tag, String sourceMcVersion, String targetMcVersion) {
        // A key only some of Minecraft's items read, such as Potion, is Minecraft's only on a
        // Minecraft item. On an unknown item it could be either, so the NBT cannot carry over.
        boolean vanillaItem = itemId != null
                && (itemId.startsWith("minecraft:") || itemId.indexOf(':') < 0);
        JsonObject components = new JsonObject();
        Map<String, Tag> custom = new LinkedHashMap<>();
        int hideFlags = 0;
        for (Map.Entry<String, Tag> entry : tag.entries().entrySet()) {
            String key = entry.getKey();
            Tag value = entry.getValue();
            if (itemId == null && VANILLA_ITEM_KEYS.contains(key)) return null;
            // The mod's own keys stay as they are, for custom data.
            boolean owned = GENERIC_KEYS.contains(key) || vanillaItem && VANILLA_ITEM_KEYS.contains(key);
            if (!owned) {
                custom.put(key, value);
                continue;
            }
            // HideFlags is kept until every key is read, then written once below.
            if (key.equals("HideFlags")) {
                Long flags = value instanceof Num num ? num.asLong() : null;
                if (flags == null) return null;
                hideFlags = (int) (long) flags;
                continue;
            }
            if (!convert(key, value, components, sourceMcVersion, targetMcVersion)) return null;
        }
        // From 1.21.5 the hidden tooltip lines are listed in tooltip_display.
        if (hideFlags != 0 && atLeast(targetMcVersion, FIRST_INLINE_COMPONENT_HOST)) {
            components.add("minecraft:tooltip_display", tooltipDisplay(hideFlags));
        }
        return new Split(components, new Compound(custom));
    }

    /** {@code minecraft:custom_data} for the mod's own keys, or null when a type would change. */
    static JsonElement customDataValue(Compound customData, String targetMcVersion) {
        // From 1.21 it is SNBT text, which keeps every type.
        if (atLeast(targetMcVersion, FIRST_SNBT_CUSTOM_DATA_HOST)) {
            return new JsonPrimitive(LegacyItemNbt.write(customData));
        }
        // 1.20.5 and 1.20.6 read it only as a JSON object, which narrows numbers. So it is
        // written there only when nothing would change type.
        return LegacyItemNbt.jsonKeepsTypes(customData) ? LegacyItemNbt.toJson(customData) : null;
    }

    /** Adds the component for one Minecraft key, or returns false when it cannot carry over. */
    private static boolean convert(String key, Tag value, JsonObject components,
                                   String source, String target) {
        switch (key) {
            case "Damage", "RepairCost" -> {
                Long amount = value instanceof Num num ? num.asLong() : null;
                if (amount == null) return false;
                // Zero is the default, and a damage component on an item that cannot break fails.
                if (amount != 0) {
                    components.addProperty(key.equals("Damage") ? "minecraft:damage"
                            : "minecraft:repair_cost", amount);
                }
                return true;
            }
            case "Unbreakable" -> {
                Long flag = value instanceof Num num ? num.asLong() : null;
                if (flag == null) return false;
                if (flag != 0) components.add("minecraft:unbreakable", new JsonObject());
                return true;
            }
            case "CustomModelData" -> {
                // From 1.21.4 the number goes in a list of floats. Before that it stands alone.
                Long model = value instanceof Num num ? num.asLong() : null;
                if (model == null) return false;
                if (atLeast(target, FIRST_FLOAT_MODEL_DATA_HOST)) {
                    JsonObject floats = new JsonObject();
                    JsonArray list = new JsonArray();
                    list.add(model);
                    floats.add("floats", list);
                    components.add("minecraft:custom_model_data", floats);
                } else {
                    components.addProperty("minecraft:custom_model_data", model);
                }
                return true;
            }
            case "display" -> {
                return value instanceof Compound display && convertDisplay(display, components, source, target);
            }
            case "Enchantments", "StoredEnchantments" -> {
                // From 1.21.5 enchantments are a plain map. Before that the map sits under levels.
                JsonObject levels = enchantmentLevels(value);
                if (levels == null) return false;
                JsonElement component = levels;
                if (!atLeast(target, FIRST_INLINE_COMPONENT_HOST)) {
                    JsonObject wrapped = new JsonObject();
                    wrapped.add("levels", levels);
                    component = wrapped;
                }
                components.add(key.equals("Enchantments") ? "minecraft:enchantments"
                        : "minecraft:stored_enchantments", component);
                return true;
            }
            case "Potion" -> {
                if (!(value instanceof Str potion) || !isIdentifier(potion.value())) return false;
                JsonObject contents = new JsonObject();
                contents.addProperty("potion", namespaced(potion.value()));
                components.add("minecraft:potion_contents", contents);
                return true;
            }
            case "EntityTag" -> {
                // Later hosts read entity data as a compound only, never as SNBT text, so it is
                // written only when the JSON form keeps every value's type.
                if (!(value instanceof Compound entity) || !(entity.get("id") instanceof Str id)
                        || !isIdentifier(id.value()) || !LegacyItemNbt.jsonKeepsTypes(entity)) {
                    return false;
                }
                components.add("minecraft:entity_data", LegacyItemNbt.toJson(entity));
                return true;
            }
            case "AttributeModifiers" -> {
                JsonArray modifiers = attributeModifiers(value, target);
                if (modifiers == null) return false;
                // A bare list is read from 1.20.5 on, which shows the modifiers in the tooltip.
                components.add("minecraft:attribute_modifiers", modifiers);
                return true;
            }
            case "BlockEntityTag" -> {
                // Only a banner's or shield's patterns and base color carry over.
                return value instanceof Compound blockEntity
                        && convertBannerData(blockEntity, components, source);
            }
            default -> {
                // Any other Minecraft key, such as CanDestroy, cannot carry over exactly.
                return false;
            }
        }
    }

    /** Converts the display compound: the custom name, the lore, and the dye color. */
    private static boolean convertDisplay(Compound display, JsonObject components, String source,
                                          String target) {
        for (Map.Entry<String, Tag> field : display.entries().entrySet()) {
            switch (field.getKey()) {
                case "Name" -> {
                    if (!(field.getValue() instanceof Str name)) return false;
                    // 1.13 made custom names JSON text; before that they were plain.
                    components.add("minecraft:custom_name",
                            text(name.value(), sourceBefore(source, "1.13"), target));
                }
                case "Lore" -> {
                    if (!(field.getValue() instanceof ListTag lines)) return false;
                    JsonArray lore = new JsonArray();
                    for (Tag line : lines.items()) {
                        if (!(line instanceof Str text)) return false;
                        // Lore lines stayed plain text until 1.14.
                        lore.add(text(text.value(), sourceBefore(source, "1.14"), target));
                    }
                    components.add("minecraft:lore", lore);
                }
                case "color" -> {
                    // From 1.21.5 a dye color is a plain int. Before that it sits under rgb.
                    Long rgb = field.getValue() instanceof Num num ? num.asLong() : null;
                    if (rgb == null) return false;
                    if (atLeast(target, FIRST_INLINE_COMPONENT_HOST)) {
                        components.addProperty("minecraft:dyed_color", rgb);
                    } else {
                        JsonObject color = new JsonObject();
                        color.addProperty("rgb", rgb);
                        components.add("minecraft:dyed_color", color);
                    }
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    /** Text for a name or lore line, in the shape the target reads. */
    private static JsonElement text(String raw, boolean plain, String target) {
        // Old JSON text is kept as it was written, and plain text becomes literal text.
        JsonElement json = plain ? null : jsonText(raw);
        // From 1.21.5 the component is the text itself, where a plain string is literal.
        if (atLeast(target, FIRST_INLINE_COMPONENT_HOST)) {
            return json != null ? json : new JsonPrimitive(raw);
        }
        // Up to 1.21.4 the component is a string holding JSON text.
        return new JsonPrimitive(json != null ? raw : new JsonPrimitive(raw).toString());
    }

    /** The text component a string holds when it is JSON text, or null when it is plain text. */
    private static JsonElement jsonText(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "{[\"".indexOf(trimmed.charAt(0)) < 0) return null;
        try {
            JsonElement parsed = JsonParser.parseString(trimmed);
            return parsed.isJsonObject() || parsed.isJsonArray()
                    || parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isString() ? parsed : null;
        } catch (RuntimeException plainText) {
            return null;
        }
    }

    /** {@code [{id, lvl}]} as an id-to-level map, or null for 1.12 numeric ids or bad levels. */
    private static JsonObject enchantmentLevels(Tag value) {
        if (!(value instanceof ListTag list) || list.items().isEmpty()) return null;
        JsonObject levels = new JsonObject();
        for (Tag item : list.items()) {
            if (!(item instanceof Compound enchantment)
                    || !(enchantment.get("id") instanceof Str id) || !isIdentifier(id.value())
                    || !(enchantment.get("lvl") instanceof Num lvl)) {
                return null;
            }
            Long level = lvl.asLong();
            if (level == null || level < 1 || level > 255) return null;
            levels.addProperty(namespaced(id.value()), level);
        }
        return levels;
    }

    /** Converts a banner's or shield's patterns and base color. */
    private static boolean convertBannerData(Compound blockEntity, JsonObject components, String source) {
        // A 1.12 color counted from black, which 1.13's world upgrade turned around. Without a
        // source version the pattern colors could be in either order, so they are refused.
        boolean legacyColors = sourceBefore(source, "1.13");
        if (source == null && blockEntity.entries().containsKey("Patterns")) return false;
        for (Map.Entry<String, Tag> field : blockEntity.entries().entrySet()) {
            switch (field.getKey()) {
                case "Patterns" -> {
                    // Pattern codes become ids and color numbers become names. A layer with any
                    // other field cannot carry over.
                    if (!(field.getValue() instanceof ListTag list)) return false;
                    JsonArray patterns = new JsonArray();
                    for (Tag item : list.items()) {
                        if (!(item instanceof Compound layer)) return false;
                        String code = layer.string("Pattern");
                        String id = code == null ? null : BANNER_PATTERNS.get(code);
                        String color = dyeColor(layer.get("Color"), legacyColors);
                        if (id == null || color == null || layer.entries().size() != 2) return false;
                        JsonObject entry = new JsonObject();
                        entry.addProperty("color", color);
                        entry.addProperty("pattern", "minecraft:" + id);
                        patterns.add(entry);
                    }
                    components.add("minecraft:banner_patterns", patterns);
                }
                case "Base" -> {
                    String color = dyeColor(field.getValue(), legacyColors);
                    if (color == null) return false;
                    components.addProperty("minecraft:base_color", color);
                }
                case "id" -> {
                    // The block entity type, which the item already implies.
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    /** A dye color name by number, counting from black when the data is from 1.12. */
    private static String dyeColor(Tag value, boolean legacy) {
        Long index = value instanceof Num num ? num.asLong() : null;
        if (index == null || index < 0 || index > 15) return null;
        return DYE_COLORS[legacy ? 15 - (int) (long) index : (int) (long) index];
    }

    /** {@code AttributeModifiers} as the component's list, or null when one cannot carry over. */
    private static JsonArray attributeModifiers(Tag value, String target) {
        // A modifier on an attribute outside Minecraft's own, or with a field this rewrite does
        // not know, fails the whole list, since the item would lose a modifier the mod gave it.
        if (!(value instanceof ListTag list) || list.items().isEmpty()) return null;
        JsonArray modifiers = new JsonArray();
        for (Tag item : list.items()) {
            JsonObject modifier = item instanceof Compound old ? attributeModifier(old, target) : null;
            if (modifier == null) return null;
            modifiers.add(modifier);
        }
        return modifiers;
    }

    /** Converts one modifier, or returns null for an unknown attribute, field, or value. */
    private static JsonObject attributeModifier(Compound old, String target) {
        // Every field must be known, and every value must be valid. A missing operation means 0.
        if (!MODIFIER_KEYS.containsAll(old.entries().keySet())) return null;
        String attribute = attributeId(old.string("AttributeName"), target);
        java.util.UUID uuid = modifierUuid(old);
        Long operation = old.get("Operation") == null ? Long.valueOf(0)
                : old.get("Operation") instanceof Num number ? number.asLong() : null;
        String slot = old.string("Slot");
        String name = old.string("Name");
        if (attribute == null || uuid == null || !(old.get("Amount") instanceof Num amount)
                || operation == null || operation < 0 || operation >= MODIFIER_OPERATIONS.length
                || old.get("Slot") != null && (slot == null || !MODIFIER_SLOTS.contains(slot))
                || old.get("Name") != null && name == null) {
            return null;
        }
        // From 1.21 a modifier has an id. Before that it has a UUID as four ints, and a name.
        JsonObject modifier = new JsonObject();
        modifier.addProperty("type", attribute);
        if (atLeast(target, FIRST_MODIFIER_ID_HOST)) {
            modifier.addProperty("id", BASE_MODIFIER_IDS.getOrDefault(uuid.toString(), "minecraft:" + uuid));
        } else {
            JsonArray ints = new JsonArray();
            for (int shift : new int[] {32, 0}) ints.add((int) (uuid.getMostSignificantBits() >>> shift));
            for (int shift : new int[] {32, 0}) ints.add((int) (uuid.getLeastSignificantBits() >>> shift));
            modifier.add("uuid", ints);
            modifier.addProperty("name", name == null ? "" : name);
        }
        modifier.add("amount", LegacyItemNbt.toJson(amount));
        modifier.addProperty("operation", MODIFIER_OPERATIONS[(int) (long) operation]);
        if (slot != null) modifier.addProperty("slot", slot);
        return modifier;
    }

    /** The attribute's id on the target, or null for one that is not Minecraft's. */
    private static String attributeId(String name, String target) {
        if (name == null) return null;
        // Old camel-case names take their 1.16 spelling. From 1.21.2 the part before the dot
        // is dropped.
        String path = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
        path = CAMEL_CASE_ATTRIBUTES.getOrDefault(path, path);
        if (!ITEM_ATTRIBUTES.contains(path)) return null;
        if (atLeast(target, FIRST_UNPREFIXED_ATTRIBUTE_HOST)) path = path.substring(path.indexOf('.') + 1);
        return "minecraft:" + path;
    }

    /** The modifier's UUID: four ints from 1.16 on, two longs before. */
    private static java.util.UUID modifierUuid(Compound old) {
        if (old.get("UUID") instanceof LegacyItemNbt.ArrayTag ints && ints.kind() == 'I'
                && ints.values().size() == 4) {
            long[] parts = new long[4];
            for (int i = 0; i < 4; i++) {
                Long part = ints.values().get(i).asLong();
                if (part == null) return null;
                parts[i] = part & 0xffffffffL;
            }
            return new java.util.UUID(parts[0] << 32 | parts[1], parts[2] << 32 | parts[3]);
        }
        if (old.get("UUIDMost") instanceof Num most && old.get("UUIDLeast") instanceof Num least
                && most.asLong() != null && least.asLong() != null && old.get("UUID") == null) {
            return new java.util.UUID(most.asLong(), least.asLong());
        }
        return null;
    }

    /** {@code tooltip_display} listing every component the HideFlags bits hid. */
    private static JsonObject tooltipDisplay(int flags) {
        Set<String> hidden = new TreeSet<>();
        for (Map.Entry<Integer, List<String>> bit : HIDDEN_BY_FLAG.entrySet()) {
            if ((flags & bit.getKey()) != 0) hidden.addAll(bit.getValue());
        }
        JsonArray list = new JsonArray();
        hidden.forEach(list::add);
        JsonObject display = new JsonObject();
        display.add("hidden_components", list);
        return display;
    }

    private static boolean isIdentifier(String id) {
        return id.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");
    }

    private static String namespaced(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** Whether the version is at least the floor. False when the version is unknown. */
    private static boolean atLeast(String version, String floor) {
        return version != null && RetromodVersion.compareMcVersions(version, floor) >= 0;
    }

    /** Whether the data was written before a version. False when the source is unknown. */
    private static boolean sourceBefore(String source, String version) {
        return source != null && source.matches("\\d+\\.\\d+.*")
                && RetromodVersion.compareMcVersions(source, version) < 0;
    }

    private static Map<String, String> pairs(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) map.put(keysAndValues[i], keysAndValues[i + 1]);
        return Map.copyOf(map);
    }
}
