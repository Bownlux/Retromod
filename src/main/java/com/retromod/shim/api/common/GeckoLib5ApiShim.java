/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.MinecraftVersionedApiShim;
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
import java.util.regex.Pattern;

/**
 * Follows GeckoLib's move from {@code software.bernie.geckolib} to {@code com.geckolib}.
 *
 * <p>GeckoLib 5 renamed the package and reorganised the library in the same release, so a mod built
 * against 4.x fails on its first animated item or entity with
 * {@code NoClassDefFoundError: software/bernie/geckolib/animatable/GeoItem} even though the host
 * has GeckoLib installed and working (#223). The name is simply gone.
 *
 * <p>This is gated on the Minecraft host rather than on a GeckoLib version because the two track
 * each other: GeckoLib 4.x stops at Minecraft 1.21.4 and 5.x begins at 1.21.5. Applying it below
 * that boundary would rewrite a mod onto packages the host's own GeckoLib does not ship.
 *
 * <p>It covers the classes whose destination is proven. GeckoLib 5 also dropped a number of 4.x
 * internals outright, mostly around the texture cache, and a mod reaching those needs a real port.
 * The earlier 3.x to 4.x rewrite is handled separately by {@link GeckoLibApiShim}.
 */
public class GeckoLib5ApiShim implements MinecraftVersionedApiShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Shim");
    private static final String RESOURCE = "/retromod/geckolib-4-to-5-classes.tsv";
    private static final Pattern INTERNAL_NAME = Pattern.compile("[A-Za-z0-9_$/]+");

    private static final Map<String, String> CLASS_MOVES = load();

    @Override
    public String getShimName() {
        return "GeckoLib 4 to 5 API Compatibility";
    }

    @Override
    public String getSourceVersion() {
        return "4.0.0";
    }

    /**
     * The first Minecraft version that carries GeckoLib 5. The shim registry treats this as the
     * host floor, so nothing is rewritten on a host whose GeckoLib is still 4.x.
     */
    @Override
    public String getTargetVersion() {
        return "1.21.5";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        for (Map.Entry<String, String> move : CLASS_MOVES.entrySet()) {
            transformer.registerClassRedirect(move.getKey(), move.getValue());
        }
    }

    /** The moves this shim registers, for tests and reporting. */
    public static Map<String, String> classMoves() {
        return CLASS_MOVES;
    }

    private static Map<String, String> load() {
        Map<String, String> moves = new LinkedHashMap<>();
        try (InputStream in = GeckoLib5ApiShim.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("GeckoLib table {} is missing; mods built against GeckoLib 4 will keep "
                        + "their original package names.", RESOURCE);
                return Map.of();
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    if (line.isBlank() || line.charAt(0) == '#') continue;
                    String[] parts = line.split("\t");
                    if (parts.length != 2) throw malformed(number, "expected two columns");
                    String from = requireInternalName(parts[0], number);
                    String to = requireInternalName(parts[1], number);
                    if (from.equals(to)) throw malformed(number, "a row that moves nothing");
                    if (moves.put(from, to) != null) {
                        throw malformed(number, "'" + from + "' is listed twice");
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read {} ({}). Continuing with {} GeckoLib move(s).",
                    RESOURCE, e.toString(), moves.size());
        }
        return Map.copyOf(moves);
    }

    private static String requireInternalName(String value, int lineNumber) {
        if (!INTERNAL_NAME.matcher(value).matches()) {
            throw malformed(lineNumber, "'" + value + "' is not a class internal name");
        }
        return value;
    }

    private static IllegalStateException malformed(int lineNumber, String reason) {
        return new IllegalStateException(
                "Malformed " + RESOURCE + " at line " + lineNumber + ": " + reason);
    }
}
