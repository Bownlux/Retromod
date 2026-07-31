/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.EnvironmentDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redirects the removed Fabric convention tags v1
 * ({@code net.fabricmc.fabric.api.tag.convention.v1.*}) onto v2.
 *
 * <p>Most item-tag fields kept their names from v1 to v2 and ride the class redirect; the
 * renamed ones get explicit field redirects. Fields with no v2 equivalent are left unmapped,
 * so a mod touching one hits a {@code NoSuchFieldError} for that tag rather than a
 * {@code NoClassDefFoundError} for the whole class. Gated on v2 being present (probed without
 * initializing, #14), since redirecting onto a missing class is worse than leaving v1 alone.
 */
public class FabricConventionTagsShim implements VersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String V1 = "net/fabricmc/fabric/api/tag/convention/v1/";
    private static final String V2 = "net/fabricmc/fabric/api/tag/convention/v2/";

    /** v1 holder classes, each with a same-named v2 successor. */
    private static final String[] HOLDERS = {
        "ConventionalItemTags",
        "ConventionalBlockTags",
        "ConventionalBiomeTags",
        "ConventionalEnchantmentTags",
        "ConventionalEntityTypeTags",
        "ConventionalFluidTags",
    };

    /** v1 item-tag fields whose v2 name differs. Same {@code TagKey} type both sides. */
    private static final String[][] ITEM_FIELD_RENAMES = {
        {"SHEARS", "SHEAR_TOOLS"},
        {"BOWS", "BOW_TOOLS"},
        {"SHIELDS", "SHIELD_TOOLS"},
        {"SPEARS", "TRIDENT_TOOLS"},
        {"RAW_ORES", "RAW_MATERIALS"},
        {"RAW_IRON_ORES", "IRON_RAW_MATERIALS"},
        {"RAW_COPPER_ORES", "COPPER_RAW_MATERIALS"},
        {"RAW_GOLD_ORES", "GOLD_RAW_MATERIALS"},
        {"RAW_IRON_BLOCKS", "STORAGE_BLOCKS_RAW_IRON"},
        {"RAW_COPPER_BLOCKS", "STORAGE_BLOCKS_RAW_COPPER"},
        {"RAW_GOLD_BLOCKS", "STORAGE_BLOCKS_RAW_GOLD"},
        {"EMPTY_BUCKET", "EMPTY_BUCKETS"},
        {"WATER_BUCKET", "WATER_BUCKETS"},
        {"LAVA_BUCKET", "LAVA_BUCKETS"},
        {"MILK_BUCKET", "MILK_BUCKETS"},
        {"DIAMONDS", "DIAMOND_GEMS"},
        {"EMERALDS", "EMERALD_GEMS"},
        {"LAPIS", "LAPIS_GEMS"},
        {"QUARTZ", "QUARTZ_GEMS"},
    };

    @Override public String getShimName() { return "Fabric convention tags v1 → v2"; }
    @Override public String getSourceVersion() { return "0.92.0"; }
    @Override public String getTargetVersion() { return "0.100.0"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // v2 ships on 26.1+; on older hosts probe before redirecting.
        boolean v2Present = com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                        com.retromod.core.RetromodVersion.TARGET_MC_VERSION)
                || EnvironmentDetector.hostClassExists(
                        "net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags");
        if (!v2Present) {
            LOGGER.debug("Fabric convention tag v2 is unavailable, so the v1 bridge is not needed");
            return;
        }

        for (String holder : HOLDERS) {
            transformer.registerClassRedirect(V1 + holder, V2 + holder);
        }
        // Key on the v2 owner: ClassRemapper rewrites the GETSTATIC owner before field redirects run.
        String itemOwner = V2 + "ConventionalItemTags";
        for (String[] r : ITEM_FIELD_RENAMES) {
            transformer.registerFieldRedirect(itemOwner, r[0], itemOwner, r[1]);
        }

        LOGGER.debug("Added Fabric convention tag v1 support with {} holder redirects and "
                        + "{} field renames",
                HOLDERS.length, ITEM_FIELD_RENAMES.length);
    }
}
