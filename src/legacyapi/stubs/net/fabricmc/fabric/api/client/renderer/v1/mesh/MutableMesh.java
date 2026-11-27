// Compile-only stub of a Fabric API host type on 26.x for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.renderer.v1.mesh;

public interface MutableMesh extends MeshView {
    QuadEmitter emitter();
    Mesh immutableCopy();
    void clear();
}
