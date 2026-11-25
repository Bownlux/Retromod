/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft 1.21.1's ShaderInstance on a host that has no class under that name, such as Forge 26.2. 26.x links shaders
 * only for its own render pipelines, so the program is never built and every uniform it hands out drops its values.
 */
public class LegacyShaderInstance implements AutoCloseable {

    public final LegacyUniform MODEL_VIEW_MATRIX = LegacyUniform.inertField(null);
    public final LegacyUniform PROJECTION_MATRIX = LegacyUniform.inertField(null);
    public final LegacyUniform INVERSE_VIEW_ROTATION_MATRIX = LegacyUniform.inertField(null);
    public final LegacyUniform TEXTURE_MATRIX = LegacyUniform.inertField(null);
    public final LegacyUniform SCREEN_SIZE = LegacyUniform.inertField(null);
    public final LegacyUniform COLOR_MODULATOR = LegacyUniform.inertField(null);
    public final LegacyUniform LIGHT0_DIRECTION = LegacyUniform.inertField(null);
    public final LegacyUniform LIGHT1_DIRECTION = LegacyUniform.inertField(null);
    public final LegacyUniform GLINT_ALPHA = LegacyUniform.inertField(null);
    public final LegacyUniform FOG_START = LegacyUniform.inertField(null);
    public final LegacyUniform FOG_END = LegacyUniform.inertField(null);
    public final LegacyUniform FOG_COLOR = LegacyUniform.inertField(null);
    public final LegacyUniform FOG_SHAPE = LegacyUniform.inertField(null);
    public final LegacyUniform LINE_WIDTH = LegacyUniform.inertField(null);
    public final LegacyUniform GAME_TIME = LegacyUniform.inertField(null);
    public final LegacyUniform CHUNK_OFFSET = LegacyUniform.inertField(null);

    // Shader mods read these through access transformers; they stay empty, as no uniform is ever parsed.
    protected final List<LegacyUniform> uniforms = new ArrayList<>();
    protected final Map<String, LegacyUniform> uniformMap = new HashMap<>();
    private final String name;
    private final VertexFormat vertexFormat;

    public LegacyShaderInstance(ResourceProvider resources, String name, VertexFormat format) {
        this.name = name;
        this.vertexFormat = format;
    }

    public LegacyShaderInstance(ResourceProvider resources, Identifier id, VertexFormat format) {
        this(resources, String.valueOf(id), format);
    }

    public LegacyUniform getUniform(String name) {
        return LegacyUniform.inert(this, name);
    }

    public LegacyUniform safeGetUniform(String name) {
        return LegacyUniform.inert(this, name);
    }

    public String getName() {
        return name;
    }

    public VertexFormat getVertexFormat() {
        return vertexFormat;
    }

    /** No GL program, as the shader is never linked. */
    public int getId() {
        return 0;
    }

    // Binding, clearing and sampler calls were for an immediate draw, which 26.x skips.
    public void apply() {}
    public void clear() {}
    public void markDirty() {}
    public void attachToProgram() {}
    public void setSampler(String name, Object sampler) {}
    public void close() {}
}
