/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/**
 * Sodium 0.4-0.6 renamed packages and reworked the renderer; Iris 1.6-1.7 changed the shader pipeline.
 */
public class SodiumIrisApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "Sodium/Iris API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "0.4.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "0.6.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // me.jellysquid.mods.sodium -> net.caffeinemc.mods.sodium (0.5+)
        transformer.registerClassRedirect(
            "me/jellysquid/mods/sodium/client/render/chunk/compile/buffers/ChunkModelBuilder",
            "net/caffeinemc/mods/sodium/client/render/chunk/compile/buffers/ChunkModelBuilder"
        );
        
        transformer.registerClassRedirect(
            "me/jellysquid/mods/sodium/client/model/vertex/type/ChunkVertexType",
            "net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType"
        );
        
        transformer.registerClassRedirect(
            "me/jellysquid/mods/sodium/client/render/chunk/data/ChunkRenderData",
            "net/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo"
        );

        transformer.registerClassRedirect(
            "me/jellysquid/mods/sodium/client/render/pipeline/BlockRenderer",
            "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer"
        );

        transformer.registerClassRedirect(
            "me/jellysquid/mods/sodium/client/render/pipeline/context/BlockRenderContext",
            "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderContext"
        );

        transformer.registerClassRedirect(
            "me/jellysquid/mods/sodium/client/gui/SodiumGameOptions",
            "net/caffeinemc/mods/sodium/client/gui/SodiumGameOptions"
        );
        
        transformer.registerMethodRedirect(
            "me/jellysquid/mods/sodium/client/SodiumClientMod",
            "options",
            "()Lme/jellysquid/mods/sodium/client/gui/SodiumGameOptions;",
            "net/caffeinemc/mods/sodium/client/SodiumClientMod",
            "options",
            "()Lnet/caffeinemc/mods/sodium/client/gui/SodiumGameOptions;"
        );

        // No FRAPI Renderer/MeshBuilder/QuadEmitter redirects here: FabricRendererApiShim relocates
        // them to client/renderer/v1/*, and identity redirects here would clobber those and break Indium.

        transformer.registerClassRedirect(
            "net/coderbot/iris/api/v0/IrisApi",
            "net/irisshaders/iris/api/v0/IrisApi"
        );
        
        transformer.registerMethodRedirect(
            "net/coderbot/iris/api/v0/IrisApi",
            "getInstance",
            "()Lnet/coderbot/iris/api/v0/IrisApi;",
            "net/irisshaders/iris/api/v0/IrisApi",
            "getInstance",
            "()Lnet/irisshaders/iris/api/v0/IrisApi;"
        );
        
        transformer.registerMethodRedirect(
            "net/coderbot/iris/api/v0/IrisApi",
            "isShaderPackInUse",
            "()Z",
            "net/irisshaders/iris/api/v0/IrisApi",
            "isShaderPackInUse",
            "()Z"
        );

        transformer.registerClassRedirect(
            "me/jellysquid/mods/sodium/client/model/vertex/formats/ModelVertexSink",
            "net/caffeinemc/mods/sodium/client/render/vertex/VertexConsumer"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.fabric.embedded.SodiumShim"
        };
    }
}
