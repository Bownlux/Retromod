/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

/** NeoForge 1.21.1's RenderLevelStageEvent.Stage, rebuilt on 26.x, which posts one event class per stage instead. */
public final class LegacyRenderLevelStage {

    // Old handlers compare stages by identity, so every old stage that now falls in one 26.x event is that event's stage.
    public static final LegacyRenderLevelStage AFTER_SKY = new LegacyRenderLevelStage("after_sky");
    public static final LegacyRenderLevelStage AFTER_SOLID_BLOCKS = new LegacyRenderLevelStage("after_opaque_blocks");
    public static final LegacyRenderLevelStage AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS = AFTER_SOLID_BLOCKS;
    public static final LegacyRenderLevelStage AFTER_CUTOUT_BLOCKS = AFTER_SOLID_BLOCKS;
    public static final LegacyRenderLevelStage AFTER_ENTITIES = new LegacyRenderLevelStage("after_opaque_features");
    public static final LegacyRenderLevelStage AFTER_BLOCK_ENTITIES = AFTER_ENTITIES;
    public static final LegacyRenderLevelStage AFTER_TRANSLUCENT_BLOCKS = new LegacyRenderLevelStage("after_translucent_blocks");
    public static final LegacyRenderLevelStage AFTER_TRIPWIRE_BLOCKS = AFTER_TRANSLUCENT_BLOCKS;
    public static final LegacyRenderLevelStage AFTER_PARTICLES = new LegacyRenderLevelStage("after_particles");
    public static final LegacyRenderLevelStage AFTER_WEATHER = new LegacyRenderLevelStage("after_weather");
    public static final LegacyRenderLevelStage AFTER_LEVEL = new LegacyRenderLevelStage("after_level");
    // Stages 26.x added, which no old handler asks for.
    static final LegacyRenderLevelStage OTHER = new LegacyRenderLevelStage("other");

    private final String name;

    private LegacyRenderLevelStage(String name) {
        this.name = name;
    }

    /** The stage an event of this 26.x class stands for, by the event class's own name. */
    public static LegacyRenderLevelStage of(Object event) {
        switch (event == null ? "" : event.getClass().getSimpleName()) {
            case "AfterSky":
                return AFTER_SKY;
            case "AfterOpaqueBlocks":
                return AFTER_SOLID_BLOCKS;
            case "AfterOpaqueFeatures":
                return AFTER_ENTITIES;
            case "AfterTranslucentBlocks":
                return AFTER_TRANSLUCENT_BLOCKS;
            case "AfterTranslucentParticles":
                return AFTER_PARTICLES;
            case "AfterWeather":
                return AFTER_WEATHER;
            case "AfterLevel":
                return AFTER_LEVEL;
            default:
                return OTHER;
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
