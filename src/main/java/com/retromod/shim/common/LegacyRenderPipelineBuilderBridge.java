/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Adapts the 26.1 render-pipeline builder calls replaced by bind groups in 26.2. */
public final class LegacyRenderPipelineBuilderBridge {
    private static final String OWNER = "com/mojang/blaze3d/pipeline/RenderPipeline$Builder";
    private static final String BUILDER_DESC = "L" + OWNER + ";";
    private static final String BRIDGE =
            "com/retromod/shim/common/LegacyRenderPipelineBuilderBridge";
    private static final Map<Object, Set<String>> PENDING_SAMPLERS = new WeakHashMap<>();

    private LegacyRenderPipelineBuilderBridge() {
    }

    public static void register(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, BRIDGE,
                LegacyRenderPipelineBuilderBridge.class);

        transformer.registerMethodRedirect(
                OWNER, "withSampler", "(Ljava/lang/String;)" + BUILDER_DESC,
                BRIDGE, "withSampler", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");
        transformer.registerMethodRedirect(
                OWNER, "withBlend",
                "(Lcom/mojang/blaze3d/pipeline/BlendFunction;)" + BUILDER_DESC,
                BRIDGE, "withBlend", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        transformer.registerMethodRedirect(
                OWNER, "withFragmentShader", "(Ljava/lang/String;)" + BUILDER_DESC,
                BRIDGE, "withFragmentShader",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");

        String oldVertexFormat = "(Lcom/mojang/blaze3d/vertex/VertexFormat;"
                + "Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;)" + BUILDER_DESC;
        String movedVertexFormat = "(Lcom/mojang/blaze3d/vertex/VertexFormat;"
                + "Lcom/mojang/blaze3d/PrimitiveTopology;)" + BUILDER_DESC;
        String bridgeVertexFormat =
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
        transformer.registerMethodRedirect(OWNER, "withVertexFormat", oldVertexFormat,
                BRIDGE, "withVertexFormat", bridgeVertexFormat);
        transformer.registerMethodRedirect(OWNER, "withVertexFormat", movedVertexFormat,
                BRIDGE, "withVertexFormat", bridgeVertexFormat);

        transformer.registerMethodRedirect(
                OWNER, "build", "()Lcom/mojang/blaze3d/pipeline/RenderPipeline;",
                BRIDGE, "build", "(Ljava/lang/Object;)Ljava/lang/Object;");
        transformer.registerMethodRedirect(
                OWNER, "buildSnippet", "()Lcom/mojang/blaze3d/pipeline/RenderPipeline$Snippet;",
                BRIDGE, "buildSnippet", "(Ljava/lang/Object;)Ljava/lang/Object;");
    }

    /** Defers sampler layout selection until all old sampler names have been collected. */
    public static Object withSampler(Object builder, String sampler) {
        synchronized (PENDING_SAMPLERS) {
            PENDING_SAMPLERS.computeIfAbsent(builder, ignored -> new LinkedHashSet<>()).add(sampler);
        }
        return builder;
    }

    /** Converts the old blend shortcut to 26.2's color-target state. */
    public static Object withBlend(Object builder, Object blendFunction) {
        try {
            ClassLoader loader = builder.getClass().getClassLoader();
            Class<?> blendClass = Class.forName(
                    "com.mojang.blaze3d.pipeline.BlendFunction", false, loader);
            Class<?> colorStateClass = Class.forName(
                    "com.mojang.blaze3d.pipeline.ColorTargetState", false, loader);
            Object colorState = colorStateClass.getConstructor(blendClass)
                    .newInstance(blendFunction);
            return builder.getClass().getMethod("withColorTargetState", colorStateClass)
                    .invoke(builder, colorState);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Losing custom blending is safer than crashing before the title screen.
            return builder;
        }
    }

    /** Maps the deleted item-entity fragment shader onto its 26.2 replacement. */
    public static Object withFragmentShader(Object builder, String shader) {
        String migrated = "core/rendertype_item_entity_translucent_cull".equals(shader)
                ? "core/item" : shader;
        try {
            return builder.getClass().getMethod("withFragmentShader", String.class)
                    .invoke(builder, migrated);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return builder;
        }
    }

    /** Splits the old combined vertex-format call into its 26.2 builder operations. */
    public static Object withVertexFormat(Object builder, Object format, Object topology) {
        try {
            ClassLoader loader = builder.getClass().getClassLoader();
            Class<?> formatClass = Class.forName(
                    "com.mojang.blaze3d.vertex.VertexFormat", false, loader);
            Class<?> topologyClass = Class.forName(
                    "com.mojang.blaze3d.PrimitiveTopology", false, loader);
            Object result = builder.getClass().getMethod(
                    "withVertexBinding", int.class, formatClass).invoke(builder, 0, format);
            return result.getClass().getMethod("withPrimitiveTopology", topologyClass)
                    .invoke(result, topology);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return builder;
        }
    }

    public static Object build(Object builder) {
        return finish(builder, "build");
    }

    public static Object buildSnippet(Object builder) {
        return finish(builder, "buildSnippet");
    }

    private static Object finish(Object builder, String terminalMethod) {
        applySamplerLayout(builder);
        try {
            return builder.getClass().getMethod(terminalMethod).invoke(builder);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Could not finish a migrated render pipeline", cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not call RenderPipeline.Builder."
                    + terminalMethod, e);
        }
    }

    private static void applySamplerLayout(Object builder) {
        Set<String> samplers;
        synchronized (PENDING_SAMPLERS) {
            samplers = PENDING_SAMPLERS.remove(builder);
        }
        String layoutField = samplerLayoutField(samplers);
        if (layoutField == null) return;
        try {
            ClassLoader loader = builder.getClass().getClassLoader();
            Class<?> layoutsClass = Class.forName(
                    "net.minecraft.client.renderer.BindGroupLayouts", false, loader);
            Class<?> layoutClass = Class.forName(
                    "com.mojang.blaze3d.pipeline.BindGroupLayout", false, loader);
            Object layout = layoutsClass.getField(layoutField).get(null);
            builder.getClass().getMethod("withBindGroupLayout", layoutClass)
                    .invoke(builder, layout);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A custom shader may render incorrectly, but the removed call must not kill startup.
        }
    }

    static String samplerLayoutField(Set<String> samplers) {
        if (samplers == null || samplers.isEmpty()) return null;
        boolean sampler0 = samplers.contains("Sampler0");
        boolean sampler1 = samplers.contains("Sampler1");
        boolean sampler2 = samplers.contains("Sampler2");
        if (sampler0 && sampler1 && sampler2) return "SAMPLER0_SAMPLER1_SAMPLER2";
        if (sampler0 && sampler1) return "SAMPLER0_SAMPLER1";
        if (sampler0 && sampler2) return "SAMPLER0_SAMPLER2";
        if (sampler0) return "SAMPLER0";
        if (sampler1) return "SAMPLER1";
        if (sampler2) return "SAMPLER2";
        return null;
    }
}
