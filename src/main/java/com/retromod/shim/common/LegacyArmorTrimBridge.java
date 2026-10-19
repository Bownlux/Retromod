/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyArmorTrims;
import org.objectweb.asm.tree.ClassNode;

/** Keeps mods that draw armor trims themselves working on 26.3, which removed the armor trim atlas. */
public final class LegacyArmorTrimBridge {

    static final String HELPER = "com/retromod/shim/common/embedded/LegacyArmorTrims";
    static final String SHEETS = "net/minecraft/client/renderer/Sheets";
    static final String ARMOR_TRIM = "net/minecraft/world/item/equipment/trim/ArmorTrim";
    static final String TRIM_MATERIAL = "net/minecraft/world/item/equipment/trim/TrimMaterial";
    static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    static final String RENDER_TYPE = "net/minecraft/client/renderer/rendertype/RenderType";
    static final String RENDER_TYPES = "net/minecraft/client/renderer/rendertype/RenderTypes";

    static final String SHEET = "ARMOR_TRIMS_SHEET";
    static final String SHEET_DESC = "L" + IDENTIFIER + ";";
    static final String SHEET_TYPE = "armorTrimsSheet";
    static final String SHEET_TYPE_DESC = "(Z)L" + RENDER_TYPE + ";";
    static final String LAYER_ASSET_ID = "layerAssetId";
    static final String LAYER_ASSET_ID_DESC =
            "(Ljava/lang/String;Lnet/minecraft/resources/ResourceKey;)L" + IDENTIFIER + ";";
    static final String PALETTE_ID = "paletteId";
    static final String PALETTE_ID_DESC = "()L" + IDENTIFIER + ";";
    static final String DECAL_TYPE = "createArmorDecalCutoutNoCull";
    static final String DECAL_TYPE_DESC = "(L" + IDENTIFIER + ";)L" + RENDER_TYPE + ";";

    /** The old members the host lost. */
    record Lost(boolean sheet, boolean sheetType, boolean layerAssetId, boolean decalType) {
        static final Lost NONE = new Lost(false, false, false, false);
        static final Lost ALL = new Lost(true, true, true, true);

        boolean any() {
            return sheet || sheetType || layerAssetId || decalType;
        }
    }

    private LegacyArmorTrimBridge() {}

    /** Redirects each old trim member the host lost to {@link LegacyArmorTrims}. */
    public static void register(RetromodTransformer transformer) {
        // Up to 26.2 a custom armor layer drew a trim like vanilla's equipment layer: the atlas at
        // Sheets.ARMOR_TRIMS_SHEET, the sprite from ArmorTrim.layerAssetId, the render type from
        // Sheets.armorTrimsSheet. 26.3 recolors each trim texture with its material's palette when
        // first drawn, so all three are gone, and the layer failed while the game built its player
        // renderers.
        Lost lost = lostMembers();
        if (lost.any()) registerRedirects(transformer, lost);
    }

    /** Unconditional registration, for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, Lost lost) {
        // The helper rebuilds an atlas of the old shape from 26.3's trim textures and palettes.
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyArmorTrims.class);
        // The sheet id becomes the rebuilt atlas's texture id. The texture manager hands that atlas
        // back, so the mod's own TextureAtlas cast and getSprite calls run unchanged.
        if (lost.sheet()) {
            transformer.registerExactFieldRedirect(SHEETS, SHEET, SHEET_DESC,
                    HELPER, "sheet", "()Ljava/lang/Object;");
        }
        // armorTrimsSheet(decal) becomes 26.3's trim render type over that atlas, which uses the
        // same pipelines and settings as the old sheet types.
        if (lost.sheetType()) {
            transformer.registerMethodRedirect(SHEETS, SHEET_TYPE, SHEET_TYPE_DESC,
                    HELPER, "armorTrimsSheet", "(Z)Ljava/lang/Object;");
        }
        // layerAssetId(prefix, asset) names the sprite for the trim's texture and palette, after the
        // equipment asset's trim overrides.
        if (lost.layerAssetId()) {
            transformer.registerMethodRedirect(ARMOR_TRIM, LAYER_ASSET_ID, LAYER_ASSET_ID_DESC,
                    HELPER, "layerAssetId", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
                    true);
        }
        // The decal half of the old sheet types, which a mod can also build over its own atlas,
        // becomes 26.3's decal trim render type over that texture.
        if (lost.decalType()) {
            transformer.registerMethodRedirect(RENDER_TYPES, DECAL_TYPE, DECAL_TYPE_DESC,
                    HELPER, "armorDecalCutoutNoCull", "(Ljava/lang/Object;)Ljava/lang/Object;");
        }
    }

    /** Which old members the host lost, read from its own classes. */
    static Lost lostMembers() {
        ClassNode trim = ClassResourceInspector.read(ARMOR_TRIM);
        ClassNode material = ClassResourceInspector.read(TRIM_MATERIAL);
        // Offline, where the host classes cannot be read, the host version decides.
        if (trim == null || material == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0
                    ? Lost.ALL : Lost.NONE;
        }
        return lostMembers(ClassResourceInspector.read(SHEETS), ClassResourceInspector.read(RENDER_TYPES), trim, material);
    }

    /** What the host lost, when its trim materials name a palette. */
    static Lost lostMembers(ClassNode sheets, ClassNode renderTypes, ClassNode trim, ClassNode material) {
        // The rebuilt atlas is made from palettes, so a host that does not recolor trims from them
        // gets no bridge.
        if (!hasMethod(material, PALETTE_ID, PALETTE_ID_DESC)) return Lost.NONE;
        // Sheets and RenderTypes are client classes, so a dedicated server cannot read them. Client
        // code that names them never runs there.
        return new Lost(
                sheets == null || !hasField(sheets, SHEET, SHEET_DESC),
                sheets == null || !hasMethod(sheets, SHEET_TYPE, SHEET_TYPE_DESC),
                !hasMethod(trim, LAYER_ASSET_ID, LAYER_ASSET_ID_DESC),
                renderTypes == null || !hasMethod(renderTypes, DECAL_TYPE, DECAL_TYPE_DESC));
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        return owner.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static boolean hasField(ClassNode owner, String name, String desc) {
        return owner.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc));
    }
}
