// Compile-only stub of a Fabric API host type on 26.x for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.renderer.v1;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;

public interface Renderer {
    static Renderer get() { throw new UnsupportedOperationException(); }
    MutableMesh mutableMesh();
}
