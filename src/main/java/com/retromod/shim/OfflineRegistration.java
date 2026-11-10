/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.AuxiliaryVersionShim;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.OfflineLoaderNameMappings;
import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Registers one offline transform's redirects in the order a running game registers them. */
public final class OfflineRegistration {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Shim");

    private static final String SERVICES = "META-INF/services/" + VersionShim.class.getName();

    private static volatile Map<String, Integer> serviceRanks;

    private OfflineRegistration() {}

    /** What one pass registered beside the version shims, for the CLI's summary lines. */
    public record Counts(int apiShims, int classMoves, int intermediaryMappings, int srgMappings) {}

    /** A shared pass the version shim for one edge runs, registered alone when a chain lacks that shim. */
    private record EdgePass(String source, String target, Consumer<RetromodTransformer> pass) {}

    /** Registers {@code versionShims} (a chain, or every shim an unknown source takes) and what the game adds. */
    public static Counts register(RetromodTransformer transformer, ShimRegistry registry,
            ModVersionInfo info, String target, List<VersionShim> versionShims) {
        String loader = info.modLoaderType();

        // A later registration wins a shared key, so this follows the game: version shims in
        // service order, then API shims, then the loader's own passes. An offline chain can lack
        // the 26.x shim that runs a shared pass (a 26.1 mod, a version that is no graph node), so
        // that pass is registered alone where the shim would have run it.
        List<VersionShim> versions = new ArrayList<>();
        for (VersionShim shim : versionShims) {
            if (!(shim instanceof AuxiliaryVersionShim)) versions.add(shim);
        }
        registerVersionShims(transformer, inRuntimeOrder(versions),
                edgePasses(target, info.targetMcVersion()));
        int apiShims = registerApiShims(transformer, registry, loader, target, versionShims);

        // Then each loader's own passes, in the order its entry point runs them.
        Counts passes;
        switch (hostFamily(loader)) {
            case "fabric" -> {
                registerPre26FabricBridges(transformer, target);
                passes = registerLoaderPasses(transformer, loader, target);
                registerPolyfills(transformer);
            }
            case "neoforge" -> {
                passes = registerLoaderPasses(transformer, loader, target);
                registerPolyfills(transformer);
            }
            default -> {
                registerPolyfills(transformer);
                passes = registerLoaderPasses(transformer, loader, target);
            }
        }
        // Last, as in the NeoForge and Forge entry points: Minecraft renames for Mojang-named mods.
        // A Fabric or Quilt mod reaches host names through intermediary ids instead.
        if ("fabric".equals(hostFamily(loader))) {
            transformer.setMojangRenames(null);
        } else {
            try {
                com.retromod.mapping.MojangRenameTable.register(transformer, target);
            } catch (Exception e) {
                transformer.setMojangRenames(null);
            }
        }
        return new Counts(apiShims, passes.classMoves(), passes.intermediaryMappings(),
                passes.srgMappings());
    }

    /** Registers the API shims in {@code given} and the registry's for {@code loader}, in service order. */
    public static int registerApiShims(RetromodTransformer transformer, ShimRegistry registry,
            String loader, String target, List<VersionShim> given) {
        LinkedHashSet<VersionShim> api = new LinkedHashSet<>();
        for (VersionShim shim : given) {
            if (shim instanceof AuxiliaryVersionShim) api.add(shim);
        }
        if (registry != null) api.addAll(registry.findApiShimsForLoader(loader, target));
        int applied = 0;
        for (VersionShim shim : inRuntimeOrder(new ArrayList<>(api))) {
            // One that fails is skipped alone, as an optional API.
            try {
                shim.registerRedirects(transformer);
                applied++;
            } catch (Exception e) {
                LOGGER.debug("Could not register optional API shim {} for loader {}",
                        shim.getShimName(), loader, e);
            }
        }
        return applied;
    }

