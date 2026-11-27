/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import com.retromod.shim.common.embedded.LegacyLog;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.resources.Identifier;

/** The old renderer access and {@code MeshBuilder} calls, answered by 26.x's renderer and mutable meshes. */
public final class LegacyRenderers {

    private LegacyRenderers() {}

    /** {@code meshBuilder.getEmitter()}. */
    public static QuadEmitter getEmitter(MutableMesh mesh) {
        return mesh.emitter();
    }

    /** {@code meshBuilder.build()}: the finished mesh, after which the builder starts empty, as the old one did. */
    public static Mesh build(MutableMesh mesh) {
        Mesh built = mesh.immutableCopy();
        mesh.clear();
        return built;
    }

    /** The old access point; a renderer mod's own registration is left to the host, which has its own. */
    static final class Access implements LegacyRendererAccess {
        private static final LegacyRenderer RENDERER = new HostRenderer();

        @Override
        public void registerRenderer(LegacyRenderer plugin) {
            LegacyLog.warnOnce("legacy renderer registration", "An old mod tried to register its own Fabric renderer "
                    + (plugin == null ? "" : plugin.getClass().getName()) + ". Renderer mods built for older Minecraft"
                    + " cannot replace the 26.x renderer, so the host's renderer stays in use.");
        }

        @Override
        public LegacyRenderer getRenderer() {
            return RENDERER;
        }

        @Override
        public boolean hasRenderer() {
            try {
                return Renderer.get() != null;
            } catch (RuntimeException | LinkageError none) {
                return false;
            }
        }
    }

    /** The old renderer over the host's: its meshes are the host's mutable meshes. */
    static final class HostRenderer implements LegacyRenderer {
        @Override
        public MutableMesh meshBuilder() {
            return Renderer.get().mutableMesh();
        }

        @Override
        public LegacyMaterialFinder materialFinder() {
            return new LegacyRenderMaterials.Finder();
        }

        @Override
        public LegacyRenderMaterial materialById(Identifier id) {
            return LegacyRenderMaterials.byId(id);
        }

        @Override
        public boolean registerMaterial(Identifier id, LegacyRenderMaterial material) {
            return LegacyRenderMaterials.register(id, material);
        }
    }
}
