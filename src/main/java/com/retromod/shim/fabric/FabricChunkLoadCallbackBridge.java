/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.loaderapi.fabric.v26.synth.HostSamSpec;
import com.retromod.loaderapi.fabric.v26.synth.SamBridgeSpec;
import com.retromod.shim.fabric.embedded.LegacyChunkEvents;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

/** Fits an old two-argument chunk load listener onto the three-argument callback Fabric API has had since 26.1. */
public final class FabricChunkLoadCallbackBridge {

    static final String LOAD = "net/fabricmc/fabric/api/event/lifecycle/v1/ServerChunkEvents$Load";
    private static final String LEVEL_CHUNK = "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;";
    static final String OLD_DESC = "(" + LEVEL_CHUNK + ")V";
    static final String HOST_DESC = "(" + LEVEL_CHUNK + "Z)V";

    // An old listener never implemented the three-argument method, so without the bridge the first
    // chunk load stops the chunk system and the server waits on its spawn chunk forever. Every build
    // needs it, so it is a plain bridge rather than part of the loader API runtime.
    static final HostSamSpec SPEC = new HostSamSpec(List.of(LOAD), LOAD, "onChunkLoad", OLD_DESC, "onChunkLoad", HOST_DESC,
            List.of(new SamBridgeSpec.HostArg(0), new SamBridgeSpec.HostArg(1)),
            "com/retromod/generated/fabric/ServerChunkLoadBridge",
            new HostSamSpec.Target(LegacyChunkEvents.INTERNAL_NAME, "onChunkLoad",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"));

    private FabricChunkLoadCallbackBridge() {}

    /** Registers the bridge on a host whose Fabric API passes the third argument. */
    public static void register(RetromodTransformer transformer) {
        if (!hostTakesGeneratedFlag()) return;
        SyntheticEmbedder.registerClassResource(transformer, LegacyChunkEvents.INTERNAL_NAME, LegacyChunkEvents.class);
        SPEC.registerAlwaysOn(transformer);
    }

    static boolean hostTakesGeneratedFlag() {
        ClassNode load = ClassResourceInspector.read(LOAD);
        // Offline the host classes are not readable, so the host version decides: 26.1 added the flag.
        if (load == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
        return load.methods.stream().anyMatch(m -> m.name.equals("onChunkLoad") && m.desc.equals(HOST_DESC));
    }
}
