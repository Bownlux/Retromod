/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.rendertype;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;

/** The old RenderStateShard family, kept as plain values that name what each shard asked for; 26.3 builds state from pipelines. */
public class LegacyRenderStateShard {

    // The old shards ran GL code here; 26.3 has no global state to set, so the rebuilt ones run nothing.
    static final Runnable NOTHING = () -> {};

    protected final String name;
    // Rebuilt vanilla constants are known by name; a mod's own shard carries setup code that cannot run.
    boolean vanilla;

    public static final ShaderStateShard NO_SHADER = ShaderStateShard.named("NO_SHADER");
    public static final ShaderStateShard POSITION_COLOR_SHADER = ShaderStateShard.named("POSITION_COLOR_SHADER");
    public static final ShaderStateShard POSITION_TEX_SHADER = ShaderStateShard.named("POSITION_TEX_SHADER");
    public static final ShaderStateShard POSITION_TEX_COLOR_SHADER = ShaderStateShard.named("POSITION_TEX_COLOR_SHADER");
    public static final ShaderStateShard POSITION_COLOR_TEX_LIGHTMAP_SHADER = ShaderStateShard.named("POSITION_COLOR_TEX_LIGHTMAP_SHADER");
    public static final ShaderStateShard POSITION_COLOR_LIGHTMAP_SHADER = ShaderStateShard.named("POSITION_COLOR_LIGHTMAP_SHADER");
    public static final ShaderStateShard PARTICLE_SHADER = ShaderStateShard.named("PARTICLE_SHADER");
    public static final ShaderStateShard RENDERTYPE_SOLID_SHADER = ShaderStateShard.named("RENDERTYPE_SOLID_SHADER");
    public static final ShaderStateShard RENDERTYPE_CUTOUT_MIPPED_SHADER = ShaderStateShard.named("RENDERTYPE_CUTOUT_MIPPED_SHADER");
    public static final ShaderStateShard RENDERTYPE_CUTOUT_SHADER = ShaderStateShard.named("RENDERTYPE_CUTOUT_SHADER");
    public static final ShaderStateShard RENDERTYPE_TRANSLUCENT_SHADER = ShaderStateShard.named("RENDERTYPE_TRANSLUCENT_SHADER");
    public static final ShaderStateShard RENDERTYPE_TRANSLUCENT_MOVING_BLOCK_SHADER = ShaderStateShard.named("RENDERTYPE_TRANSLUCENT_MOVING_BLOCK_SHADER");
    public static final ShaderStateShard RENDERTYPE_ARMOR_CUTOUT_NO_CULL_SHADER = ShaderStateShard.named("RENDERTYPE_ARMOR_CUTOUT_NO_CULL_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_SOLID_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_SOLID_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_CUTOUT_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_CUTOUT_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_CUTOUT_NO_CULL_Z_OFFSET_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_CUTOUT_NO_CULL_Z_OFFSET_SHADER");
    public static final ShaderStateShard RENDERTYPE_ITEM_ENTITY_TRANSLUCENT_CULL_SHADER = ShaderStateShard.named("RENDERTYPE_ITEM_ENTITY_TRANSLUCENT_CULL_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_TRANSLUCENT_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_TRANSLUCENT_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_SMOOTH_CUTOUT_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_SMOOTH_CUTOUT_SHADER");
    public static final ShaderStateShard RENDERTYPE_BEACON_BEAM_SHADER = ShaderStateShard.named("RENDERTYPE_BEACON_BEAM_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_DECAL_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_DECAL_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_NO_OUTLINE_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_NO_OUTLINE_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_SHADOW_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_SHADOW_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_ALPHA_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_ALPHA_SHADER");
    public static final ShaderStateShard RENDERTYPE_EYES_SHADER = ShaderStateShard.named("RENDERTYPE_EYES_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENERGY_SWIRL_SHADER = ShaderStateShard.named("RENDERTYPE_ENERGY_SWIRL_SHADER");
    public static final ShaderStateShard RENDERTYPE_BREEZE_WIND_SHADER = ShaderStateShard.named("RENDERTYPE_BREEZE_WIND_SHADER");
    public static final ShaderStateShard RENDERTYPE_LEASH_SHADER = ShaderStateShard.named("RENDERTYPE_LEASH_SHADER");
    public static final ShaderStateShard RENDERTYPE_WATER_MASK_SHADER = ShaderStateShard.named("RENDERTYPE_WATER_MASK_SHADER");
    public static final ShaderStateShard RENDERTYPE_OUTLINE_SHADER = ShaderStateShard.named("RENDERTYPE_OUTLINE_SHADER");
    public static final ShaderStateShard RENDERTYPE_ARMOR_GLINT_SHADER = ShaderStateShard.named("RENDERTYPE_ARMOR_GLINT_SHADER");
    public static final ShaderStateShard RENDERTYPE_ARMOR_ENTITY_GLINT_SHADER = ShaderStateShard.named("RENDERTYPE_ARMOR_ENTITY_GLINT_SHADER");
    public static final ShaderStateShard RENDERTYPE_GLINT_TRANSLUCENT_SHADER = ShaderStateShard.named("RENDERTYPE_GLINT_TRANSLUCENT_SHADER");
    public static final ShaderStateShard RENDERTYPE_GLINT_SHADER = ShaderStateShard.named("RENDERTYPE_GLINT_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_GLINT_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_GLINT_SHADER");
    public static final ShaderStateShard RENDERTYPE_ENTITY_GLINT_DIRECT_SHADER = ShaderStateShard.named("RENDERTYPE_ENTITY_GLINT_DIRECT_SHADER");
    public static final ShaderStateShard RENDERTYPE_CRUMBLING_SHADER = ShaderStateShard.named("RENDERTYPE_CRUMBLING_SHADER");
    public static final ShaderStateShard RENDERTYPE_TEXT_SHADER = ShaderStateShard.named("RENDERTYPE_TEXT_SHADER");
    public static final ShaderStateShard RENDERTYPE_TEXT_BACKGROUND_SHADER = ShaderStateShard.named("RENDERTYPE_TEXT_BACKGROUND_SHADER");
    public static final ShaderStateShard RENDERTYPE_TEXT_INTENSITY_SHADER = ShaderStateShard.named("RENDERTYPE_TEXT_INTENSITY_SHADER");
    public static final ShaderStateShard RENDERTYPE_TEXT_SEE_THROUGH_SHADER = ShaderStateShard.named("RENDERTYPE_TEXT_SEE_THROUGH_SHADER");
    public static final ShaderStateShard RENDERTYPE_TEXT_BACKGROUND_SEE_THROUGH_SHADER = ShaderStateShard.named("RENDERTYPE_TEXT_BACKGROUND_SEE_THROUGH_SHADER");
    public static final ShaderStateShard RENDERTYPE_TEXT_INTENSITY_SEE_THROUGH_SHADER = ShaderStateShard.named("RENDERTYPE_TEXT_INTENSITY_SEE_THROUGH_SHADER");
    public static final ShaderStateShard RENDERTYPE_LIGHTNING_SHADER = ShaderStateShard.named("RENDERTYPE_LIGHTNING_SHADER");
    public static final ShaderStateShard RENDERTYPE_TRIPWIRE_SHADER = ShaderStateShard.named("RENDERTYPE_TRIPWIRE_SHADER");
    public static final ShaderStateShard RENDERTYPE_END_PORTAL_SHADER = ShaderStateShard.named("RENDERTYPE_END_PORTAL_SHADER");
    public static final ShaderStateShard RENDERTYPE_END_GATEWAY_SHADER = ShaderStateShard.named("RENDERTYPE_END_GATEWAY_SHADER");
    public static final ShaderStateShard RENDERTYPE_CLOUDS_SHADER = ShaderStateShard.named("RENDERTYPE_CLOUDS_SHADER");
    public static final ShaderStateShard RENDERTYPE_LINES_SHADER = ShaderStateShard.named("RENDERTYPE_LINES_SHADER");
    public static final ShaderStateShard RENDERTYPE_GUI_SHADER = ShaderStateShard.named("RENDERTYPE_GUI_SHADER");
    public static final ShaderStateShard RENDERTYPE_GUI_OVERLAY_SHADER = ShaderStateShard.named("RENDERTYPE_GUI_OVERLAY_SHADER");
    public static final ShaderStateShard RENDERTYPE_GUI_TEXT_HIGHLIGHT_SHADER = ShaderStateShard.named("RENDERTYPE_GUI_TEXT_HIGHLIGHT_SHADER");
    public static final ShaderStateShard RENDERTYPE_GUI_GHOST_RECIPE_OVERLAY_SHADER = ShaderStateShard.named("RENDERTYPE_GUI_GHOST_RECIPE_OVERLAY_SHADER");
    public static final TransparencyStateShard NO_TRANSPARENCY = vanilla(new TransparencyStateShard("NO_TRANSPARENCY", NOTHING, NOTHING));
    public static final TransparencyStateShard ADDITIVE_TRANSPARENCY = vanilla(new TransparencyStateShard("ADDITIVE_TRANSPARENCY", NOTHING, NOTHING));
    public static final TransparencyStateShard LIGHTNING_TRANSPARENCY = vanilla(new TransparencyStateShard("LIGHTNING_TRANSPARENCY", NOTHING, NOTHING));
    public static final TransparencyStateShard GLINT_TRANSPARENCY = vanilla(new TransparencyStateShard("GLINT_TRANSPARENCY", NOTHING, NOTHING));
    public static final TransparencyStateShard CRUMBLING_TRANSPARENCY = vanilla(new TransparencyStateShard("CRUMBLING_TRANSPARENCY", NOTHING, NOTHING));
    public static final TransparencyStateShard TRANSLUCENT_TRANSPARENCY = vanilla(new TransparencyStateShard("TRANSLUCENT_TRANSPARENCY", NOTHING, NOTHING));
    public static final EmptyTextureStateShard NO_TEXTURE = vanilla(new EmptyTextureStateShard());
    public static final TextureStateShard BLOCK_SHEET_MIPPED = vanilla(new TextureStateShard(Identifier.withDefaultNamespace("textures/atlas/blocks.png"), false, true));
    public static final TextureStateShard BLOCK_SHEET = vanilla(new TextureStateShard(Identifier.withDefaultNamespace("textures/atlas/blocks.png"), false, false));
    public static final TextureStateShard PARTICLE_SHEET = vanilla(new TextureStateShard(Identifier.withDefaultNamespace("textures/atlas/particles.png"), false, false));
    public static final TexturingStateShard DEFAULT_TEXTURING = vanilla(new TexturingStateShard("DEFAULT_TEXTURING", NOTHING, NOTHING));
    public static final TexturingStateShard GLINT_TEXTURING = vanilla(new TexturingStateShard("GLINT_TEXTURING", NOTHING, NOTHING));
    public static final TexturingStateShard ENTITY_GLINT_TEXTURING = vanilla(new TexturingStateShard("ENTITY_GLINT_TEXTURING", NOTHING, NOTHING));
    public static final LightmapStateShard LIGHTMAP = vanilla(new LightmapStateShard(true));
    public static final LightmapStateShard NO_LIGHTMAP = vanilla(new LightmapStateShard(false));
    public static final OverlayStateShard OVERLAY = vanilla(new OverlayStateShard(true));
    public static final OverlayStateShard NO_OVERLAY = vanilla(new OverlayStateShard(false));
    public static final CullStateShard CULL = vanilla(new CullStateShard(true));
    public static final CullStateShard NO_CULL = vanilla(new CullStateShard(false));
    public static final DepthTestStateShard NO_DEPTH_TEST = vanilla(new DepthTestStateShard("NO_DEPTH_TEST", 519));
    public static final DepthTestStateShard EQUAL_DEPTH_TEST = vanilla(new DepthTestStateShard("EQUAL_DEPTH_TEST", 514));
    public static final DepthTestStateShard LEQUAL_DEPTH_TEST = vanilla(new DepthTestStateShard("LEQUAL_DEPTH_TEST", 515));
    public static final DepthTestStateShard GREATER_DEPTH_TEST = vanilla(new DepthTestStateShard("GREATER_DEPTH_TEST", 516));
    public static final WriteMaskStateShard COLOR_DEPTH_WRITE = vanilla(new WriteMaskStateShard(true, true), "COLOR_DEPTH_WRITE");
    public static final WriteMaskStateShard COLOR_WRITE = vanilla(new WriteMaskStateShard(true, false), "COLOR_WRITE");
    public static final WriteMaskStateShard DEPTH_WRITE = vanilla(new WriteMaskStateShard(false, true), "DEPTH_WRITE");
    public static final LayeringStateShard NO_LAYERING = vanilla(new LayeringStateShard("NO_LAYERING", NOTHING, NOTHING));
    public static final LayeringStateShard POLYGON_OFFSET_LAYERING = vanilla(new LayeringStateShard("POLYGON_OFFSET_LAYERING", NOTHING, NOTHING));
    public static final LayeringStateShard VIEW_OFFSET_Z_LAYERING = vanilla(new LayeringStateShard("VIEW_OFFSET_Z_LAYERING", NOTHING, NOTHING));
    public static final OutputStateShard MAIN_TARGET = vanilla(new OutputStateShard("MAIN_TARGET", NOTHING, NOTHING));
    public static final OutputStateShard OUTLINE_TARGET = vanilla(new OutputStateShard("OUTLINE_TARGET", NOTHING, NOTHING));
    public static final OutputStateShard TRANSLUCENT_TARGET = vanilla(new OutputStateShard("TRANSLUCENT_TARGET", NOTHING, NOTHING));
    public static final OutputStateShard PARTICLES_TARGET = vanilla(new OutputStateShard("PARTICLES_TARGET", NOTHING, NOTHING));
    public static final OutputStateShard WEATHER_TARGET = vanilla(new OutputStateShard("WEATHER_TARGET", NOTHING, NOTHING));
    public static final OutputStateShard CLOUDS_TARGET = vanilla(new OutputStateShard("CLOUDS_TARGET", NOTHING, NOTHING));
    public static final OutputStateShard ITEM_ENTITY_TARGET = vanilla(new OutputStateShard("ITEM_ENTITY_TARGET", NOTHING, NOTHING));
    public static final LineStateShard DEFAULT_LINE = vanilla(new LineStateShard(OptionalDouble.of(1.0)));

    public LegacyRenderStateShard(String name, Runnable setupState, Runnable clearState) {
        this.name = name;
    }

    /** The old shard set GL state here; 26.3 sets state per draw, so nothing runs. */
    public void setupRenderState() {
        if (!vanilla) {
            LegacyLog.warnOnce("render state shard " + name, "The old render state " + name + " set up drawing state by hand,"
                    + " which 26.3 does per draw, so its setup code does not run.");
        }
    }

    public void clearRenderState() {
    }

    /** The name this shard is known by, which the render type resolver reads. */
    public String key() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    static <T extends LegacyRenderStateShard> T vanilla(T shard) {
        shard.vanilla = true;
        return shard;
    }

    // A vanilla shard whose old name did not say which variant it was, so it is renamed after its constant.
    static WriteMaskStateShard vanilla(WriteMaskStateShard shard, String key) {
        shard.vanilla = true;
        shard.key = key;
        return shard;
    }

    /** A shard that is on or off. */
    public static class BooleanStateShard extends LegacyRenderStateShard {
        protected final boolean enabled;

        public BooleanStateShard(String name, Runnable setupState, Runnable clearState, boolean enabled) {
            super(name, setupState, clearState);
            this.enabled = enabled;
        }

        public boolean enabled() {
            return enabled;
        }
    }

    /** Back-face culling. */
    public static class CullStateShard extends BooleanStateShard {
        public CullStateShard(boolean useCull) {
            super("cull", NOTHING, NOTHING, useCull);
        }
    }

    /** Whether the lightmap is sampled. */
    public static class LightmapStateShard extends BooleanStateShard {
        public LightmapStateShard(boolean lightmap) {
            super("lightmap", NOTHING, NOTHING, lightmap);
        }
    }

    /** Whether the overlay (hurt tint) texture is sampled. */
    public static class OverlayStateShard extends BooleanStateShard {
        public OverlayStateShard(boolean overlay) {
            super("overlay", NOTHING, NOTHING, overlay);
        }
    }

    /** The depth comparison, by its old name. */
    public static class DepthTestStateShard extends LegacyRenderStateShard {
        public DepthTestStateShard(String functionName, int depthFunction) {
            super(functionName, NOTHING, NOTHING);
        }
    }

    /** Which buffers are written. */
    public static class WriteMaskStateShard extends LegacyRenderStateShard {
        String key;

        public WriteMaskStateShard(boolean writeColor, boolean writeDepth) {
            super("write_mask_state", NOTHING, NOTHING);
            this.key = writeColor && writeDepth ? "COLOR_DEPTH_WRITE" : writeColor ? "COLOR_WRITE" : "DEPTH_WRITE";
        }

        @Override
        public String key() {
            return key;
        }
    }

    /** Blending, by its old name. */
    public static class TransparencyStateShard extends LegacyRenderStateShard {
        public TransparencyStateShard(String name, Runnable setupState, Runnable clearState) {
            super(name, setupState, clearState);
        }
    }

    /** Depth offset tricks, by their old name. */
    public static class LayeringStateShard extends LegacyRenderStateShard {
        public LayeringStateShard(String name, Runnable setupState, Runnable clearState) {
            super(name, setupState, clearState);
        }
    }

    /** The render target, by its old name. */
    public static class OutputStateShard extends LegacyRenderStateShard {
        public OutputStateShard(String name, Runnable setupState, Runnable clearState) {
            super(name, setupState, clearState);
        }
    }

    /** Texture matrix tricks, by their old name. */
    public static class TexturingStateShard extends LegacyRenderStateShard {
        public TexturingStateShard(String name, Runnable setupState, Runnable clearState) {
            super(name, setupState, clearState);
        }
    }

    /** A fixed texture offset. */
    public static class OffsetTexturingStateShard extends TexturingStateShard {
        public OffsetTexturingStateShard(float u, float v) {
            super("offset_texturing", NOTHING, NOTHING);
        }
    }

    /** No texture, or a mod's own texture binding code. */
    public static class EmptyTextureStateShard extends LegacyRenderStateShard {
        public EmptyTextureStateShard(Runnable setupState, Runnable clearState) {
            super("texture", setupState, clearState);
        }

        public EmptyTextureStateShard() {
            super("NO_TEXTURE", NOTHING, NOTHING);
        }

        /** The texture drawn with, if any. */
        public Optional<Identifier> textureId() {
            return Optional.empty();
        }
    }

    /** One texture, by id. */
    public static class TextureStateShard extends EmptyTextureStateShard {
        protected final Optional<Identifier> texture;
        final boolean blur;

        public TextureStateShard(Identifier texture, boolean blur, boolean mipmap) {
            super(NOTHING, NOTHING);
            this.texture = Optional.of(texture);
            this.blur = blur;
            vanilla = true;
        }

        @Override
        public Optional<Identifier> textureId() {
            return texture;
        }
    }

    /** A shader: a vanilla one by its old constant name, or a mod's own through a supplier. */
    public static class ShaderStateShard extends LegacyRenderStateShard {
        final boolean custom;

        public ShaderStateShard(Supplier<?> shader) {
            super("custom_shader", NOTHING, NOTHING);
            this.custom = true;
            vanilla = true;
        }

        public ShaderStateShard() {
            super("NO_SHADER", NOTHING, NOTHING);
            this.custom = false;
        }

        private ShaderStateShard(String name) {
            super(name, NOTHING, NOTHING);
            this.custom = false;
        }

        static ShaderStateShard named(String name) {
            return vanilla(new ShaderStateShard(name));
        }
    }

    /** A line width, which 26.3 reads from each vertex. */
    public static class LineStateShard extends LegacyRenderStateShard {
        public LineStateShard(OptionalDouble width) {
            super("line_width", NOTHING, NOTHING);
        }
    }
}
