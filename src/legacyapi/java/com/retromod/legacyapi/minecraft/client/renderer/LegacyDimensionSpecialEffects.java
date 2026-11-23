/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** The old DimensionSpecialEffects, rebuilt as values read from the game's dimension type and environment attributes. */
public abstract class LegacyDimensionSpecialEffects {

    // Public under their old names, since mods widened and read the old private fields.
    public final float cloudLevel;
    public final boolean hasGround;
    public final SkyType skyType;
    public final boolean forceBrightLightmap;
    public final boolean constantAmbientLight;

    public LegacyDimensionSpecialEffects(float cloudLevel, boolean hasGround, SkyType skyType, boolean forceBrightLightmap,
            boolean constantAmbientLight) {
        this.cloudLevel = cloudLevel;
        this.hasGround = hasGround;
        this.skyType = skyType;
        this.forceBrightLightmap = forceBrightLightmap;
        this.constantAmbientLight = constantAmbientLight;
    }

    /** {@code DimensionSpecialEffects.forType(type)}: the effects the game's own settings for that type describe. */
    public static LegacyDimensionSpecialEffects forType(DimensionType type) {
        // The cloud height lives on the level, so it is read from the client level when that level is of this type.
        ClientLevel level = currentLevel();
        return new HostEffects(type, level != null && level.dimensionType() == type ? level : null);
    }

    /** {@code level.effects()}, which 26.x removed with the class. */
    public static LegacyDimensionSpecialEffects effects(ClientLevel level) {
        LegacyLog.warnOnce("legacy dimension effects", "An old mod read a dimension's special effects, which this Minecraft version"
                + " replaced with environment attributes, so it gets values read from the dimension's settings. Its fog color"
                + " adjustment is left to the game.");
        return new HostEffects(level == null ? null : level.dimensionType(), level);
    }

    public float getCloudHeight() {
        return cloudLevel;
    }

    public boolean hasGround() {
        return hasGround;
    }

    public SkyType skyType() {
        return skyType;
    }

    public boolean forceBrightLightmap() {
        return forceBrightLightmap;
    }

    public boolean constantAmbientLight() {
        return constantAmbientLight;
    }

    /** The sky gradient at sunrise, which 26.x computes elsewhere; none here. */
    public float[] getSunriseColor(float timeOfDay, float partialTick) {
        return null;
    }

    /** Lightmap tweaks a dimension made, which 26.x reads from attributes; nothing changes. */
    public void adjustLightmapColors(ClientLevel level, float partialTick, float skyDarken, float blockLightRedFlicker,
            float skyLight, int pixelX, int pixelY, Vector3f colors) {
    }

    public abstract Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness);

    public abstract boolean isFoggyAt(int x, int y);

    /** The sky kinds; OVERWORLD is the later name of NORMAL. */
    public enum SkyType {
        NONE, NORMAL, OVERWORLD, END
    }

    /** The overworld's kind: the normal sky, ground and changing ambient light, read from the current level where it fits. */
    public static class OverworldEffects extends LegacyDimensionSpecialEffects {
        public OverworldEffects() {
            super(currentClouds(), true, SkyType.NORMAL, false, false);
        }

        @Override
        public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
            return fogColor;
        }

        @Override
        public boolean isFoggyAt(int x, int y) {
            return false;
        }
    }

    /** The nether's kind: no sky or clouds, fog everywhere, constant ambient light. */
    public static class NetherEffects extends LegacyDimensionSpecialEffects {
        public NetherEffects() {
            super(Float.NaN, true, SkyType.NONE, false, true);
        }

        @Override
        public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
            return fogColor;
        }

        @Override
        public boolean isFoggyAt(int x, int y) {
            return true;
        }
    }

    /** The end's kind: the end sky, no ground or clouds, a bright lightmap. */
    public static class EndEffects extends LegacyDimensionSpecialEffects {
        public EndEffects() {
            super(Float.NaN, false, SkyType.END, true, false);
        }

        @Override
        public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
            return fogColor;
        }

        @Override
        public boolean isFoggyAt(int x, int y) {
            return false;
        }
    }

    /** Effects read from a dimension type's skybox and cardinal lighting, and the level's cloud height attribute. */
    private static final class HostEffects extends LegacyDimensionSpecialEffects {
        HostEffects(DimensionType type, ClientLevel level) {
            super(cloudsOf(level, skybox(type)), !"END".equals(skybox(type)), skyTypeOf(skybox(type)), "END".equals(skybox(type)),
                    netherLighting(type));
        }

        // The game applies its own fog color from the environment attributes, so the old adjustment is not repeated.
        @Override
        public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
            return fogColor;
        }

        // The old dimension with constant ambient light was the one with fog everywhere.
        @Override
        public boolean isFoggyAt(int x, int y) {
            return constantAmbientLight;
        }
    }

    /** The type's skybox by name (NONE, OVERWORLD or END), or OVERWORLD when there is no type. */
    private static String skybox(DimensionType type) {
        DimensionType.Skybox skybox = type == null ? null : type.skybox();
        return skybox == null ? "OVERWORLD" : skybox.name();
    }

    private static SkyType skyTypeOf(String skybox) {
        return switch (skybox) {
            case "NONE" -> SkyType.NONE;
            case "END" -> SkyType.END;
            default -> SkyType.NORMAL;
        };
    }

    private static boolean netherLighting(DimensionType type) {
        CardinalLighting.Type lighting = type == null ? null : type.cardinalLightType();
        return lighting != null && "NETHER".equals(lighting.name());
    }

    /** The level's cloud height for a dimension with the normal sky, NaN otherwise or when it cannot be read, as old values were. */
    private static float cloudsOf(ClientLevel level, String skybox) {
        if (level == null || !"OVERWORLD".equals(skybox)) return Float.NaN;
        try {
            EnvironmentAttributeReader attributes = level.environmentAttributes();
            Float height = attributes == null ? null : attributes.getDimensionValue(EnvironmentAttributes.CLOUD_HEIGHT);
            return height == null ? Float.NaN : height;
        } catch (RuntimeException | LinkageError unreadable) {
            return Float.NaN;
        }
    }

    /** The current client level's cloud height, when that level has the normal sky. */
    private static float currentClouds() {
        ClientLevel level = currentLevel();
        return level == null ? Float.NaN : cloudsOf(level, skybox(level.dimensionType()));
    }

    private static ClientLevel currentLevel() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft == null ? null : minecraft.level;
        } catch (RuntimeException | LinkageError notYet) {
            return null;
        }
    }
}
