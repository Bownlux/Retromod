/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.device;

/** Stands in for 26.2 and newer {@code RenderSystem}: a device whose limits sit in its info record. */
public final class FakeRenderSystem {
    private static final FakeDevice DEVICE = new FakeDevice();

    private FakeRenderSystem() {}

    public static FakeDevice getDevice() {
        return DEVICE;
    }

    /** The 26.2 and newer device: no getMaxTextureSize, only getDeviceInfo. */
    public static final class FakeDevice {
        public FakeDeviceInfo getDeviceInfo() {
            return new FakeDeviceInfo();
        }
    }

    /** The device's info record. */
    public static final class FakeDeviceInfo {
        public FakeLimits limits() {
            return new FakeLimits();
        }
    }

    /** The device limits record. */
    public static final class FakeLimits {
        public int maxTextureSize() {
            return 16384;
        }
    }
}
