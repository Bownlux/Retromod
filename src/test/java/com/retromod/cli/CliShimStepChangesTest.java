/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** diff and devhelp list each chain step's own redirects and count each change once. */
class CliShimStepChangesTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    @AfterEach
    void clearTransformer() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    void startupRedirectsStayOutOfTheFirstStep() {
        // Stands in for the Forge 1.12.2 lifecycle redirects main registers before any command.
        transformer.registerClassRedirect("old/StartupEvent", "new/StartupEvent");
        VersionShim first = shim("A", t -> t.registerClassRedirect("old/First", "new/First"));

        List<RetromodCli.StepChanges> steps = RetromodCli.registerByStep(transformer, List.of(first));

        assertEquals(Map.of("old/First", "new/First"), steps.get(0).classes(),
                "a redirect registered before the chain is not part of its first step");
    }

    @Test
    void eachStepListsOnlyItsOwnRedirects() {
        VersionShim a = shim("A", t -> t.registerClassRedirect("old/A", "new/A"));
        VersionShim b = shim("B", t -> t.registerClassRedirect("old/B", "new/B"));

        List<RetromodCli.StepChanges> steps = RetromodCli.registerByStep(transformer, List.of(a, b));

        assertEquals(Map.of("old/A", "new/A"), steps.get(0).classes());
        assertEquals(Map.of("old/B", "new/B"), steps.get(1).classes(),
                "step B must not repeat step A's rename");
        assertEquals(2, RetromodCli.ChainTotals.of(steps).classes());
    }

    @Test
    void eachStepListsOnlyItsOwnMethodRedirects() {
        VersionShim a = shim("A", t -> t.registerMethodRedirect(
                "old/Owner", "first", "()V", "new/Owner", "first", "()V"));
        VersionShim b = shim("B", t -> t.registerMethodRedirect(
                "old/Owner", "second", "()V", "new/Owner", "second", "()V"));

        List<RetromodCli.StepChanges> steps = RetromodCli.registerByStep(transformer, List.of(a, b));

        var secondKey = new RetromodTransformer.MethodKey("old/Owner", "second", "()V");
        assertEquals(List.of(secondKey), new ArrayList<>(steps.get(1).methods().keySet()),
                "step B must list only the method redirect it registered");
        assertEquals(2, RetromodCli.ChainTotals.of(steps).methods());
    }

    @Test
    void laterRetargetShowsUnderTheLaterStep() {
        // Mirrors WorldRenderContext, which a later step points at a new package.
        VersionShim a = shim("A", t -> t.registerClassRedirect("x/Ctx", "x/world/Ctx"));
        VersionShim b = shim("B", t -> t.registerClassRedirect("x/Ctx", "x/level/Ctx"));

        List<RetromodCli.StepChanges> steps = RetromodCli.registerByStep(transformer, List.of(a, b));

        assertEquals(Map.of("x/Ctx", "x/level/Ctx"), steps.get(1).classes(),
                "a key that a later step retargets belongs in that step's section");
        assertEquals(1, RetromodCli.ChainTotals.of(steps).classes(),
                "a retargeted key is still one rename in the summary");
    }

    @Test
    void summaryCountsEachChangeOnce() {
        List<VersionShim> chain = new ArrayList<>();
        for (int step = 0; step < 3; step++) {
            int base = step * 10;
            chain.add(shim("S" + step, t -> {
                for (int i = 0; i < 10; i++) {
                    t.registerClassRedirect("old/C" + (base + i), "new/C" + (base + i));
                }
            }));
        }

        List<RetromodCli.StepChanges> steps = RetromodCli.registerByStep(transformer, chain);

        for (RetromodCli.StepChanges step : steps) {
            assertEquals(10, step.classes().size(), step.shim().getShimName() + " added 10 renames");
        }
        assertEquals(30, RetromodCli.ChainTotals.of(steps).classes(),
                "three steps of 10 renames are 30, not a sum of running totals");
        assertFalse(steps.get(2).classes().containsKey("old/C0"));
    }

    /** A chain step that runs {@code registration} when registered. */
    private static VersionShim shim(String name, Consumer<RetromodTransformer> registration) {
        return new VersionShim() {
            @Override public String getShimName() { return name; }
            @Override public String getSourceVersion() { return "1.0"; }
            @Override public String getTargetVersion() { return "1.1"; }
            @Override public String getModLoaderType() { return "fabric"; }
            @Override public void registerRedirects(RetromodTransformer transformer) {
                registration.accept(transformer);
            }
        };
    }
}
