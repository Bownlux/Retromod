/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

/** Old {@code BufferUploader} calls: the mesh is closed and not drawn, since the host has no matching immediate draw. */
public final class LegacyBufferUploader {

    private LegacyBufferUploader() {}

    /** {@code BufferUploader.drawWithShader(mesh)}. */
    public static void drawWithShader(Object mesh) {
        draw(mesh);
    }

    /** {@code BufferUploader.draw(mesh)}: the mesh is skipped and closed. */
    public static void draw(Object mesh) {
        if (mesh == null) return;
        // A host render type would draw with the game's own projection and state, not the mod's, which puts
        // old GUI fills across the world, so skipping is the safe answer.
        LegacyLog.warnOnce("buffer-uploader-skip", "An old mod drew a mesh with BufferUploader, which this Minecraft "
                + "version removed. Retromod does not draw these meshes, so that part of the mod's drawing is missing.");
        close(mesh);
    }

    /** {@code BufferUploader.reset()} and {@code invalidate()}: there is no bound buffer to forget any more. */
    public static void reset() {
    }

    private static void close(Object mesh) {
        // An unclosed mesh keeps its native buffer allocated, and the old draw closed it too.
        if (mesh instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Closing twice or after the host closed it is harmless.
            }
        }
    }
}
