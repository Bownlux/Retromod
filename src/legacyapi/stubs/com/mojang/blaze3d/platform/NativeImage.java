// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package com.mojang.blaze3d.platform;

public final class NativeImage implements AutoCloseable {
    public NativeImage(int width, int height, boolean calloc) { throw new UnsupportedOperationException(); }
    public static NativeImage read(java.io.InputStream in) throws java.io.IOException { throw new UnsupportedOperationException(); }
    public int getWidth() { throw new UnsupportedOperationException(); }
    public int getHeight() { throw new UnsupportedOperationException(); }
    public int getPixel(int x, int y) { throw new UnsupportedOperationException(); }
    public void setPixel(int x, int y, int color) { throw new UnsupportedOperationException(); }
    @Override public void close() { throw new UnsupportedOperationException(); }
}
