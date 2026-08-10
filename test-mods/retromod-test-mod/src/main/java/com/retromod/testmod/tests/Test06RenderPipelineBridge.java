/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;

/** Load-time smoke test for the 26.2 legacy render-pipeline bridge. */
public class Test06RenderPipelineBridge implements Test {

    @Override
    public String description() {
        return "legacy RenderPipeline.Builder bridge";
    }

    @Override
    public TestResult run() {
        try {
            Class<?> bridge = Class.forName(
                    "com.retromod.shim.common.LegacyRenderPipelineBuilderBridge");
            bridge.getMethod("withSampler", Object.class, String.class);
            bridge.getMethod("withFragmentShader", Object.class, String.class);
            bridge.getMethod("withBlend", Object.class, Object.class);
            bridge.getMethod("withVertexFormat", Object.class, Object.class, Object.class);
            bridge.getMethod("build", Object.class);

            Class<?> formats = Class.forName(
                    "com.mojang.blaze3d.vertex.DefaultVertexFormat");
            formats.getField("ENTITY");
            return TestResult.success();
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
