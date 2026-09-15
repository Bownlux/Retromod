/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The offline paths build a {@link PolyfillRegistry} with no category gating.
 *
 * <p>{@code RetromodForge} and {@code RetromodNeoForge} both turn the {@code neoforge} category off
 * because the host-gated shims own it, and {@code RetromodNeoForgePolyfillTest} covers that. The
 * CLI and the AOT compiler do not: they construct a plain registry, where every category including
 * {@code neoforge} is on by default. So a polyfill that is wrong only shows up when a mod is
 * transformed ahead of time, which is the harder failure to trace back.
 *
 * <p>The concrete case: the NeoForge core polyfill claimed the Transfer API rework removed
 * {@code ItemStackHandler} and {@code ComponentItemHandler}, and redirected both to a static
 * utility class with a private constructor. Neither class was removed. A mod calling
 * {@code new ItemStackHandler(9)} or extending it came out of an offline transform pointing at
 * something it can neither construct nor subclass.
 */
class OfflinePolyfillItemHandlerTest {

    private static final List<String> STILL_PRESENT = List.of(
            "net/neoforged/neoforge/items/IItemHandler",
            "net/neoforged/neoforge/items/IItemHandlerModifiable",
            "net/neoforged/neoforge/items/ItemStackHandler",
            "net/neoforged/neoforge/items/ComponentItemHandler");

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an offline transform leaves the item handler classes alone")
    void itemHandlersSurviveTheUngatedRegistry() {
        // Exactly what RetromodCli and AotCompiler do: no setCategoryEnabled call at all.
        new PolyfillRegistry().loadAndRegister(transformer);

        Map<String, String> redirects = transformer.getClassRedirects();
        for (String owner : STILL_PRESENT) {
            assertFalse(redirects.containsKey(owner),
                    owner + " is still present on 26.1 with its old shape, so redirecting it can "
                    + "only break a mod that was working. It pointed at " + redirects.get(owner));
        }
    }
}
