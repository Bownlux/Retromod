/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import com.retromod.core.RedirectKey;
import com.retromod.core.RetromodTransformer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Registers a provider's rows, claiming each key first so a later shim cannot replace it. */
public final class LoaderApiRegistration {

    private final RetromodTransformer transformer;
    private final LoaderApiProvider provider;
    private final Set<RedirectKey> declared = new LinkedHashSet<>();
    private final Set<RedirectKey> suppressed = new LinkedHashSet<>();

    LoaderApiRegistration(RetromodTransformer transformer, LoaderApiProvider provider) {
        this.transformer = transformer;
        this.provider = provider;
    }

    /** The provider registering. */
    public LoaderApiProvider provider() {
        return provider;
    }

    /** Takes ownership of {@code key}, clearing what other providers registered for it. */
    public void claim(RedirectKey key) {
        transformer.claim(key);
        declared.add(key);
    }

    /** Takes ownership of {@code key} and keeps the host's own member, with no redirect. */
    public void suppress(RedirectKey key) {
        transformer.suppress(key);
        declared.add(key);
        suppressed.add(key);
    }

    /** Claims and redirects one method or constructor call. */
    public void redirectMethod(String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc, boolean devirtualize) {
        claim(RedirectKey.method(oldOwner, oldName, oldDesc));
        transformer.registerMethodRedirect(oldOwner, oldName, oldDesc, newOwner, newName, newDesc,
                devirtualize);
    }

    /**
     * Claims and redirects one method for its declaring owner and for each subtype owner a call
     * site may name instead, such as {@code FluidVariant.getNbt} for a member declared on
     * {@code TransferVariant}. Subtype owners from other jars are matched by hierarchy.
     */
    public void redirectInherited(String declaringOwner, String oldName, String oldDesc,
            Iterable<String> callSiteOwners, String newOwner, String newName, String newDesc) {
        claim(RedirectKey.method(declaringOwner, oldName, oldDesc));
        transformer.registerInheritedMethodRedirect(declaringOwner, oldName, oldDesc,
                newOwner, newName, newDesc);
        for (String owner : callSiteOwners) {
            if (owner.equals(declaringOwner)) continue;
            claim(RedirectKey.method(owner, oldName, oldDesc));
            transformer.registerMethodRedirect(owner, oldName, oldDesc, newOwner, newName, newDesc);
        }
    }

    /** Claims and redirects one class name. */
    public void redirectClass(String oldClass, String newClass) {
        claim(RedirectKey.type(oldClass));
        transformer.registerClassRedirect(oldClass, newClass);
    }

    /**
     * Claims and redirects one field read or write, and a static access that names a mod subtype
     * of {@code oldOwner}, as an interface constant read inside an implementing class does.
     */
    public void redirectField(String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc) {
        claim(RedirectKey.field(oldOwner, oldName));
        transformer.registerInheritedStaticFieldRedirect(oldOwner, oldName, oldDesc, newOwner, newName,
                newDesc);
    }

    /** Claims and retypes lambdas whose interface method descriptor changed. */
    public void retypeLambda(String iface, String samName, String oldSamDesc, String newSamDesc) {
        claim(RedirectKey.method(iface, samName, oldSamDesc));
        transformer.registerLambdaSamRetype(iface, samName, oldSamDesc, newSamDesc);
    }

    /** Claims {@code iface} in implements lists and replaces it with {@code replacement}. */
    public void replaceImplementedInterface(String iface, String replacement) {
        claim(RedirectKey.type(iface));
        transformer.registerInterfaceReplacement(iface, replacement);
    }

    /**
     * Registers a per-mod synthetic. {@code SyntheticEmbedder} relocates it into each jar that
     * references it, so it must never be a type another mod can name in a signature.
     */
    public void registerPerModSynthetic(String internalName, byte[] classBytes) {
        if (LoaderApiRuntime.isSharedTypeName(internalName)) {
            throw new IllegalArgumentException(internalName + " is a shared type. Shared types are "
                    + "compiled into Retromod so every mod sees one class.");
        }
        claim(RedirectKey.type(internalName));
        transformer.registerSyntheticClass(internalName, classBytes);
    }

    /** The transformer, for registrations that have no helper here. Claim their keys first. */
    public RetromodTransformer transformer() {
        return transformer;
    }

    /** Every key this provider claimed or suppressed, in registration order. */
    public Set<RedirectKey> declaredKeys() {
        return Collections.unmodifiableSet(declared);
    }

    /** Every key this provider suppressed. */
    public Set<RedirectKey> suppressedKeys() {
        return Collections.unmodifiableSet(suppressed);
    }
}
