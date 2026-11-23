/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.rendertype;

import com.retromod.legacyapi.minecraft.client.renderer.rendertype.LegacyRenderStateShard.*;
import com.retromod.shim.common.embedded.LegacyRenderTypeResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The old RenderType.CompositeState: the shards a mod chose, described for the render type resolver. */
public final class LegacyCompositeState {

    public final EmptyTextureStateShard textureState;
    public final ShaderStateShard shaderState;
    public final TransparencyStateShard transparencyState;
    public final DepthTestStateShard depthTestState;
    public final CullStateShard cullState;
    public final LightmapStateShard lightmapState;
    public final OverlayStateShard overlayState;
    public final LayeringStateShard layeringState;
    public final OutputStateShard outputState;
    public final TexturingStateShard texturingState;
    public final WriteMaskStateShard writeMaskState;
    public final LineStateShard lineState;
    final boolean affectsOutline;

    private LegacyCompositeState(CompositeStateBuilder builder, boolean affectsOutline) {
        this.textureState = builder.textureState;
        this.shaderState = builder.shaderState;
        this.transparencyState = builder.transparencyState;
        this.depthTestState = builder.depthTestState;
        this.cullState = builder.cullState;
        this.lightmapState = builder.lightmapState;
        this.overlayState = builder.overlayState;
        this.layeringState = builder.layeringState;
        this.outputState = builder.outputState;
        this.texturingState = builder.texturingState;
        this.writeMaskState = builder.writeMaskState;
        this.lineState = builder.lineState;
        this.affectsOutline = affectsOutline;
    }

    /** {@code CompositeState.builder()}. */
    public static CompositeStateBuilder builder() {
        return new CompositeStateBuilder();
    }

    /** Each chosen part by name; parts left unset keep the old defaults, which the resolver knows. */
    public Map<String, Object> describe() {
        Map<String, Object> state = new LinkedHashMap<>();
        List<String> customShards = new ArrayList<>();
        if (shaderState != null) {
            state.put(LegacyRenderTypeResolver.SHADER, shaderState.key());
            state.put(LegacyRenderTypeResolver.CUSTOM_SHADER, shaderState.custom);
        }
        if (textureState != null) {
            textureState.textureId().ifPresent(id -> state.put(LegacyRenderTypeResolver.TEXTURE, id));
            if (textureState instanceof TextureStateShard texture && texture.blur) state.put(LegacyRenderTypeResolver.BLUR, true);
        }
        put(state, LegacyRenderTypeResolver.TRANSPARENCY, transparencyState, customShards);
        put(state, LegacyRenderTypeResolver.DEPTH, depthTestState, customShards);
        put(state, LegacyRenderTypeResolver.WRITE, writeMaskState, customShards);
        put(state, LegacyRenderTypeResolver.LAYERING, layeringState, customShards);
        put(state, LegacyRenderTypeResolver.OUTPUT, outputState, customShards);
        put(state, LegacyRenderTypeResolver.TEXTURING, texturingState, customShards);
        if (cullState != null) state.put(LegacyRenderTypeResolver.CULL, cullState.enabled());
        if (lightmapState != null) state.put(LegacyRenderTypeResolver.LIGHTMAP, lightmapState.enabled());
        if (overlayState != null) state.put(LegacyRenderTypeResolver.OVERLAY, overlayState.enabled());
        if (lineState != null && lineState != LegacyRenderStateShard.DEFAULT_LINE) state.put(LegacyRenderTypeResolver.LINE, true);
        if (textureState != null && !textureState.vanilla) customShards.add(textureState.key());
        if (affectsOutline) state.put(LegacyRenderTypeResolver.OUTLINE, true);
        if (!customShards.isEmpty()) state.put(LegacyRenderTypeResolver.CUSTOM_SHARDS, customShards);
        return state;
    }

    private static void put(Map<String, Object> state, String key, LegacyRenderStateShard shard, List<String> customShards) {
        if (shard == null) return;
        state.put(key, shard.key());
        if (!shard.vanilla) customShards.add(shard.key());
    }

    /** The old CompositeStateBuilder: each setter records its shard. */
    public static final class CompositeStateBuilder {
        EmptyTextureStateShard textureState;
        ShaderStateShard shaderState;
        TransparencyStateShard transparencyState;
        DepthTestStateShard depthTestState;
        CullStateShard cullState;
        LightmapStateShard lightmapState;
        OverlayStateShard overlayState;
        LayeringStateShard layeringState;
        OutputStateShard outputState;
        TexturingStateShard texturingState;
        WriteMaskStateShard writeMaskState;
        LineStateShard lineState;

        CompositeStateBuilder() {}

        public CompositeStateBuilder setTextureState(EmptyTextureStateShard state) {
            textureState = state;
            return this;
        }

        public CompositeStateBuilder setShaderState(ShaderStateShard state) {
            shaderState = state;
            return this;
        }

        public CompositeStateBuilder setTransparencyState(TransparencyStateShard state) {
            transparencyState = state;
            return this;
        }

        public CompositeStateBuilder setDepthTestState(DepthTestStateShard state) {
            depthTestState = state;
            return this;
        }

        public CompositeStateBuilder setCullState(CullStateShard state) {
            cullState = state;
            return this;
        }

        public CompositeStateBuilder setLightmapState(LightmapStateShard state) {
            lightmapState = state;
            return this;
        }

        public CompositeStateBuilder setOverlayState(OverlayStateShard state) {
            overlayState = state;
            return this;
        }

        public CompositeStateBuilder setLayeringState(LayeringStateShard state) {
            layeringState = state;
            return this;
        }

        public CompositeStateBuilder setOutputState(OutputStateShard state) {
            outputState = state;
            return this;
        }

        public CompositeStateBuilder setTexturingState(TexturingStateShard state) {
            texturingState = state;
            return this;
        }

        public CompositeStateBuilder setWriteMaskState(WriteMaskStateShard state) {
            writeMaskState = state;
            return this;
        }

        public CompositeStateBuilder setLineState(LineStateShard state) {
            lineState = state;
            return this;
        }

        /** {@code createCompositeState(affectsOutline)}. */
        public LegacyCompositeState createCompositeState(boolean affectsOutline) {
            return new LegacyCompositeState(this, affectsOutline);
        }
    }
}
