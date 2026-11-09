/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.network.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.retromod.legacyapi.minecraft.core.LegacyRegistryContext;
import com.retromod.shim.common.embedded.LegacyEnchantmentRegistry;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.datafix.DataFixers;

/** Stands in for {@code Component.Serializer}: text components as JSON through the component codec, with the old Gson adapter shape too. */
public class LegacyComponentJson implements JsonDeserializer<MutableComponent>, JsonSerializer<Component> {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    // The first schema with the text component type: the host's text fixes run from there, after 1.21.4's shape.
    private static final int FIRST_TEXT_SCHEMA = 4290;
    private static volatile Optional<Object> textType;
    private static volatile boolean noRegistryOps;
    // The style flags 1.21.5 turned from strings into booleans, the other change the host's text fixes make.
    private static final List<String> FLAGS = List.of("bold", "italic", "underlined", "strikethrough", "obfuscated");
    private static final List<String> NESTED_TEXT = List.of("extra", "with", "separator");

    /** {@code new Component.Serializer()}, which mods before 1.20.3 registered as a Gson type adapter, and some extended. */
    public LegacyComponentJson() {}

    /** The old adapter's read: any text component JSON, with the loaded world's registries. */
    @Override
    public MutableComponent deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
        return read(json, ops(registries()));
    }

    /** The old adapter's write. */
    @Override
    public JsonElement serialize(Component component, Type type, JsonSerializationContext context) {
        return oldShape(write(component, ops(registries())));
    }

    /** {@code fromJson(json)}: null for a JSON null, as before; malformed text fails with a parse error. */
    public static MutableComponent fromJson(String json) {
        return json == null ? null : fromJson(JsonParser.parseString(json));
    }

    /** {@code fromJson(json, registries)} from 1.20.5. */
    public static MutableComponent fromJson(String json, HolderLookup.Provider registries) {
        return json == null ? null : read(JsonParser.parseString(json), ops(registries));
    }

    /** {@code fromJson(element)}. */
    public static MutableComponent fromJson(JsonElement element) {
        return read(element, ops(registries()));
    }

    /** {@code fromJson(element, registries)} from 1.20.5. */
    public static MutableComponent fromJson(JsonElement element, HolderLookup.Provider registries) {
        return read(element, ops(registries));
    }

    /** {@code fromJsonLenient(json)}: Gson's parser already accepts comments and unquoted names. */
    public static MutableComponent fromJsonLenient(String json) {
        return fromJson(json);
    }

    /** {@code fromJsonLenient(json, registries)} from 1.20.5. */
    public static MutableComponent fromJsonLenient(String json, HolderLookup.Provider registries) {
        return fromJson(json, registries);
    }

    /** {@code toJson(component)}. */
    public static String toJson(Component component) {
        return GSON.toJson(toJsonTree(component));
    }

    /** {@code toJson(component, registries)} from 1.20.5. */
    public static String toJson(Component component, HolderLookup.Provider registries) {
        return GSON.toJson(write(component, ops(registries)));
    }

    /** {@code toJsonTree(component)}, which 1.20.5 made private. */
    public static JsonElement toJsonTree(Component component) {
        return oldShape(write(component, ops(registries())));
    }

    /** {@code toStableJson(component)} before 1.20.3: the same JSON with every object's keys sorted. */
    public static String toStableJson(Component component) {
        return GSON.toJson(sorted(toJsonTree(component)));
    }

    private static MutableComponent read(JsonElement element, DynamicOps<JsonElement> ops) {
        if (element == null || element.isJsonNull()) return null;
        Component component = ComponentSerialization.CODEC.parse(ops, upgrade(numbersAsText(element)))
                .getOrThrow(message -> new JsonParseException("Not a text component: " + message));
        return component.copy();
    }

    private static JsonElement write(Component component, DynamicOps<JsonElement> ops) {
        return ComponentSerialization.CODEC.encodeStart(ops, component)
                .getOrThrow(message -> new JsonParseException("Cannot write the text component: " + message));
    }

    // 1.21.5 renamed the click and hover event keys, and the host's own data fixer brings old text over; older hosts
    // read the old keys as they are. Text the fixer cannot read goes to the codec as it was, which reports why.
    private static JsonElement upgrade(JsonElement element) {
        // Running the fixer costs about a hundred times a codec read, so text it would not change skips it.
        if (!needsUpgrade(element)) return element;
        Object type = textComponentType();
        // A host without the fixer reads the old event keys, but not a style flag written as a string.
        if (type == null) return flagsAsBooleans(element);
        try {
            int current = net.minecraft.SharedConstants.class.getField("WORLD_VERSION").getInt(null);
            return DataFixers.getDataFixer().update((DSL.TypeReference) type, new Dynamic<>(JsonOps.INSTANCE, element),
                    FIRST_TEXT_SCHEMA, current).getValue();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unfixable) {
            return element;
        }
    }

    /** Whether the text holds a key the 1.21.5 text fixes change: an old click or hover event, or a style flag written as a string. */
    private static boolean needsUpgrade(JsonElement element) {
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (needsUpgrade(item)) return true;
            }
            return false;
        }
        if (!element.isJsonObject()) return false;
        JsonObject object = element.getAsJsonObject();
        if (object.has("clickEvent") || object.has("hoverEvent")) return true;
        for (String flag : FLAGS) {
            JsonElement value = object.get(flag);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return true;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (needsUpgrade(entry.getValue())) return true;
        }
        return false;
    }

    // The 1.20.1 adapter read a flag written as a string as Boolean.parseBoolean does, and so does this copy.
    private static JsonElement flagsAsBooleans(JsonElement element) {
        JsonElement text = element.deepCopy();
        booleanFlags(text);
        return text;
    }

    private static void booleanFlags(JsonElement text) {
        if (text.isJsonArray()) {
            for (JsonElement item : text.getAsJsonArray()) booleanFlags(item);
            return;
        }
        if (!text.isJsonObject()) return;
        JsonObject object = text.getAsJsonObject();
        for (String flag : FLAGS) {
            JsonElement value = object.get(flag);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                object.addProperty(flag, Boolean.parseBoolean(value.getAsString()));
            }
        }
        // Text nests in siblings, translation arguments and a separator, and in the text a hover event shows.
        for (String key : NESTED_TEXT) {
            JsonElement nested = object.get(key);
            if (nested != null) booleanFlags(nested);
        }
        JsonElement hover = object.get("hoverEvent");
        if (hover == null || !hover.isJsonObject()) return;
        JsonObject event = hover.getAsJsonObject();
        JsonElement shown = event.has("contents") ? event.get("contents") : event.get("value");
        JsonElement action = event.get("action");
        if (shown == null || action == null || !action.isJsonPrimitive()) return;
        if (action.getAsString().equals("show_text")) booleanFlags(shown);
        if (action.getAsString().equals("show_entity") && shown.isJsonObject() && shown.getAsJsonObject().has("name")) {
            booleanFlags(shown.getAsJsonObject().get("name"));
        }
    }

    // References.TEXT_COMPONENT came with the 1.21.5 text fixes, so a host before it has none.
    private static Object textComponentType() {
        Optional<Object> known = textType;
        if (known == null) {
            Object type;
            try {
                type = net.minecraft.util.datafix.fixes.References.class.getField("TEXT_COMPONENT").get(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
                type = null;
            }
            known = Optional.ofNullable(type);
            textType = known;
        }
        return known.orElse(null);
    }

    // Before 1.20.5 a number or boolean read as its text, as a whole component, a list entry, or the text field.
    private static JsonElement numbersAsText(JsonElement element) {
        if (element.isJsonPrimitive()) {
            return element.getAsJsonPrimitive().isString() ? element : new JsonPrimitive(element.getAsString());
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) array.add(numbersAsText(item));
            return array;
        }
        if (!element.isJsonObject()) return element;
        JsonObject object = element.getAsJsonObject();
        JsonElement text = object.get("text");
        JsonElement extra = object.get("extra");
        boolean textNumber = text != null && text.isJsonPrimitive() && !text.getAsJsonPrimitive().isString();
        if (!textNumber && (extra == null || !extra.isJsonArray())) return element;
        JsonObject copy = object.deepCopy();
        if (textNumber) copy.addProperty("text", text.getAsString());
        if (extra != null && extra.isJsonArray()) copy.add("extra", numbersAsText(extra));
        return copy;
    }

    // Before 1.20.3 plain text was written as {"text": ...}; the codec writes it as a bare string now.
    private static JsonElement oldShape(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return element;
        JsonObject text = new JsonObject();
        text.add("text", element);
        return text;
    }

    // The save or load on this thread passes its registries; otherwise the world the calling code sees: the client's
    // on the client thread, a running server's, or a data pack load's. Before any world there are none.
    private static HolderLookup.Provider registries() {
        HolderLookup.Provider current = LegacyRegistryContext.current();
        if (current != null) return current;
        return LegacyEnchantmentRegistry.registriesOr(null) instanceof HolderLookup.Provider world ? world : null;
    }

    // Registry-backed text, such as an item shown on hover, needs the registries; plain text reads without them.
    private static DynamicOps<JsonElement> ops(HolderLookup.Provider registries) {
        if (registries == null || noRegistryOps) return JsonOps.INSTANCE;
        try {
            return registries.createSerializationContext(JsonOps.INSTANCE);
        } catch (NoSuchMethodError before1205) {
            // 1.20.3 and 1.20.4 read text without registries, as their own serializer did.
            noRegistryOps = true;
            return JsonOps.INSTANCE;
        }
    }

    private static JsonElement sorted(JsonElement element) {
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) array.add(sorted(item));
            return array;
        }
        if (!element.isJsonObject()) return element;
        List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(element.getAsJsonObject().entrySet());
        entries.sort(Map.Entry.comparingByKey());
        JsonObject object = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : entries) object.add(entry.getKey(), sorted(entry.getValue()));
        return object;
    }
}
