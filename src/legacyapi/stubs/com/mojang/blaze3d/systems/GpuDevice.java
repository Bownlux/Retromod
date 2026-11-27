// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged.
package com.mojang.blaze3d.systems;

// A class on 26.1 and 26.2; the 26.3 class move makes the calls interface calls.
public class GpuDevice {
    // 26.2 and newer.
    public DeviceInfo getDeviceInfo() { throw new UnsupportedOperationException(); }
    // 26.1 only.
    public String getVendor() { throw new UnsupportedOperationException(); }
    public String getRenderer() { throw new UnsupportedOperationException(); }
    public String getVersion() { throw new UnsupportedOperationException(); }
}
