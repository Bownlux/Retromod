/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.platform;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** The old {@code GlUtil} helpers on 26.1, whose GPU device still answers its vendor, renderer and version itself. */
public final class LegacyGlUtil261 {

    private LegacyGlUtil261() {}

    public static ByteBuffer allocateMemory(int size) {
        return MemoryUtil.memAlloc(size);
    }

    public static void freeMemory(Buffer buffer) {
        MemoryUtil.memFree(buffer);
    }

    public static String getVendor() {
        GpuDevice device = device();
        return device == null ? "" : device.getVendor();
    }

    public static String getRenderer() {
        GpuDevice device = device();
        return device == null ? "" : device.getRenderer();
    }

    public static String getOpenGLVersion() {
        GpuDevice device = device();
        return device == null ? "" : device.getVersion();
    }

    /** The processor count and architecture; the processor's model name is no longer read. */
    public static String getCpuInfo() {
        return Runtime.getRuntime().availableProcessors() + "x " + System.getProperty("os.arch", "unknown");
    }

    private static GpuDevice device() {
        try {
            return RenderSystem.getDevice();
        } catch (RuntimeException | LinkageError notReady) {
            return null;
        }
    }
}
