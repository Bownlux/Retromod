/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.AuxiliaryVersionShim;
import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

/**
 * Follows the Zine library's move from Yarn-style to Mojang-style names.
 *
 * <p>Zine 1.8 for Minecraft 1.21.11 named its helpers after Yarn's types:
 * {@code PacketCodecUtil.enumPacketCodec}, {@code BOX} and {@code BLOCK_BOX}, and a
 * {@code common/block_entity} package. The 26.x builds renamed them after Mojang's types:
 * {@code StreamCodecUtil.ofEnum}, {@code AABB}, {@code BOUNDING_BOX}, and
 * {@code common/block/entity}. A mod built against Zine 1.8 then fails on the installed Zine with
 * {@code NoClassDefFoundError: com/eightsidedsquare/zine/common/util/network/PacketCodecUtil}, often
 * from a component's static initializer, which takes the whole mod down.
 *
 * <p>The installed Zine decides each rename: a class is renamed only when that Zine has the new
 * class and not the old one, and the codec members follow the codec class. A mod built for the new
 * Zine never names the old classes, so it passes through unchanged.
 *
 * <p>It covers the renames whose destination has the same members. Zine also removed its tooltip
 * submenu callback and its particle texture data consumer, and a mod reaching those still fails.
 */
public class ZineApiShim implements AuxiliaryVersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Shim");

    static final String OLD_CODEC_UTIL = "com/eightsidedsquare/zine/common/util/network/PacketCodecUtil";
    static final String NEW_CODEC_UTIL = "com/eightsidedsquare/zine/common/util/network/StreamCodecUtil";
    static final String OLD_SYNCING_BLOCK_ENTITY =
            "com/eightsidedsquare/zine/common/block_entity/SyncingBlockEntity";
    static final String NEW_SYNCING_BLOCK_ENTITY =
            "com/eightsidedsquare/zine/common/block/entity/SyncingBlockEntity";

    @Override
    public String getShimName() {
        return "Zine API Compatibility";
    }

    @Override
    public String getSourceVersion() {
        return "1.8.0";
    }

    @Override
    public String getTargetVersion() {
        return "1.10.0";
    }

    @Override
    public String getModLoaderType() {
        return "fabric";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        registerIfHostRenamed(transformer, ClassResourceInspector::exists);
    }

    /** @return whether any rename was registered */
    static boolean registerIfHostRenamed(RetromodTransformer transformer,
            Predicate<String> hostHasClass) {
        boolean codecUtilRenamed = renamedOnHost(OLD_CODEC_UTIL, NEW_CODEC_UTIL, hostHasClass);
        boolean syncingRenamed = renamedOnHost(OLD_SYNCING_BLOCK_ENTITY, NEW_SYNCING_BLOCK_ENTITY,
                hostHasClass);
        if (codecUtilRenamed) {
            transformer.registerClassRedirect(OLD_CODEC_UTIL, NEW_CODEC_UTIL);
            // The class redirect can rename the owner before the member is looked up, so each
            // member rename is listed under both owners.
            for (String owner : new String[]{OLD_CODEC_UTIL, NEW_CODEC_UTIL}) {
                transformer.registerMethodRename(owner, "enumPacketCodec", "ofEnum");
                transformer.registerFieldRedirect(owner, "BOX", NEW_CODEC_UTIL, "AABB");
                transformer.registerFieldRedirect(owner, "BLOCK_BOX", NEW_CODEC_UTIL, "BOUNDING_BOX");
            }
        }
        if (syncingRenamed) {
            transformer.registerClassRedirect(OLD_SYNCING_BLOCK_ENTITY, NEW_SYNCING_BLOCK_ENTITY);
        }
        if (!codecUtilRenamed && !syncingRenamed) return false;
        LOGGER.info("Registered Zine 1.8 to 1.10 renames for the installed Zine");
        return true;
    }

    private static boolean renamedOnHost(String oldClass, String newClass,
            Predicate<String> hostHasClass) {
        return hostHasClass.test(newClass) && !hostHasClass.test(oldClass);
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
