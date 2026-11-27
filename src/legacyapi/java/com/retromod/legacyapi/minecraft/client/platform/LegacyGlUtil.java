/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.platform;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** The old {@code GlUtil} helpers, answered by native memory calls and the GPU device's own description on 26.2 and newer. */
public final class LegacyGlUtil {

    private LegacyGlUtil() {}

    /** Native memory of the given size, which the caller frees with {@link #freeMemory}. */
    public static ByteBuffer allocateMemory(int size) {
        return MemoryUtil.memAlloc(size);
    }

    public static void freeMemory(Buffer buffer) {
        MemoryUtil.memFree(buffer);
    }

    public static String getVendor() {
        DeviceInfo info = info();
        return info == null ? "" : info.vendorName();
    }

    public static String getRenderer() {
        DeviceInfo info = info();
        return info == null ? "" : info.name();
    }

    /** The driver description, which the old call read from OpenGL's version string. */
    public static String getOpenGLVersion() {
        DeviceInfo info = info();
        return info == null ? "" : info.driverInfo();
    }

    /** The processor count and architecture; the processor's model name is no longer read. */
    public static String getCpuInfo() {
        return Runtime.getRuntime().availableProcessors() + "x " + System.getProperty("os.arch", "unknown");
    }

    // Before the device exists, as during early loading, there is nothing to describe.
    private static DeviceInfo info() {
        try {
            return RenderSystem.getDevice().getDeviceInfo();
        } catch (RuntimeException | LinkageError notReady) {
            return null;
        }
    }
}
