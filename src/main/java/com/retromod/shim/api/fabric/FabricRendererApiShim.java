/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/**
 * Redirects {@code fabric-renderer-api-v1} types that Fabric API 0.110 moved from
 * {@code net/fabricmc/fabric/api/renderer/v1/*} into the {@code client/} subpackage.
 *
 * <p>The {@code material/} subtree ({@code RenderMaterial}, {@code BlendMode},
 * {@code MaterialFinder}) was removed rather than moved, so it needs API bridging
 * and is left for follow-up.
 */
public class FabricRendererApiShim implements AuxiliaryVersionShim {

    private static final String OLD = "net/fabricmc/fabric/api/renderer/v1/";
    private static final String NEW = "net/fabricmc/fabric/api/client/renderer/v1/";

    private static final String[] MOVED_CLASSES = {
        "mesh/Mesh", "mesh/MeshView", "mesh/MutableMesh",
        "mesh/MutableQuadView", "mesh/QuadAtlas", "mesh/QuadEmitter",
        "mesh/QuadTransform", "mesh/QuadView", "mesh/ShadeMode",
        "model/ModelHelper",
        "Renderer",
    };

    @Override
    public String getShimName() {
        return "Fabric Renderer API Relocation";
    }

    @Override
    public String getSourceVersion() {
        return "0.50.0";
    }

    @Override
    public String getTargetVersion() {
        return "0.110.0";
    }

    @Override
    public String getModLoaderType() {
        return "fabric";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        for (String cls : MOVED_CLASSES) {
            transformer.registerClassRedirect(OLD + cls, NEW + cls);
        }
    }
}
