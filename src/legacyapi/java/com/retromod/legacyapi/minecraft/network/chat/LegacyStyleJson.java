/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.network.chat;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.lang.reflect.Type;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Stands in for {@code Style.Serializer}, a Gson type adapter until 1.20.3: a style as the JSON fields it had. */
public class LegacyStyleJson implements JsonDeserializer<Style>, JsonSerializer<Style> {

    // The style keys of a text object, under the names before and after 1.21.5. The 1.20.1 text adapter handed this
    // adapter the whole object, siblings and a mod's own keys included, so only these are read.
    private static final List<String> STYLE_KEYS = List.of("color", "shadow_color", "bold", "italic", "underlined",
            "strikethrough", "obfuscated", "insertion", "font", "clickEvent", "hoverEvent", "click_event", "hover_event");

    // A mod that declares no Minecraft version takes the stand-in too, so the codecs it may read are the host's.
    public static final MapCodec<Style> MAP_CODEC = Style.Serializer.MAP_CODEC;
    public static final Codec<Style> CODEC = Style.Serializer.CODEC;

    /** {@code new Style.Serializer()}, which mods registered beside the component adapter. */
    public LegacyStyleJson() {}

    /** The style keys of a JSON object, read as the style of an empty text, which carries the same keys; anything else is null, as before. */
    @Override
    public Style deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
        if (json == null || !json.isJsonObject()) return null;
        JsonObject text = new JsonObject();
        text.addProperty("text", "");
        for (String key : STYLE_KEYS) {
            JsonElement value = json.getAsJsonObject().get(key);
            if (value != null) text.add(key, value.deepCopy());
        }
        MutableComponent component = LegacyComponentJson.fromJson(text);
        return component == null ? null : component.getStyle();
    }

    /** The style's fields as a JSON object, or null for an empty style, as before. */
    @Override
    public JsonElement serialize(Style style, Type type, JsonSerializationContext context) {
        if (style == null || style.isEmpty()) return null;
        JsonElement text = LegacyComponentJson.toJsonTree(Component.empty().withStyle(style));
        if (!text.isJsonObject()) return null;
        JsonObject fields = text.getAsJsonObject().deepCopy();
        fields.remove("text");
        return fields;
    }
}
