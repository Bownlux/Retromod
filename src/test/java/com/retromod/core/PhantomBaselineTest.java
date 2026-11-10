/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression fence for phantom redirect targets (task #113).
 *
 * <p>A phantom is a redirect whose target is a {@code com/retromod/**} class that is never written
 * to any transformed jar and is not a registered synthetic. At runtime such a redirect is dropped
 * fail-safe by {@link RetromodTransformer#dropPhantomComRetromodTargets()} (#119), so it does not
 * crash, but it is dead weight and a latent trap. This test registers the full superset of shims
 * (every {@link VersionShim} on the ServiceLoader, all loaders, plus the Forge->NeoForge synthetics)
 * and asserts the resulting phantom set matches {@code phantom-baseline.txt} EXACTLY, so:
 * <ul>
 *   <li>a NEW phantom (someone registered a redirect to a class that will never resolve) fails the
 *       build with the offending name;</li>
 *   <li>a FIXED phantom fails too, prompting whoever fixed it to shrink the baseline file so the
 *       list keeps trending to zero.</li>
 * </ul>
 */
class PhantomBaselineTest {

    private static final String BASELINE_RESOURCE = "/phantom-baseline.txt";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    private static TreeSet<String> loadBaseline() throws Exception {
        TreeSet<String> baseline = new TreeSet<>();
        try (InputStream in = PhantomBaselineTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            assertNotNull(in, "phantom-baseline.txt is missing from the test classpath");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                baseline.add(s);
            }
        }
        return baseline;
    }

    /** Register the full unconditional superset of redirects, exactly like the offline scan. */
    private static TreeSet<String> collectPhantoms() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        for (VersionShim s : ServiceLoader.load(VersionShim.class)) {
            try {
                s.registerRedirects(t);
            } catch (Throwable ignore) {
                // a shim that fails to register cannot contribute phantoms; skip it
            }
        }
        try {
            com.retromod.shim.forge.ForgeNeoForgeSynthetics.registerAll(t);
        } catch (Throwable ignore) {
            // synthetics are best-effort here; their absence only shrinks the set
        }
        return new TreeSet<>(t.collectPhantomComRetromodTargets());
    }

    @Test
    @DisplayName("phantom redirect targets match the checked-in baseline exactly")
    void phantomSetMatchesBaseline() throws Exception {
        TreeSet<String> baseline = loadBaseline();
        TreeSet<String> actual = collectPhantoms();

        TreeSet<String> added = new TreeSet<>(actual);
        added.removeAll(baseline);
        TreeSet<String> removed = new TreeSet<>(baseline);
        removed.removeAll(actual);

        if (added.isEmpty() && removed.isEmpty()) {
            return; // green
        }

        StringBuilder msg = new StringBuilder("Phantom redirect targets drifted from the baseline.\n");
        if (!added.isEmpty()) {
            msg.append("\nNEW phantoms (a redirect points at a com/retromod class that will never\n")
               .append("resolve at runtime). Either write/register the embedded class, or remove the\n")
               .append("redirect. Do NOT add these to phantom-baseline.txt to silence the build:\n");
            for (String s : added) msg.append("  + ").append(s).append('\n');
        }
        if (!removed.isEmpty()) {
            msg.append("\nFIXED phantoms (no longer registered as dead targets). Delete their lines\n")
               .append("from src/test/resources/phantom-baseline.txt so the baseline keeps shrinking:\n");
            for (String s : removed) msg.append("  - ").append(s).append('\n');
        }
        msg.append("\nbaseline=").append(baseline.size())
           .append(" actual=").append(actual.size());
        fail(msg.toString());
    }

    @Test
    @DisplayName("Forge and NeoForge 26.3 launches register no redirect to a Retromod class that was never written")
    void forgeFamilyHostsLeaveNoPhantoms() {
        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        boolean savedNeoForge = McReflect.isForceNeoForge();
        try {
            assertNoForgeFamilyPhantoms("NeoForge 26.3", true, Set.of("neoforge", "forge", "common"));
            assertNoForgeFamilyPhantoms("Forge 26.3", false, Set.of("forge", "common"));
        } finally {
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
            McReflect.setForceNeoForge(savedNeoForge);
        }
    }

    /** Registers what the entry point registers on a 26.3 host and checks for dead Forge-family targets. */
    private static void assertNoForgeFamilyPhantoms(String host, boolean neoForge, Set<String> loaders) {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        McReflect.setForceNeoForge(neoForge);
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        // The shims RetromodNeoForge.loadNeoForgeShims or RetromodForge.loadForgeShims takes.
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (loaders.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, "26.3")) {
                ShimRegistration.register(shim, t);
            }
        }
        IntermediaryToMojangMapper.applyClassMovesOnly(t);

        List<String> dead = t.collectPhantomComRetromodTargets().stream()
                .filter(name -> name.startsWith("com/retromod/shim/forge/")
                        || name.startsWith("com/retromod/shim/neoforge/")
                        || name.startsWith("com/retromod/generated/legacyfabric/"))
                .toList();
        assertEquals(List.of(), dead,
                host + " must not register redirects that the launch sweep drops with a crash warning");
    }
}
