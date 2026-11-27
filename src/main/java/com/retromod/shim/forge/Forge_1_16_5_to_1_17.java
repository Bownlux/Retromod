/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Forge 1.16.5 to 1.17: Mojang's class names replaced MCP's, getAllTags() went, and RegistryObject moved. */
public class Forge_1_16_5_to_1_17 implements VersionShim {

    @Override public String getShimName() { return "Forge 1.16.5 to 1.17"; }
    @Override public String getSourceVersion() { return "1.16.5"; }
    @Override public String getTargetVersion() { return "1.17"; }
    @Override public String getModLoaderType() { return "forge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // 1.16.5 still uses the legacy Forge/MCP class namespace. Reuse the host-validated
        // direct move table so the entire package migration runs, not only member shims.
        new Forge_1_12_2_to_1_13_2().loadDirectClassMoves(transformer);
        loadMcpClassNames(transformer);
        registerConfigMoves(transformer);
        // 1.17 moved RegistryObject from fml to registries with the same members; NeoForge's chain takes it on to DeferredHolder.
        transformer.registerClassRedirect("net/minecraftforge/fml/RegistryObject", "net/minecraftforge/registries/RegistryObject");
        transformer.registerClassRedirect(
            "net/minecraft/block/FlowingFluidBlock",
            "net/minecraft/world/level/block/LiquidBlock"
        );
        String properties = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
        String result = "L" + properties + ";";
        transformer.registerArgDropMethodRedirect(
            properties, "of",
            "(Lnet/minecraft/block/material/Material;)" + result,
            properties, "of", "()" + result
        );
        transformer.registerArgDropMethodRedirect(
            properties, "of",
            "(Lnet/minecraft/block/material/Material;"
                    + "Lnet/minecraft/world/level/material/MapColor;)" + result,
            properties, "of", "()" + result
        );
    }

    private static final String MCP_CLASS_NAMES = "/retromod/forge-mcp-class-names.tsv";
    /**
     * The first version whose Forge mods take the table, and the first that uses Mojang's names. MCPConfig named
     * classes from 1.13, but the table is built from 1.14.4 on, and 1.13.2 used some names for other classes.
     */
    static final String FIRST_MCP_NAMED = "1.14";
    static final String FIRST_MOJANG_NAMED = "1.17";

    /** One row of the MCP class name table: the class it names, and the first 26.x host it no longer applies to. */
    record McpClassName(String mcpName, String target, boolean live, String untilHost) {}

    /** Moves the MCP class names of Forge mods for 1.14 to 1.16.5 onto a 26.x host's classes; returns how many. */
    static int loadMcpClassNames(RetromodTransformer transformer) {
        String host = RetromodVersion.TARGET_MC_VERSION;
        // The targets were checked on 26.x hosts only. Older hosts run SRG member names and other class names.
        if (!RetromodVersion.isUnobfuscatedTarget(host)) return 0;
        Map<String, String> existing = transformer.getClassRedirects();
        int registered = 0;
        for (McpClassName row : mcpClassNames()) {
            // Past its last host the chain no longer ends on a host class, so the name stays unresolved.
            if (!row.untilHost().equals("*") && !RetromodVersion.mcVersionExceeds(row.untilHost(), host)) continue;
            // A name another shim already takes keeps that redirect, and the limits below would bind it too.
            if (existing.containsKey(row.mcpName())) continue;
            transformer.registerClassRedirect(row.mcpName(), row.target());
            // 1.12.2 and 1.13.2 used some of these names for other classes.
            transformer.limitClassRedirectToModsFrom(row.mcpName(), FIRST_MCP_NAMED);
            // A host or a Mojang-named version also has a class under this name, so only a mod known to be older
            // takes it. Any other MCP name comes from MCP-era code, even in a jar whose pack format says 1.18.
            if (row.live()) transformer.limitClassRedirectToKnownModsBefore(row.mcpName(), FIRST_MOJANG_NAMED);
            registered++;
        }
        return registered;
    }

    /** The bundled MCP class name table, read once. */
    static List<McpClassName> mcpClassNames() {
        return McpClassNames.ROWS;
    }

    private static final class McpClassNames {
        static final List<McpClassName> ROWS = read();

        private static List<McpClassName> read() {
            List<McpClassName> rows = new ArrayList<>();
            try (InputStream in = Forge_1_16_5_to_1_17.class.getResourceAsStream(MCP_CLASS_NAMES)) {
                if (in == null) return List.of();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] columns = line.split("\t", -1);
                    if (columns.length < 5) continue;
                    rows.add(new McpClassName(columns[0], columns[1], columns[2].equals("host"), columns[4]));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Retromod could not read its bundled " + MCP_CLASS_NAMES, e);
            }
            return List.copyOf(rows);
        }
    }

    /**
     * 1.17 moved the config events out of {@code ModConfig} and typed {@code registerConfig}'s spec
     * as {@code IConfigSpec}, so a 1.16 mod's config registration failed with
     * {@code NoSuchMethodError}. On NeoForge the later config bridge takes the call from here.
     */
    private static void registerConfigMoves(RetromodTransformer transformer) {
        String oldEvent = "net/minecraftforge/fml/config/ModConfig$";
        String newEvent = "net/minecraftforge/fml/event/config/ModConfigEvent";
        transformer.registerClassRedirect(oldEvent + "ModConfigEvent", newEvent);
        transformer.registerClassRedirect(oldEvent + "Loading", newEvent + "$Loading");
        transformer.registerClassRedirect(oldEvent + "Reloading", newEvent + "$Reloading");

        String context = "net/minecraftforge/fml/ModLoadingContext";
        String type = "Lnet/minecraftforge/fml/config/ModConfig$Type;";
        String oldSpec = "Lnet/minecraftforge/common/ForgeConfigSpec;";
        String spec = "Lnet/minecraftforge/fml/config/IConfigSpec;";
        for (String fileName : new String[] {"", "Ljava/lang/String;"}) {
            transformer.registerMethodRedirect(context, "registerConfig", "(" + type + oldSpec + fileName + ")V",
                    context, "registerConfig", "(" + type + spec + fileName + ")V");
        }
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
