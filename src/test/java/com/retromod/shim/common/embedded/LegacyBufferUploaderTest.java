/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** An old BufferUploader draw is skipped on every host, and every mesh is closed. */
public class LegacyBufferUploaderTest {

    @Test
    @DisplayName("drawWithShader and draw close the mesh without drawing it")
    void meshesAreClosedNotDrawn() {
        Mesh shaded = new Mesh();
        Mesh plain = new Mesh();

        LegacyBufferUploader.drawWithShader(shaded);
        LegacyBufferUploader.draw(plain);

        assertTrue(shaded.closed, "a skipped mesh must still be closed so its buffer is freed");
        assertTrue(plain.closed);
        assertFalse(shaded.read || plain.read, "the helper must not read the mesh, since it never draws it");
    }

    @Test
    @DisplayName("a null mesh, a mesh whose close fails, and reset are all harmless")
    void edgeCasesAreContained() {
        assertDoesNotThrow(() -> LegacyBufferUploader.draw(null));
        assertDoesNotThrow(() -> LegacyBufferUploader.draw(new FailingMesh()), "a failing close must not reach the mod");
        assertDoesNotThrow(LegacyBufferUploader::reset);
    }

    @Test
    @DisplayName("the helper keeps no render type matching or other drawing code")
    void helperHasNoDrawPath() {
        assertEquals(0, LegacyBufferUploader.class.getDeclaredFields().length, "the helper holds no state");
        assertTrue(Arrays.stream(LegacyBufferUploader.class.getDeclaredMethods())
                        .noneMatch(method -> method.getName().contains("RenderType")),
                "skipping on every host leaves nothing to match");
    }

    /** The host's MeshData. */
    public static final class Mesh implements AutoCloseable {
        public boolean closed;
        public boolean read;

        public Object drawState() {
            read = true;
            return null;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A mesh whose buffer was already freed by someone else. */
    public static final class FailingMesh implements AutoCloseable {
        @Override
        public void close() {
            throw new IllegalStateException("already closed");
        }
    }
}
