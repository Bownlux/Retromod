/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JSON text the 1.20.1 way: Component.Serializer's statics, which 1.20.5 made take registries, and its Gson adapters. */
final class TextJsonCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");

    private TextJsonCase() {}

    /** Checks once a server runs, so the text reads with the world's registries. */
    static void register() {
        MinecraftForge.EVENT_BUS.addListener(TextJsonCase::check);
    }

    private static void check(ServerStartedEvent event) {
        String result;
        try {
            result = run();
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] Component.Serializer statics and the Component and Style Gson adapters (server started): {}",
                result);
    }

    private static String run() {
        MutableComponent text = Component.Serializer.fromJson("{\"text\":\"hi\",\"color\":\"red\"}");
        if (text == null || !"hi".equals(text.getString())) return "fail: read " + text;
        String json = Component.Serializer.toJson(text);
        if (!json.contains("\"red\"") || !Component.Serializer.toJsonTree(text).isJsonObject()) return "fail: wrote " + json;
        // Config libraries register both serializers on a Gson of their own.
        Gson gson = new GsonBuilder().registerTypeHierarchyAdapter(Component.class, new Component.Serializer())
                .registerTypeHierarchyAdapter(Style.class, new Style.Serializer()).create();
        Component viaGson = gson.fromJson(json, Component.class);
        if (viaGson == null || !viaGson.getString().equals("hi")) return "fail: the adapter read " + viaGson;
        Style style = gson.fromJson("{\"color\":\"gold\",\"bold\":true}", Style.class);
        if (style == null || !style.isBold()) return "fail: the style adapter read " + style;
        String styleJson = gson.toJson(style, Style.class);
        return styleJson.contains("\"gold\"") ? "success" : "fail: the style adapter wrote " + styleJson;
    }
}
