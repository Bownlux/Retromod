// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged.
package com.mojang.blaze3d.framegraph;

import com.mojang.blaze3d.resource.ResourceHandle;

public interface FramePass {
    <T> ResourceHandle<T> readsAndWrites(ResourceHandle<T> handle);

    void disableCulling();

    void executes(Runnable task);
}
