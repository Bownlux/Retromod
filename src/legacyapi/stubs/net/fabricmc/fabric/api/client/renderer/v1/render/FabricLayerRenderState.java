// Compile-only stub of a Fabric API host type on 26.x for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.renderer.v1.render;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;

public interface FabricLayerRenderState {
    default QuadEmitter emitter() { throw new UnsupportedOperationException(); }
}
