/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;
import com.retromod.shim.api.common.LibraryMoves;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Moves Sodium and Iris class names where the Sodium and Iris the mods run with have renamed them. */
public class SodiumIrisApiShim implements AuxiliaryVersionShim {

    // me.jellysquid.mods.sodium -> net.caffeinemc.mods.sodium. ModelVertexSink has no row: neither Sodium 0.6.0 nor
    // 0.8.13 ships the render/vertex/VertexConsumer it used to be moved to.
    static final Map<String, String> SODIUM_MOVES = orderedMoves(
            "me/jellysquid/mods/sodium/client/SodiumClientMod", "net/caffeinemc/mods/sodium/client/SodiumClientMod",
            "me/jellysquid/mods/sodium/client/render/chunk/compile/buffers/ChunkModelBuilder",
            "net/caffeinemc/mods/sodium/client/render/chunk/compile/buffers/ChunkModelBuilder",
            "me/jellysquid/mods/sodium/client/model/vertex/type/ChunkVertexType",
            "net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType",
            "me/jellysquid/mods/sodium/client/render/chunk/data/ChunkRenderData",
            "net/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo",
            "me/jellysquid/mods/sodium/client/render/pipeline/BlockRenderer",
            "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer",
            "me/jellysquid/mods/sodium/client/render/pipeline/context/BlockRenderContext",
            "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderContext");

    // Modrinth, 2026-11-07: Sodium 0.5.11 for 1.21 still ships me.jellysquid, 0.6.0-beta.3 for 1.21.2 and 0.8.13
    // ship net.caffeinemc, and every Sodium for 1.21.2 and newer is 0.6 or later.
    static final LibraryMoves.Table SODIUM_06 = new LibraryMoves.Table("1.21.2", "1.21.2");

    static final Map<String, String> IRIS_MOVES = orderedMoves(
            "net/coderbot/iris/api/v0/IrisApi", "net/irisshaders/iris/api/v0/IrisApi");

    // Iris 1.6.11 for 1.20.1 and 1.7.0 for 1.20.5 ship net.irisshaders, and every Iris for 1.20.5 and newer is 1.7+.
    static final LibraryMoves.Table IRIS_17 = new LibraryMoves.Table("1.20.5", "1.20.5");
    
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
        // Only where the Sodium and Iris the mods run with have dropped the old names. The class moves carry
        // SodiumClientMod.options() and IrisApi.getInstance() and isShaderPackInUse() with them.
        LibraryMoves.register(transformer, SODIUM_MOVES, SODIUM_06);
        LibraryMoves.register(transformer, IRIS_MOVES, IRIS_17);

        // No FRAPI Renderer/MeshBuilder/QuadEmitter redirects here: FabricRendererApiShim relocates
        // them to client/renderer/v1/*, and identity redirects here would clobber those and break Indium.
    }

    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.fabric.embedded.SodiumShim"
        };
    }

    private static Map<String, String> orderedMoves(String... pairs) {
        Map<String, String> moves = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) moves.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(moves);
    }
}
