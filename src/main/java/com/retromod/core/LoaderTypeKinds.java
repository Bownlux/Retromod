/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.McReflect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Which mod loader types the host answers for, and the Forge types a checked table lists as interfaces. */
public final class LoaderTypeKinds {

    static final String FORGE_ROOT = "net/minecraftforge/";
    static final String NEOFORGE_ROOT = "net/neoforged/";
    private static final String FORGE_TABLE = "retromod/forge-host-interfaces.tsv";

    // Type to the first Minecraft version its row was checked on, read once.
    private static volatile Map<String, String> forgeInterfaces;

    private LoaderTypeKinds() {}

    /** Whether the name sits under a mod loader's own packages, its event and bus packages included. */
    public static boolean isLoaderName(String owner) {
        return owner != null && (owner.startsWith(FORGE_ROOT) || owner.startsWith(NEOFORGE_ROOT));
    }

    /** Whether the target loader owns the name, so that loader's jars are the host that answers for it. */
    public static boolean targetOwns(String owner) {
        // Another loader's names are left as written: on Fabric a mod may ship them itself.
        if (owner.startsWith(FORGE_ROOT)) return McReflect.isForgeTarget();
        if (owner.startsWith(NEOFORGE_ROOT)) return McReflect.isNeoForge();
        return false;
    }

    /** Whether the checked table lists the type as an interface on this Forge target, for a host that cannot be read. */
    public static boolean tabledInterface(String owner) {
        // NeoForge 26.x made no NeoForge 1.21.x class an interface, so only a Forge target has rows.
        if (owner == null || !owner.startsWith(FORGE_ROOT) || !McReflect.isForgeTarget()) return false;
        String since = forgeInterfaces().get(owner);
        // Below the first version a row was checked on, the type may still be a class, so the call stays.
        return since != null
                && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0;
    }

    private static Map<String, String> forgeInterfaces() {
        Map<String, String> table = forgeInterfaces;
        if (table == null) {
            table = readTable();
            forgeInterfaces = table;
        }
        return table;
    }

    private static Map<String, String> readTable() {
        Map<String, String> table = new HashMap<>();
        try (InputStream in = LoaderTypeKinds.class.getClassLoader().getResourceAsStream(FORGE_TABLE)) {
            if (in == null) throw new IllegalStateException("Retromod's jar is missing " + FORGE_TABLE);
            BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = lines.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] columns = line.split("\t");
                if (columns.length != 2) throw new IllegalStateException("Malformed row in " + FORGE_TABLE + ": " + line);
                table.put(columns[1], columns[0]);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Retromod could not read " + FORGE_TABLE, failure);
        }
        return Map.copyOf(table);
    }
}
