/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 RevivalSMP. Licensed under MIT.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.EnvironmentDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.MinecraftVersionedApiShim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges low-level rendering calls when Minecraft moves off OpenGL to a Vulkan/Metal backend.
 * Mods on the high-level API (DrawContext, VertexConsumer) are unaffected; only those reaching
 * into GlStateManager or direct LWJGL calls need redirects.
 */
public class RenderingBackendShim implements MinecraftVersionedApiShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-RenderBackend");

    @Override
    public String getShimName() {
        return "RenderingBackendCompat";
    }

    @Override
    public String getSourceVersion() {
        return "1.21.4";
    }

    @Override
    public String getTargetVersion() {
        return "26.1.0";
    }

    @Override
    public String getModLoaderType() {
        return "fabric"; // applies on all loaders via bytecode transformation
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        EnvironmentDetector.RenderingBackend backend = EnvironmentDetector.getRenderingBackend();
        LOGGER.info("Rendering backend: {} (arch: {}, os: {})",
            backend, EnvironmentDetector.getCpuArch(), EnvironmentDetector.getOsFamily());

        if (backend == EnvironmentDetector.RenderingBackend.VULKAN) {
            registerVulkanRedirects(transformer);
        } else if (backend == EnvironmentDetector.RenderingBackend.METAL) {
            registerMetalRedirects(transformer);
        }

        registerFutureProofRedirects(transformer);

        LOGGER.info("Rendering backend shim registered");
    }

    /** Redirects for the OpenGL to Vulkan transition. */
    private void registerVulkanRedirects(RetromodTransformer transformer) {
        LOGGER.info("Registering Vulkan rendering redirects");

        transformer.registerClassRedirect(
            "com/mojang/blaze3d/platform/GlStateManager",
            "com/mojang/blaze3d/platform/RenderStateManager"
        );

        // enableTexture/disableTexture are gone under Vulkan; textures stay bound
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "enableTexture", "()V",
            "com/retromod/shim/api/fabric/embedded/RenderingCompat", "noop", "()V"
        );
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "disableTexture", "()V",
            "com/retromod/shim/api/fabric/embedded/RenderingCompat", "noop", "()V"
        );

        // GLSL shaders translate to SPIR-V
        transformer.registerMethodRedirect(
            "net/minecraft/client/gl/ShaderProgram", "loadProgram",
            "(Ljava/lang/String;)V",
            "com/retromod/shim/api/fabric/embedded/RenderingCompat", "loadShaderCompat",
            "(Ljava/lang/String;)V"
        );

        transformer.registerClassRedirect(
            "com/mojang/blaze3d/vertex/DefaultVertexFormat",
            "com/mojang/blaze3d/vertex/VertexFormats"
        );

        // OpenGL framebuffers become Vulkan render passes
        transformer.registerMethodRedirect(
            "net/minecraft/client/gl/Framebuffer", "bindFramebuffer", "(Z)V",
            "com/retromod/shim/api/fabric/embedded/RenderingCompat", "bindRenderTarget",
            "(Ljava/lang/Object;Z)V"
        );
    }

    private void registerMetalRedirects(RetromodTransformer transformer) {
        LOGGER.info("Registering Metal rendering redirects (macOS)");

        // Metal's abstractions track Vulkan's, so reuse those redirects
        registerVulkanRedirects(transformer);

        // Metal supports different texture formats than OpenGL
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/GlStateManager", "texImage2D",
            "(IIIIIIIILjava/nio/IntBuffer;)V",
            "com/retromod/shim/api/fabric/embedded/RenderingCompat", "texImage2DCompat",
            "(IIIIIIIILjava/nio/IntBuffer;)V"
        );
    }

    /** Redirects that hold across every backend, for API changes unrelated to the GPU API. */
    private void registerFutureProofRedirects(RetromodTransformer transformer) {
        // Vulkan/Metal demand stricter threading; route assertThread through a thread-checked wrapper
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/systems/RenderSystem", "assertThread",
            "(Ljava/util/function/Supplier;)V",
            "com/retromod/shim/api/fabric/embedded/RenderingCompat", "assertRenderThread",
            "(Ljava/util/function/Supplier;)V"
        );

        // Track Mojang's gradual MatrixStack to PoseStack rename
        transformer.registerClassRedirect(
            "net/minecraft/client/util/math/MatrixStack",
            "net/minecraft/client/util/math/MatrixStack"
        );

        // Vulkan drops immediate-mode buffer building; future MC may replace Tessellator
        transformer.registerMethodRedirect(
            "net/minecraft/client/render/Tessellator", "getInstance",
            "()Lnet/minecraft/client/render/Tessellator;",
            "com/retromod/shim/api/fabric/embedded/RenderingCompat", "getTessellator",
            "()Ljava/lang/Object;"
        );
    }
}
