/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.block;

import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FireBlock;

import java.lang.reflect.Method;

/** Old {@code FireBlock.setFlammable}, which Fabric mods reached through an access widener Fabric API no longer ships. */
public final class LegacyFlammables {

    private static final String REGISTRY = "net.fabricmc.fabric.api.registry.FlammableBlockRegistry";

    private LegacyFlammables() {}

    /** Records the block's fire odds with Fabric API's flammable registry, which the fire block reads. */
    public static void setFlammable(FireBlock fire, Block block, int encouragement, int flammability) {
        try {
            Class<?> registry = Class.forName(REGISTRY, true, fire.getClass().getClassLoader());
            Object forFire = registry.getMethod("getInstance", Block.class).invoke(null, fire);
            Method add = registry.getMethod("add", Block.class, int.class, int.class);
            add.invoke(forFire, block, encouragement, flammability);
        } catch (ReflectiveOperationException | LinkageError missing) {
            LegacyLog.warnOnce("legacy-flammable", "An old mod set fire odds for " + block + ", but Fabric API's "
                    + "flammable block registry is not available (" + missing + "), so that block does not burn.");
        }
    }
}
