/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Moves old structure code from the {@code StructurePieceAccessor} interface 26.3 deleted onto {@code StructurePiecesBuilder}, its only host implementation. */
public final class LegacyStructurePieceAccessorBridge {

    static final String ACCESSOR = "net/minecraft/world/level/levelgen/structure/StructurePieceAccessor";
    static final String BUILDER = "net/minecraft/world/level/levelgen/structure/pieces/StructurePiecesBuilder";
    /** The release that deleted the interface. */
    static final String VERSION = "26.3";

    private LegacyStructurePieceAccessorBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostDeletedIt(host)) return;
        // The builder kept addPiece and findCollisionPiece with the same descriptors, and an old addChildren
        // override, renamed with its parameter, matches the host's new addChildren.
        t.registerClassRedirect(ACCESSOR, BUILDER);
        t.limitClassRedirectToModsBefore(ACCESSOR, VERSION);
        // The calls were interface calls; the builder is a class.
        t.registerClassOwner(BUILDER);
    }

    /** Whether the host's builder no longer implements the accessor; offline or unreadable, a target from 26.3. */
    static boolean hostDeletedIt(Function<String, ClassNode> host) {
        ClassNode builder = host.apply(BUILDER);
        if (builder == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, VERSION) >= 0;
        return !builder.interfaces.contains(ACCESSOR);
    }
}
