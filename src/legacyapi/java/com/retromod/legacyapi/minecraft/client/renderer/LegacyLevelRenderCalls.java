/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;

/** LevelRenderer and Minecraft calls that 26.2 moved to the game renderer, the level extractor, and LightCoordsUtil. */
public final class LegacyLevelRenderCalls {

    private LegacyLevelRenderCalls() {}

    /** {@code Minecraft.getMainRenderTarget()}: the game renderer's main target. */
    public static RenderTarget mainRenderTarget(Minecraft minecraft) {
        return minecraft.gameRenderer.mainRenderTarget();
    }

    /** {@code LevelRenderer.allChanged()}: the level extractor rebuilds every chunk section, as the old call did. */
    public static void allChanged(LevelRenderer levelRenderer) {
        Minecraft.getInstance().levelExtractor.allChanged();
    }

    /** {@code LevelRenderer.needsUpdate()}: the section graph and the clouds are rebuilt on the next frame. */
    public static void needsUpdate(LevelRenderer levelRenderer) {
        levelRenderer.sectionOcclusionGraph().invalidate();
        levelRenderer.cloudRenderer().markForRebuild();
    }

    /** {@code LevelRenderer.getLightColor(level, state, pos)}: the packed light with the default brightness. */
    public static int lightColor(BlockAndLightGetter level, BlockState state, BlockPos pos) {
        return LightCoordsUtil.getLightCoords(LightCoordsUtil.BrightnessGetter.DEFAULT, level, state, pos);
    }
}
