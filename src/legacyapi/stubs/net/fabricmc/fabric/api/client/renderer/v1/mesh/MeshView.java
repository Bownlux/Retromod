// Compile-only stub of a Fabric API host type on 26.x for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.renderer.v1.mesh;

import java.util.function.Consumer;

public interface MeshView {
    int size();
    void forEach(Consumer<? super QuadView> action);
    void outputTo(QuadEmitter emitter);
}
