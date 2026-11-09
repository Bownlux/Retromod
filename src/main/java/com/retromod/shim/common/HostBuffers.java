/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** One answer, for every bridge, to whether old renderers draw into the host's MultiBufferSource or Retromod's stand-in. */
public final class HostBuffers {

    public static final String HOST_BUFFERS = "net/minecraft/client/renderer/MultiBufferSource";
    // Every 26.x client has the item model API, so reading it tells a client host from a server or an offline run.
    static final String CLIENT_ANCHOR = "net/minecraft/client/renderer/item/ItemModel$Unbaked";
    public static final String FIRST_HOST_WITHOUT_BUFFERS = "26.2";

    private static final Remapper TO_HOST = new Remapper() {
        @Override
        public String map(String internalName) {
            return internalName.equals(RenderBufferSynthetics.MBS) ? HOST_BUFFERS : internalName;
        }
    };

    private HostBuffers() {}

    /** Whether the host still has MultiBufferSource; a server or offline run answers by the target, which lost it in 26.2. */
    public static boolean keeps(Function<String, ClassNode> host) {
        if (host.apply(CLIENT_ANCHOR) != null) return host.apply(HOST_BUFFERS) != null;
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST_WITHOUT_BUFFERS) < 0;
    }

    /** The rewrite rebuilt classes take on such a host, their stand-in becoming the host's own interface; null otherwise. */
    public static Remapper names(Function<String, ClassNode> host) {
        return keeps(host) ? TO_HOST : null;
    }
}
