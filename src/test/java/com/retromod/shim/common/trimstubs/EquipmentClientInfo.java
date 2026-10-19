/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stands in for 26.3's {@code EquipmentClientInfo}: an equipment asset's trim overrides, which
 * {@link #CODEC} reads from the asset's JSON in the host's shape. An override's {@code when}
 * names the trim material by its palette, which is all a stand-in material has; the host's own
 * predicate compares material keys.
 */
public record EquipmentClientInfo(List<TrimOverride> trimOverrides) {

    public static final Codec CODEC = (ops, input) -> DataResult.success(read((JsonElement) input));

    public record TrimOverride(TrimPredicate predicate, Optional<Identifier> textureId, Optional<Identifier> paletteId) {
    }

    public record TrimPredicate(Identifier materialPalette) {
        public boolean matches(ArmorTrim trim) {
            return trim.material().value().paletteId().equals(materialPalette);
        }
    }

    private static EquipmentClientInfo read(JsonElement json) {
        List<TrimOverride> overrides = new ArrayList<>();
        JsonObject root = json.getAsJsonObject();
        if (root.has("trim_overrides")) {
            for (JsonElement entry : root.getAsJsonArray("trim_overrides")) {
                JsonObject override = entry.getAsJsonObject();
                Identifier material = id(override.getAsJsonObject("when").get("material").getAsString());
                overrides.add(new TrimOverride(new TrimPredicate(material),
                        optionalId(override, "texture"), optionalId(override, "palette")));
            }
        }
        return new EquipmentClientInfo(overrides);
    }

    private static Optional<Identifier> optionalId(JsonObject object, String key) {
        return object.has(key) ? Optional.of(id(object.get(key).getAsString())) : Optional.empty();
    }

    private static Identifier id(String text) {
        int colon = text.indexOf(':');
        return Identifier.fromNamespaceAndPath(text.substring(0, colon), text.substring(colon + 1));
    }
}
