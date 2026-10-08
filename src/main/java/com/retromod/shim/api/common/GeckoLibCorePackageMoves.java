/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Follows GeckoLib 4.5 folding {@code software.bernie.geckolib.core} into its main packages (#308).
 *
 * <p>A mod built against GeckoLib 4.0 to 4.4 for Minecraft 1.20.1 names
 * {@code software/bernie/geckolib/core/animation/AnimationController}; a 1.21.1 host ships
 * {@code software/bernie/geckolib/animation/AnimationController}. The table lists only proven
 * destinations.
 *
 * <p>The host's own GeckoLib decides whether this applies: it registers only when the merged
 * {@code AnimationController} exists and the core one does not. That leaves a 1.20.1 host's GeckoLib
 * alone, and a 1.21.5 or newer host to {@link GeckoLib5ApiShim}, whose GeckoLib has neither.
 * GeckoLib 3 names are not moved: that version loads beside GeckoLib 4 under its own package.
 */
public final class GeckoLibCorePackageMoves {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Shim");
    private static final String RESOURCE = "/retromod/geckolib-core-to-4.5-classes.tsv";
    static final String MERGED_CONTROLLER = "software/bernie/geckolib/animation/AnimationController";
    static final String CORE_CONTROLLER = "software/bernie/geckolib/core/animation/AnimationController";

    private GeckoLibCorePackageMoves() {}

    /** Called by {@link GeckoLibApiShim}, which runs on every loader. */
    public static void register(RetromodTransformer transformer) {
        registerIfHostMerged(transformer, GeckoLibCorePackageMoves::hostHasClass);
    }

    static int registerIfHostMerged(RetromodTransformer transformer, Predicate<String> hostHasClass) {
        if (!hostHasClass.test(MERGED_CONTROLLER) || hostHasClass.test(CORE_CONTROLLER)) return 0;
        Map<String, String> moves = classMoves();
        moves.forEach(transformer::registerClassRedirect);
        LOGGER.info("Registered {} GeckoLib core package move(s) for this host's GeckoLib", moves.size());
        return moves.size();
    }

    static Map<String, String> classMoves() {
        Map<String, String> moves = new LinkedHashMap<>();
        try (InputStream in = GeckoLibCorePackageMoves.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("GeckoLib table {} is missing; 1.20.1 GeckoLib mods keep the core package.", RESOURCE);
                return Map.of();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.charAt(0) == '#') continue;
                    String[] parts = line.split("\t");
                    if (parts.length != 2 || parts[0].equals(parts[1])) {
                        throw new IllegalStateException("Malformed " + RESOURCE + " row: " + line);
                    }
                    moves.put(parts[0], parts[1]);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read {} ({}).", RESOURCE, e.toString());
        }
        return moves;
    }

    /**
     * Reads the class file instead of loading the class: NeoForge's dist cleaner throws for a
     * client-only class on a dedicated server, even with {@code initialize=false}.
     */
    private static boolean hostHasClass(String internalName) {
        return com.retromod.core.ClassResourceInspector.exists(internalName);
    }
}
