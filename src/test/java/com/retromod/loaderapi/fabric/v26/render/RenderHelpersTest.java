/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.render;

import com.retromod.loaderapi.fabric.v26.render.shared.LegacyBlockColor;
import com.retromod.loaderapi.fabric.v26.render.shared.LegacyBlockRenderLayerMap;
import com.retromod.loaderapi.fabric.v26.render.shared.LegacyColorProviderRegistry;
import com.retromod.loaderapi.fabric.v26.render.shared.LegacyItemColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.io.StringReader;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The R1 helpers on their own: tint slot counts from model JSON, alpha rules, and the side with no host. */
class RenderHelpersTest {

    private static Function<String, Reader> models(Map<String, String> files) {
        return id -> {
            String json = files.get(id);
            if (json == null) throw new IllegalStateException("no model " + id);
            return new StringReader(json);
        };
    }

    @Test
    @DisplayName("A generated item model needs one tint slot per layer")
    void generatedItemCountsLayers() {
        Map<String, String> files = Map.of(
                "mod:item/gem", "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"a\",\"layer2\":\"b\"}}",
                "minecraft:item/generated", "{\"parent\":\"builtin/generated\",\"textures\":{}}");
        assertEquals(3, ModelTintIndex.tintSlots("mod:item/gem", models(files)));
    }

    @Test
    @DisplayName("A block item model follows its parents to the faces that define its geometry")
    void blockItemReadsFaceTints() {
        Map<String, String> files = Map.of(
                "mod:item/leaves", "{\"parent\":\"mod:block/leaves\"}",
                "mod:block/leaves", "{\"parent\":\"block/leaves\"}",
                "minecraft:block/leaves", "{\"elements\":[{\"faces\":{\"up\":{\"tintindex\":0},"
                        + "\"down\":{\"tintindex\":108},\"north\":{}}}]}");
        assertEquals(109, ModelTintIndex.tintSlots("mod:item/leaves", models(files)));
    }

    @Test
    @DisplayName("The nearest model with elements decides, so a child's untinted cube needs no tint")
    void nearestElementsWin() {
        Map<String, String> files = Map.of(
                "mod:item/plain", "{\"parent\":\"minecraft:block/leaves\",\"elements\":[{\"faces\":{\"up\":{}}}]}",
                "minecraft:block/leaves", "{\"elements\":[{\"faces\":{\"up\":{\"tintindex\":0}}}]}");
        assertEquals(0, ModelTintIndex.tintSlots("mod:item/plain", models(files)));
    }

    @Test
    @DisplayName("Old model files with comments and a trailing comma in a list still count")
    void lenientModelJson() {
        Map<String, String> files = Map.of("mod:item/old",
                "{ /* made by hand */ \"elements\": [{\"faces\": {\"up\": {\"tintindex\": 1}}},]}");
        assertEquals(2, ModelTintIndex.tintSlots("mod:item/old", models(files)));
    }

    @Test
    @DisplayName("A model that cannot be read, or a parent loop, is unknown")
    void unreadableIsUnknown() {
        assertEquals(ModelTintIndex.UNKNOWN, ModelTintIndex.tintSlots("mod:item/missing", models(Map.of())));
        Map<String, String> loop = Map.of("mod:a", "{\"parent\":\"mod:b\"}", "mod:b", "{\"parent\":\"mod:a\"}");
        assertEquals(ModelTintIndex.UNKNOWN, ModelTintIndex.tintSlots("mod:a", models(loop)));
    }

    @Test
    @DisplayName("An item color with no alpha is read as RGB, and one with alpha keeps it")
    void itemColorAlpha() {
        assertEquals(0xFF123456, HostTints.opaqueWhenNoAlpha(0x123456));
        assertEquals(0x80123456, HostTints.opaqueWhenNoAlpha(0x80123456));
        assertEquals(-1, HostTints.opaqueWhenNoAlpha(-1));
    }

    @Test
    @DisplayName("With no client host, as on a dedicated server, old colors are kept for get()")
    void noHostKeepsProviders() {
        Object block = new Object();
        LegacyBlockColor blockColor = (state, level, pos, tint) -> 7;
        LegacyColorProviderRegistry.BLOCK.register(blockColor, block);
        assertSame(blockColor, LegacyColorProviderRegistry.BLOCK.get(block));
        assertNull(LegacyColorProviderRegistry.BLOCK.get(new Object()), "an unknown block has no provider");

        Object item = new Object();
        LegacyItemColor itemColor = (stack, tint) -> 9;
        LegacyColorProviderRegistry.ITEM.register(itemColor, item);
        assertSame(itemColor, LegacyColorProviderRegistry.ITEM.get(item));
        assertSame(itemColor, LegacyColorProviders.itemProvider(item));
    }

    @Test
    @DisplayName("Old render layer calls are recorded, with or without a layer")
    void renderLayersAreRecorded() {
        Object block = new Object();
        Object fluid = new Object();
        Object item = new Object();
        Object layer = new Object();
        LegacyBlockRenderLayerMap.INSTANCE.putBlock(block, layer);
        LegacyBlockRenderLayerMap.INSTANCE.putFluids(null, new Object[]{fluid});
        LegacyRenderLayers.putItems(layer, new Object[]{item, null});
        assertSame(layer, LegacyRenderLayers.layerOf(block));
        assertTrue(LegacyRenderLayers.hasLayer(fluid));
        assertNull(LegacyRenderLayers.layerOf(fluid), "a put with no layer records none");
        assertSame(layer, LegacyRenderLayers.layerOf(item));
        assertFalse(LegacyRenderLayers.hasLayer(new Object()));
    }
}