    /** {@code shims} in the order a running game registers them: the service file's order. */
    public static List<VersionShim> inRuntimeOrder(List<VersionShim> shims) {
        Map<String, Integer> ranks = serviceRanks();
        List<VersionShim> ordered = new ArrayList<>(shims);
        // A stable sort keeps a shim the service file does not list after the listed ones, in place.
        ordered.sort(Comparator.comparingInt(
                shim -> ranks.getOrDefault(shim.getClass().getName(), Integer.MAX_VALUE)));
        return ordered;
    }

    private static void registerVersionShims(RetromodTransformer transformer,
            List<VersionShim> versions, List<EdgePass> edgePasses) {
        List<EdgePass> pending = new ArrayList<>();
        for (EdgePass edge : edgePasses) {
            if (!coversEdge(versions, edge)) pending.add(edge);
        }
        for (VersionShim shim : versions) {
            // A missing edge's pass runs before the first shim that starts at or after it.
            while (!pending.isEmpty() && startsAtOrAfter(shim, pending.get(0).target())) {
                runEdgePass(transformer, pending.remove(0));
            }
            // As in the game, a shim that throws is skipped alone and logged.
            ShimRegistration.register(shim, transformer);
        }
        for (EdgePass edge : pending) runEdgePass(transformer, edge);
    }

    /** The shared passes the 26.x version shims of every loader run, for a 26.x target. */
    private static List<EdgePass> edgePasses(String target, String sourceVersion) {
        List<EdgePass> passes = new ArrayList<>();
        if (!RetromodVersion.isUnobfuscatedTarget(target)) return passes;
        passes.add(new EdgePass("1.21.11", "26.1",
                com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves::register));
        if (!RetromodVersion.mcVersionExceeds("26.2", target)) {
            passes.add(new EdgePass("26.1", "26.2",
                    com.retromod.shim.common.Mc26_1To26_2CoreMoves::register));
        }
        // A 26.3 mod is left alone: the keybind redirects here expect GLFW numbers.
        if (!RetromodVersion.mcVersionExceeds("26.3", target)
                && RetromodVersion.mcVersionExceeds("26.3", sourceVersion)) {
            passes.add(new EdgePass("26.2", "26.3",
                    com.retromod.shim.common.Mc26_2To26_3CoreMoves::register));
        }
        return passes;
    }

    private static boolean coversEdge(List<VersionShim> versions, EdgePass edge) {
        for (VersionShim shim : versions) {
            if (edge.source().equals(shim.getSourceVersion())
                    && edge.target().equals(shim.getTargetVersion())) return true;
        }
        return false;
    }

