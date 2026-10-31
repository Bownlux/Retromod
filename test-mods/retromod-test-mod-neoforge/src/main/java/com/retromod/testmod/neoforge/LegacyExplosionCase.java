/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.EventHooks;

/**
 * #366: ProjectE's nova catalyst extends {@code Explosion}, which 1.21.2 turned into an interface,
 * and lists its blocks in {@code explode()} before breaking them in {@code finalizeExplosion}.
 */
public class LegacyExplosionCase extends Explosion {

    private boolean finalized;

    public LegacyExplosionCase(Level level, double x, double y, double z, float radius, Explosion.BlockInteraction interaction) {
        super(level, null, x, y, z, radius, false, interaction);
    }

    @Override
    public void finalizeExplosion(boolean spawnParticles) {
        finalized = true;
        super.finalizeExplosion(spawnParticles);
    }

    /** Blows up two dirt blocks high above the world origin and reports the first step that went wrong. */
    static String run(ServerLevel level) {
        if (level.isClientSide || level.random == null) return "fail: the level reads as a client level";
        BlockPos first = new BlockPos(0, 300, 0);
        BlockPos second = first.east();
        level.setBlockAndUpdate(first, Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(second, Blocks.DIRT.defaultBlockState());
        LegacyExplosionCase explosion = new LegacyExplosionCase(level, 0.5, 300.5, 0.5, 3.0F, Explosion.BlockInteraction.DESTROY);
        if (EventHooks.onExplosionStart(level, explosion)) return "fail: the start event was canceled";
        explosion.explode();
        if (!explosion.getToBlow().contains(first)) {
            return "fail: explode() listed " + explosion.getToBlow().size() + " blocks, without the dirt";
        }
        if (!level.getBlockState(first).is(Blocks.DIRT)) return "fail: explode() broke the dirt before finalizeExplosion";
        explosion.finalizeExplosion(false);
        if (!explosion.finalized) return "fail: the subclass's finalizeExplosion did not run";
        if (!level.getBlockState(first).isAir() || !level.getBlockState(second).isAir()) {
            return "fail: finalizeExplosion left the dirt";
        }
        return "success";
    }

    /** The game rule read that picks a nova's block interaction, which only a server level answers now. */
    static String gameRuleRead(ServerLevel level) {
        boolean decay = level.getGameRules().getBoolean(GameRules.RULE_TNT_EXPLOSION_DROP_DECAY);
        return decay == level.getServer().getGameRules().getBoolean(GameRules.RULE_TNT_EXPLOSION_DROP_DECAY)
                ? "success" : "fail: the level and the server disagree";
    }
}
