/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Polyfill for removed NeoForge APIs.
 *
 * <p>Covers the removed block-outline render event and the {@code javax.annotation} stubs that
 * NeoForge stopped shipping.
 *
 * <p>It used to claim the Transfer API rework removed {@code ItemStackHandler} and
 * {@code ComponentItemHandler} and pointed both at {@code IItemHandlerShim}. Neither class was
 * removed: both are still present on 26.1 with their old shape, and the shim was a static utility
 * with a private constructor, so a mod doing {@code new ItemStackHandler(9)} or extending it was
 * rewritten onto something it could not construct or subclass. Only the loader entry points
 * disabled this category, so the damage landed on the offline CLI and AOT paths.
 */
public class NeoForgeCorePolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "NeoForge Core Removed APIs";
    }

    @Override
    public String getCategory() {
        return "neoforge";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            "net/neoforged/neoforge/client/event/RenderHighlightEvent",
            "net/neoforged/neoforge/client/event/RenderHighlightEvent$Block",
            "javax/annotation/Nullable",
            "javax/annotation/Nonnull"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        return new String[]{
            "javax.annotation.Nullable",
            "javax.annotation.Nonnull"
        };
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // RenderHighlightEvent is genuinely gone, but replacing it needs the block-outline render
        // state, so it is reported as removed rather than redirected somewhere that cannot answer.
        for (String cls : getPolyfillClasses()) {
            transformer.registerEmbeddedShim(cls);
        }
    }
}