    private static boolean startsAtOrAfter(VersionShim shim, String version) {
        try {
            return RetromodVersion.compareMcVersions(shim.getSourceVersion(), version) >= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void runEdgePass(RetromodTransformer transformer, EdgePass edge) {
        try {
            edge.pass().accept(transformer);
        } catch (Exception e) {
            // The name remap and class moves still apply.
            LOGGER.debug("Could not register the shared {} to {} pass", edge.source(), edge.target(), e);
        }
    }

    /** Fabric and Quilt mods run on a Fabric host; a NeoForge mod or target on NeoForge; the rest on Forge. */
    private static String hostFamily(String loader) {
        if ("fabric".equalsIgnoreCase(loader) || "quilt".equalsIgnoreCase(loader)) return "fabric";
        if ("neoforge".equalsIgnoreCase(loader) || McReflect.isNeoForge()) return "neoforge";
        return "forge";
    }

    /** The removed-API replacements, which every entry point registers after its shims. */
    private static void registerPolyfills(RetromodTransformer transformer) {
        new com.retromod.polyfill.PolyfillRegistry().loadAndRegister(transformer);
    }

    /** Pre-26 Fabric hosts keep intermediary names; the game finds these 1.20.5 and 1.21.2 changes from host bytes. */
    private static void registerPre26FabricBridges(RetromodTransformer transformer, String target) {
        if (!RetromodVersion.isUnobfuscatedTarget(target)
                && !RetromodVersion.mcVersionExceeds("1.21.2", target)) {
            com.retromod.shim.fabric.Pre1_21_2EntityTypeBuildBridge.registerRedirects(transformer);
        }
        // The Gson text adapters 1.20.3 ended and the serializer's old statics, in intermediary names. Registered
        // first, so the registry-passing helper for the JSON text calls 1.20.5 changed stands aside where it answers.
        com.retromod.shim.common.LegacyComponentJsonBridge.registerIntermediaryForTarget(transformer, target);
        com.retromod.shim.fabric.Pre1_20_5ComponentJsonBridge.registerForTarget(transformer, target);
        // KeyDispatchDataCodec's 1.20.5 map codec shape.
        if (!RetromodVersion.isUnobfuscatedTarget(target)) {
            com.retromod.shim.common.LegacyKeyDispatchCodecBridge.registerIntermediaryForVersion(transformer, target);
        }
    }

    /** The member names, class moves and constructor factories an entry point registers after its shims. */
    private static Counts registerLoaderPasses(RetromodTransformer transformer, String loader,
            String target) {
        var names = OfflineLoaderNameMappings.configure(transformer, loader, target,
                McReflect.isNeoForge());
        boolean forgeFamily = "forge".equalsIgnoreCase(loader) || "neoforge".equalsIgnoreCase(loader);
        boolean unobfuscated = RetromodVersion.isUnobfuscatedTarget(target);
        int classMoves = 0;
        if (!unobfuscated) {
            // A host before 26.1 already made some of the 26.1 renames, so a Mojang-named mod gets
            // each move its host has.
            if (forgeFamily) {
                try {
                    classMoves = IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
                } catch (Exception e) {
                    // The other registered changes still produce a useful result.
                }
            }
        } else {
            try {
                for (var move : IntermediaryToMojangMapper.getInstance().getClassMoves().entrySet()) {
                    // A Fabric API move's stand-in is registered only for Fabric and Quilt mods.
                    if (!IntermediaryToMojangMapper.appliesToLoader(move.getKey(), loader)) continue;
                    transformer.registerClassRedirect(move.getKey(), move.getValue());
                    classMoves++;
                }
            } catch (Exception e) {
                // Other registered changes can still produce a useful result.
            }
            IntermediaryToMojangMapper.registerIdentifierCtorRedirects(transformer);
        }
        // Stand-ins for classes the target lacks, which the NeoForge entry point registers on every
        // host from 1.20.2: Forge's own classes there, NeoForge's removals only from the version
        // checked to lack them. A Forge target takes them on 26.x, for a jar that later runs on
        // NeoForge; reference scanning decides which are embedded.
        boolean neoForgeHost = "neoforge".equals(hostFamily(loader))
                && !RetromodVersion.mcVersionExceeds("1.20.2", target);
        if (forgeFamily && (unobfuscated || neoForgeHost)) {
            try {
                com.retromod.shim.forge.ForgeNeoForgeSynthetics.registerAll(transformer, target);
            } catch (Exception e) {
                // Optional bridges should not discard the transformed jar.
            }
        }
        return new Counts(0, classMoves, names.intermediaryMappings(), names.srgMappings());
    }

    /** Service-file position of each listed shim class, read once without loading the classes. */
    private static Map<String, Integer> serviceRanks() {
        Map<String, Integer> ranks = serviceRanks;
        if (ranks != null) return ranks;
        Map<String, Integer> read = new HashMap<>();
        try {
            ClassLoader loader = VersionShim.class.getClassLoader();
            Enumeration<URL> files = loader.getResources(SERVICES);
            while (files.hasMoreElements()) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(
                        files.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        int comment = line.indexOf('#');
                        String name = (comment >= 0 ? line.substring(0, comment) : line).trim();
                        if (!name.isEmpty()) read.putIfAbsent(name, read.size());
                    }
                }
            }
        } catch (IOException e) {
            // Without the file the given order stands.
            LOGGER.debug("Could not read the version shim service file", e);
        }
        serviceRanks = Map.copyOf(read);
        return serviceRanks;
    }
}
