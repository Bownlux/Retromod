/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.core.RedirectKey;
import com.retromod.loaderapi.LoaderApiRegistration;
import com.retromod.loaderapi.fabric.v26.synth.BoundaryTypeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/** Registers batch R1: old block render layers, color providers and their callback types on 26.x hosts. */
public final class RenderRows {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");
    private static final String SHARED = "com/retromod/loaderapi/fabric/v26/render/shared/";
    private static final String BRIDGES = "com/retromod/loaderapi/fabric/v26/synth/";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String SECTION_LAYER = "net/minecraft/client/renderer/chunk/ChunkSectionLayer";

    /**
     * The vanilla color callbacks old color providers implement. 26.1 removed both, and libraries
     * such as Balm and Architectury take them in public methods, so every mod must name one type.
     */
    public static final List<BoundaryTypeSpec> SHARED_TYPES = List.of(
            new BoundaryTypeSpec("net/minecraft/client/color/block/BlockColor",
                    SHARED + "LegacyBlockColor", "getColor",
                    "(Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                            + "Lnet/minecraft/core/BlockPos;I)I",
                    "legacyBlockColor", "(" + OBJECT.repeat(3) + "I)I", BRIDGES + "BlockColorBridge"),
            new BoundaryTypeSpec("net/minecraft/client/color/item/ItemColor",
                    SHARED + "LegacyItemColor", "getColor", "(Lnet/minecraft/world/item/ItemStack;I)I",
                    "legacyItemColor", "(" + OBJECT + "I)I", BRIDGES + "ItemColorBridge"));

    /** Compiled helpers that redirected calls land on. */
    public static final List<String> HELPERS = List.of(
            "com/retromod/loaderapi/fabric/v26/render/LegacyRenderLayers",
            "com/retromod/loaderapi/fabric/v26/render/LegacyColorProviders");

    /** Shared interfaces the rename table sends the old registry types to; they stay interfaces. */
    public static final List<String> SHARED_TARGETS = List.of(
            SHARED + "LegacyBlockRenderLayerMap",
            SHARED + "LegacyColorProviderRegistry");

    /** The no-op stand-in older shims gave both {@code BlockRenderLayerMap} types. */
    static final List<String> RETIRED_STAND_INS = List.of(
            "com/retromod/generated/legacyfabric/BlockRenderLayerMap",
            "com/retromod/generated/legacyfabric/BlockRenderLayerMapImpl");

    /**
     * Section layers 26.x lacks that 1.21.6 to 1.21.11 mods pass to the static layer map, each with
     * the layer that took over its blocks. CUTOUT_MIPPED was gone by 1.21.11, so the intermediary
     * table has no 26.x name for it and its read keeps the intermediary name after the remap.
     */
    static final Map<String, String> REMOVED_SECTION_LAYERS = Map.of(
            "field_60924", "CUTOUT",
            "TRIPWIRE", "TRANSLUCENT");

    private RenderRows() {}

    /**
     * Registers the shared callback types, helper owners and removed section layers; the call rows
     * come from the rename table.
     */
    public static void register(LoaderApiRegistration registration) {
        // These are Minecraft types, so a spec stands back if the Minecraft side ever redirects one.
        for (BoundaryTypeSpec spec : SHARED_TYPES) {
            if (!spec.register(registration)) {
                LOGGER.debug("{} stays with Retromod's Minecraft side, which redirects it",
                        spec.oldName());
            }
        }
        for (String helper : HELPERS) {
            registration.claim(RedirectKey.type(helper));
            registration.transformer().registerClassOwner(helper);
        }
        for (String target : SHARED_TARGETS) registration.claim(RedirectKey.type(target));
        // Both old map types pointed at this no-op stand-in, and shims keyed calls on it. The rows
        // replace it, so the runtime takes its keys too and no translated class names it.
        for (String standIn : RETIRED_STAND_INS) registration.claim(RedirectKey.type(standIn));
        // The layer is only recorded, but reading a removed constant would throw before the call.
        String layerDesc = "L" + SECTION_LAYER + ";";
        REMOVED_SECTION_LAYERS.forEach((removed, successor) -> registration.redirectField(
                SECTION_LAYER, removed, layerDesc, SECTION_LAYER, successor, layerDesc));
    }
}
