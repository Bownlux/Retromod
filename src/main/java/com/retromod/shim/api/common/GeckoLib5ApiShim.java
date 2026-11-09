/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.RetromodLog;
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
import java.util.regex.Pattern;

/** Moves GeckoLib 4 class names to {@code com.geckolib} where the installed GeckoLib uses that package. */
public class GeckoLib5ApiShim implements MinecraftVersionedApiShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Shim");
    private static final String RESOURCE = "/retromod/geckolib-4-to-5-classes.tsv";
    private static final Pattern INTERNAL_NAME = Pattern.compile("[A-Za-z0-9_$/]+");

    /** GeckoLib's main class under the package every release through 5.4 uses. */
    static final String OLD_MAIN_CLASS = "software/bernie/geckolib/GeckoLib";
    /** GeckoLib's main class under the package 5.5 moved to. */
    static final String NEW_MAIN_CLASS = "com/geckolib/GeckoLib";

    /** GeckoLib 5 starts at Minecraft 1.21.5, so a mod built for it or later names GeckoLib 5 classes. */
    static final String FIRST_GECKOLIB_5_HOST = "1.21.5";
    // Modrinth, 2026-11-06: every GeckoLib through 5.4.5 (Minecraft 1.21.11) uses software.bernie.geckolib, and
    // every build from 5.5 (Minecraft 26.1) through 5.5.7 (Minecraft 26.3) uses com.geckolib, on all three loaders.
    static final String FIRST_HOST_WITH_NEW_PACKAGE = "26.1";
    // A newer host has no GeckoLib checked yet, so it keeps the old names unless its GeckoLib can be read.
    static final String FIRST_HOST_NOT_CHECKED = "26.4";

    // Only proven destinations (#223). The 4.x internals GeckoLib 5 dropped, mostly the texture cache, have no row.
    // GeckoLib 3 names are left to GeckoLibApiShim.
    private static final Map<String, String> CLASS_MOVES = load();

    @Override
    public String getShimName() {
        return "GeckoLib 4 to 5 API Compatibility";
    }

    @Override
    public String getSourceVersion() {
        return "4.0.0";
    }

    /** No host older than GeckoLib 5's first Minecraft version is offered the moves at all. */
    @Override
    public String getTargetVersion() {
        return FIRST_GECKOLIB_5_HOST;
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        registerIfInstalledMoved(transformer, GeckoLib5ApiShim::installedHasClass,
                RetromodVersion.TARGET_MC_VERSION);
    }

    /** Registers the moves when the GeckoLib mods will run with uses {@code com.geckolib}, and returns how many. */
    static int registerIfInstalledMoved(RetromodTransformer transformer, Predicate<String> installedHasClass,
            String host) {
        // GeckoLib 5.4.5 for 1.21.11 is GeckoLib 5 and still ships software.bernie.geckolib, so its names stay.
        boolean installedKeepsOldPackage = installedHasClass.test(OLD_MAIN_CLASS);
        boolean installedMoved = !installedKeepsOldPackage && installedHasClass.test(NEW_MAIN_CLASS);
        if (installedKeepsOldPackage || (!installedMoved && !hostShipsNewPackage(host))) {
            LOGGER.debug(RetromodLog.DETAIL, "GeckoLib for host {} keeps software.bernie.geckolib, so GeckoLib 4 "
                    + "names stay as they are", host);
            return 0;
        }
        for (Map.Entry<String, String> move : CLASS_MOVES.entrySet()) {
            transformer.registerClassRedirect(move.getKey(), move.getValue());
            // A mod built for 1.21.5 or newer names GeckoLib 5 classes, whose layout this 4.x table does not describe.
            transformer.limitClassRedirectToModsBefore(move.getKey(), FIRST_GECKOLIB_5_HOST);
            // Only the version table decided, so a GeckoLib 4 the mod ships or is translated with counts as the
            // installed one. The in-place pass holds GeckoLib 4 back from the loader, so no class loader shows it.
            if (!installedMoved) transformer.yieldClassRedirectToLibrary(move.getKey(), OLD_MAIN_CLASS);
        }
        LOGGER.debug(RetromodLog.DETAIL, "Registered {} GeckoLib 4 to com.geckolib class move(s) for host {}",
                CLASS_MOVES.size(), host);
        return CLASS_MOVES.size();
    }

    /** Whether the GeckoLib published for {@code host} uses {@code com.geckolib}; false when that is unknown. */
    static boolean hostShipsNewPackage(String host) {
        if (host == null || host.isBlank()) return false;
        return RetromodVersion.compareMcVersions(host, FIRST_HOST_WITH_NEW_PACKAGE) >= 0
                && RetromodVersion.compareMcVersions(host, FIRST_HOST_NOT_CHECKED) < 0;
    }

    /** Reads, never loads, a class from the game's class loader or from a library linked to an offline transform. */
    private static boolean installedHasClass(String internalName) {
        // In a game the loader has put the jars in mods/ on this class loader before the shims register.
        return ClassResourceInspector.exists(internalName)
                || RetromodTransformer.readLinkedLibraryClassForAdapter(internalName) != null;
    }

    /** The moves this shim can register, for tests and reporting. */
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
