/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.lang.reflect.Type;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.model.geometry.IGeometryLoader;

/** Forge 26.2's geometry loader for an old mod's loader: it reads the old geometry and hands Minecraft an adapter to bake it. */
final class LegacyGeometryLoaderAdapter implements IGeometryLoader {

    private final LegacyGeometryLoader<?> loader;
    private final Identifier id;

    LegacyGeometryLoaderAdapter(LegacyGeometryLoader<?> loader, Identifier id) {
        this.loader = loader;
        this.id = id;
    }

    @Override
    public UnbakedGeometry read(JsonObject json, JsonDeserializationContext context) throws JsonParseException {
        IUnbakedGeometry<?> geometry;
        try {
            geometry = loader.read(json, new OldModels(context));
        } catch (LinkageError failure) {
            // Minecraft reports a parse error for this one model; an error outside it would stop the whole reload.
            throw new JsonParseException("The old geometry loader " + id + " could not read this model: " + failure, failure);
        }
        if (geometry == null) throw new JsonParseException("The old geometry loader " + id + " read no geometry");
        return new LegacyUnbakedGeometryAdapter(geometry, id);
    }

    @Override
    public String toString() {
        return "LegacyGeometryLoaderAdapter[" + id + " " + loader + "]";
    }

    /** Minecraft's JSON context, which reads an old nested BlockModel as the model Minecraft 26.2 parses. */
    private record OldModels(JsonDeserializationContext host) implements JsonDeserializationContext {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(JsonElement json, Type type) throws JsonParseException {
            // Old code asks for BlockModel, whose name Minecraft 26.1 gave to another type.
            if (type == BlockModel.class || type == LegacyBlockModel.class) {
                return (T) LegacyBlockModel.of(host.deserialize(json, CuboidModel.class));
            }
            return host.deserialize(json, type);
        }
    }
}
