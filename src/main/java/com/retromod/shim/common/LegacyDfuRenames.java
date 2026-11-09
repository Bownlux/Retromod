/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.function.Function;

/** DataFixerUpper members that later versions moved to a new name, sent there on every loader once the host's DFU drops the old one. */
public class LegacyDfuRenames implements MinecraftVersionedApiShim {

    static final String CODEC = "com/mojang/serialization/Codec";
    static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    static final String DATA_RESULT = "com/mojang/serialization/DataResult";
    static final String PARTIAL_RESULT = "com/mojang/serialization/DataResult$PartialResult";
    static final String ERROR = "com/mojang/serialization/DataResult$Error";

    /** The first version checked to lack {@code Codec.unit} (DFU 9.0.19); 1.21.1 mods still call it. */
    static final String FIRST_CHECKED_VERSION = "1.21.11";

    /** The first version checked to have {@code DataResult.Error}, which 1.21.1 mods name, in place of PartialResult. */
    static final String FIRST_CHECKED_ERROR_VERSION = "1.21.1";

    // DFU 6's getOrThrow(boolean, Consumer). 1.20.1 ships DFU 6.0.8, which has it; 1.20.5 rebuilt
    // DataResult as an interface without it, and 1.21.11 (9.0.19) and 26.2 (10.0.21) lack it too.
    static final String OLD_GET_OR_THROW = "(ZLjava/util/function/Consumer;)Ljava/lang/Object;";
    static final String GET_OR_THROW_HELPER = "com/retromod/polyfill/minecraft/DataResultPolyfill";
    static final String GET_OR_THROW_GONE_VERSION = "1.20.5";

    /** A static method that moved to {@code newOwner} under {@code newName}, with the same descriptor and meaning. */
    record MovedStatic(String owner, String name, String desc, String newOwner, String newName) {}

    // Codec.unit built a unit MapCodec and returned its codec; MapCodec.unitCodec is the same codec.
    static final List<MovedStatic> MOVED_STATICS = List.of(
            new MovedStatic(CODEC, "unit", "(Ljava/lang/Object;)L" + CODEC + ";", MAP_CODEC, "unitCodec"),
            new MovedStatic(CODEC, "unit", "(Ljava/util/function/Supplier;)L" + CODEC + ";", MAP_CODEC, "unitCodec"));

    @Override
    public String getShimName() {
        return "Legacy DataFixerUpper Renames";
    }

    @Override
    public String getSourceVersion() {
        return "1.20.4";
    }

    /** DataResult was rebuilt from 1.20.5 on, so older hosts have nothing to probe. */
    @Override
    public String getTargetVersion() {
        return "1.20.5";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        register(transformer, ClassResourceInspector::read, RetromodVersion.TARGET_MC_VERSION);
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }

    /** Registers each move the host proves, or offline, each move for a target from the version checked to have it. */
    static void register(RetromodTransformer transformer, Function<String, ClassNode> host, String target) {
        boolean offlineMoved = RetromodVersion.compareMcVersions(target, FIRST_CHECKED_VERSION) >= 0;
        boolean offlineError = RetromodVersion.compareMcVersions(target, FIRST_CHECKED_ERROR_VERSION) >= 0;
        boolean offlineGetOrThrowGone = RetromodVersion.compareMcVersions(target, GET_OR_THROW_GONE_VERSION) >= 0;
        for (MovedStatic move : MOVED_STATICS) {
            if (!moved(host, move, offlineMoved)) continue;
            // MapCodec is a class, so the call must not keep Codec's interface method reference.
            transformer.registerClassOwner(move.newOwner());
            transformer.registerMethodRedirect(move.owner(), move.name(), move.desc(),
                    move.newOwner(), move.newName(), move.desc());
        }
        // DataResult.error() now holds an Error record, whose message() a PartialResult reader still calls.
        if (renamedToError(host, offlineError)) {
            transformer.registerClassRedirect(PARTIAL_RESULT, ERROR);
        }
        // The old getOrThrow goes to a helper that answers it from the new members, as DFU 6 did.
        if (lacksOldGetOrThrow(host, offlineGetOrThrowGone)) {
            transformer.registerMethodRedirect(DATA_RESULT, "getOrThrow", OLD_GET_OR_THROW, GET_OR_THROW_HELPER,
                    "getOrThrow", "(Ljava/lang/Object;ZLjava/util/function/Consumer;)Ljava/lang/Object;", true);
        }
    }

    /** Whether the host's DataResult lacks DFU 6's getOrThrow(boolean, Consumer); when DFU cannot be read, the target version answers. */
    static boolean lacksOldGetOrThrow(Function<String, ClassNode> host, boolean offlineGone) {
        ClassNode dataResult = host.apply(DATA_RESULT);
        if (dataResult == null) return offlineGone;
        return !declares(dataResult, "getOrThrow", OLD_GET_OR_THROW);
    }

    /** Whether the host's DFU replaced PartialResult with Error; when DFU cannot be read, the target version answers. */
    static boolean renamedToError(Function<String, ClassNode> host, boolean offlineError) {
        if (host.apply(DATA_RESULT) == null) return offlineError;
        ClassNode error = host.apply(ERROR);
        return host.apply(PARTIAL_RESULT) == null && error != null && declares(error, "message", "()Ljava/lang/String;");
    }

    /** Whether the host's DFU lacks the old method and has the new one; when DFU cannot be read, the target version answers. */
    static boolean moved(Function<String, ClassNode> host, MovedStatic move, boolean offlineMoved) {
        ClassNode owner = host.apply(move.owner());
        ClassNode newOwner = host.apply(move.newOwner());
        if (owner == null || newOwner == null) return offlineMoved;
        return !declares(owner, move.name(), move.desc()) && declares(newOwner, move.newName(), move.desc());
    }

    private static boolean declares(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }
}
