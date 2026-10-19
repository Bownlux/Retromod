/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.synth;

import com.retromod.core.RedirectKey;
import com.retromod.core.RetromodTransformer;
import com.retromod.loaderapi.LoaderApiRegistration;
import com.retromod.loaderapi.LoaderApiRuntime;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A type that can cross a mod boundary, mapped to one compiled shared interface for every mod.
 *
 * @param oldName      the old interface, in 26.x Mojang names
 * @param sharedName   the compiled shared interface; must be a {@code .../shared/...} type
 * @param samName      the old method name, which mods implement and call
 * @param typedDesc    the old method descriptor, in 26.x Mojang names
 * @param erasedName   the shared method name, different for every shared type
 * @param erasedDesc   the shared method descriptor, with {@code Object} for each reference type
 * @param bridgeName   internal name of the per-mod bridge interface
 */
public record BoundaryTypeSpec(String oldName, String sharedName, String samName,
        String typedDesc, String erasedName, String erasedDesc, String bridgeName) {

    // Which shared type owns each erased method, so no two can collide in one class.
    private static final Map<String, String> ERASED_METHOD_OWNERS = new ConcurrentHashMap<>();

    public BoundaryTypeSpec {
        if (!LoaderApiRuntime.isSharedTypeName(sharedName)) {
            throw new IllegalArgumentException(sharedName + " must live in a .../shared/ package "
                    + "under com/retromod/loaderapi/ so it is compiled, never relocated");
        }
        if (LoaderApiRuntime.isSharedTypeName(bridgeName)) {
            throw new IllegalArgumentException(bridgeName + " is per mod and must not be shared");
        }
        if (Type.getArgumentTypes(typedDesc).length != Type.getArgumentTypes(erasedDesc).length) {
            throw new IllegalArgumentException(oldName + ": typed and erased descriptors differ in "
                    + "parameter count");
        }
        // The class bridge and the mod's own method must never be the same method.
        if (erasedName.equals(samName)) {
            throw new IllegalArgumentException(oldName + ": the erased method needs its own name, "
                    + "not " + samName);
        }
    }

    /** The per-mod bridge interface for classes and interfaces that implement the old type. */
    public byte[] generateBridge() {
        return new SamBridgeSpec(bridgeName, List.of(sharedName), samName, typedDesc, erasedName,
                erasedDesc, SamBridgeSpec.passThrough(typedDesc), SamBridgeSpec.Result.SAME)
                .generate();
    }

    /**
     * Registers the spec. Skipped when Retromod's Minecraft side already redirects the old name,
     * because that side owns Minecraft types.
     *
     * @return whether the spec was registered
     */
    public boolean register(LoaderApiRegistration registration) {
        RetromodTransformer transformer = registration.transformer();
        String existing = transformer.getClassRedirects().get(oldName);
        if (existing != null && !existing.equals(sharedName)) {
            return false;
        }
        String owner = ERASED_METHOD_OWNERS.putIfAbsent(erasedName + erasedDesc, sharedName);
        if (owner != null && !owner.equals(sharedName)) {
            throw new IllegalStateException(sharedName + " and " + owner + " both declare "
                    + erasedName + erasedDesc + ", so a class implementing both could not tell "
                    + "them apart. Give one of them another erased method name.");
        }

        // Types first: a type claim clears every member keyed on the type.
        registration.claim(RedirectKey.type(oldName));
        registration.claim(RedirectKey.type(sharedName));
        registration.registerPerModSynthetic(bridgeName, generateBridge());
        transformer.registerClassRedirect(oldName, sharedName);
        transformer.registerInterfaceReplacement(oldName, bridgeName);
        transformer.registerInterfaceReplacement(sharedName, bridgeName);
        // Lambdas are matched before or after the class remap, so both names are keyed.
        transformer.registerLambdaSamRetype(sharedName, samName, typedDesc, erasedName, erasedDesc);
        transformer.registerLambdaSamRetype(oldName, samName, typedDesc, erasedName, erasedDesc);
        transformer.registerMethodRedirect(sharedName, samName, typedDesc,
                sharedName, erasedName, erasedDesc);
        // Each class gets its own erased method, because two jars' bridges that both met in one
        // class would otherwise offer two defaults and the call would fail.
        transformer.registerImplementedMethodBridge(bridgeName, erasedName, erasedDesc, samName,
                typedDesc);
        return true;
    }
}
