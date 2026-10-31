/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The 1.4.0-snapshot.3 intermediary pass, each value checked against the 26.x host jars. */
class IntermediaryHostRefreshTest {

    private final IntermediaryToMojangMapper mapper = IntermediaryToMojangMapper.getInstance();

    @Test
    @DisplayName("A lambda id is left unmapped instead of borrowing the name of a member it shares an obfuscated name with")
    void lambdaIdsBorrowNoName() {
        // An unmapped id comes back unchanged.
        assertEquals("method_71167", mapper.mapMethod("method_71167"),
                "RenderTarget's lambda$blitAndBlendToTexture$2, which was mapped to the field colorTexture");
        assertEquals("method_66047", mapper.mapMethod("method_66047"),
                "AreaEffectCloudDurationScaleFix's lambda$fix$0, which was mapped to fix");
        assertEquals("method_73066", mapper.mapMethod("method_73066"),
                "a loot lambda that was mapped to registries");
    }

    @Test
    @DisplayName("Classes 1.21.5 to 1.21.11 added map to the package the 26.x host has them in")
    void newerClassesMapToTheirHostPackage() {
        assertEquals("com/mojang/blaze3d/opengl/GlShaderModule", mapper.mapClass("net/minecraft/class_10141"),
                "CompiledShader was renamed by 1.21.11");
        assertEquals("net/minecraft/client/renderer/block/dispatch/BlockStateModel",
                mapper.mapClass("net/minecraft/class_1087"), "BakedModel is gone from every 26.x host");
    }

    @Test
    @DisplayName("Members renamed by 1.21.11 map to the name the 26.x host uses")
    void renamedMembersMapToHostNames() {
        assertEquals("submit", mapper.mapMethod("method_3555"), "BlockEntityRenderDispatcher.render became submit");
        assertEquals("noButtonComponent", mapper.mapField("field_2399"),
                "the host's noButton field has another type");
    }

    @Test
    @DisplayName("Ids the table lacked get the name they have on the host")
    void missingIdsAreAdded() {
        assertEquals("com/mojang/blaze3d/opengl/GlStateManager$BlendState",
                mapper.mapClass("com/mojang/blaze3d/platform/GlStateManager$class_1017"),
                "a 1.21.4 mod names the inner class under the outer class's old package");
        assertEquals("enforceGameTypeForPlayers", mapper.mapMethod("method_74059"),
                "a MinecraftServer method, a class intermediary leaves unrenamed");
    }

    @Test
    @DisplayName("Classes added after 1.21.1 that 26.1 moved have a move for every 26.1 host")
    void newerClassMovesAreCovered() {
        assertEquals("net/minecraft/client/renderer/state/gui/BlitRenderState",
                mapper.getClassMoves().get("net/minecraft/client/gui/render/state/BlitRenderState"));
        assertEquals("net/minecraft/client/resources/model/sprite/AtlasManager$AtlasConfig",
                mapper.getClassMoves().get("net/minecraft/client/resources/model/AtlasManager$AtlasConfig"),
                "26.1 already had the new path, so a 26.2-only shim missed 26.1.x hosts");
    }

    @Test
    @DisplayName("Each id has one row, so the loader never picks between two values")
    void everyIdHasOneRow() throws Exception {
        Set<String> seen = new HashSet<>();
        List<String> repeated = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                IntermediaryHostRefreshTest.class.getResourceAsStream("/intermediary-to-mojang.tsv"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (!seen.add(parts[0] + " " + parts[1])) repeated.add(line);
            }
        }
        assertEquals(List.of(), repeated);
    }
}
