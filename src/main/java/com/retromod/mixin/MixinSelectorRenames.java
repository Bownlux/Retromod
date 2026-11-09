/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.Opcodes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Finds the host method a Mixin selector meant when the host renamed its target, from proven rows and the 26.1 GUI rename. */
public final class MixinSelectorRenames {

    private static final String TABLE = "/retromod/mixin-selector-renames.tsv";
    private static final String GUI_PACKAGE = "net/minecraft/client/gui/";
    private static final List<Row> ROWS = load();

    private MixinSelectorRenames() {}

    /** One proven rename; a {@code *} owner or descriptor matches any. */
    record Row(String owner, String oldName, String oldDesc, String newName, String newDesc, String since) {}

    /**
     * The host method {@code owner.name(desc)} became, or {@code null} when nothing is proven.
     * {@code desc} is {@code null} for a bare selector. The owner must lack the old method and
     * declare exactly one concrete candidate, and the host must be at least the row's version.
     */
    public static FuzzyMethodResolver.MethodInfo renamed(FuzzyMethodResolver host, String owner, String name,
            String desc) {
        if (host == null || !host.isIndexed() || owner == null || name == null || !host.hasClass(owner)) return null;
        if (declares(host, owner, name, desc)) return null;
        for (Row row : ROWS) {
            if (!row.oldName().equals(name) || (!row.owner().equals("*") && !row.owner().equals(owner))) continue;
            if (desc != null && !row.oldDesc().equals("*") && !row.oldDesc().equals(desc)) continue;
            if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, row.since()) < 0) continue;
            // A wildcard owner names no class, so only the new method's exact shape proves it.
            if (row.owner().equals("*") && row.newDesc().equals("*")) continue;
            FuzzyMethodResolver.MethodInfo only = soleConcrete(host, owner, row.newName(), row.newDesc());
            if (only != null) return only;
        }
        return guiExtract(host, owner, name, desc);
    }

    /**
     * 26.1 renamed the GUI's {@code render...} methods to {@code extract...} and kept their
     * parameters: {@code Screen.render} became {@code extractRenderState}, {@code renderTooltip}
     * became {@code extractTooltip}. Only the same descriptor, or for a bare selector the one
     * method of that name, counts.
     */
    private static FuzzyMethodResolver.MethodInfo guiExtract(FuzzyMethodResolver host, String owner, String name,
            String desc) {
        if (!name.startsWith("render") || !inGuiTree(host, owner)) return null;
        String candidate = name.equals("render") ? "extractRenderState" : "extract" + name.substring("render".length());
        return soleConcrete(host, owner, candidate, desc == null ? "*" : desc);
    }

    /** Whether the owner is a GUI class or extends one, as loaders' own screens and overlays do. */
    private static boolean inGuiTree(FuzzyMethodResolver host, String owner) {
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(List.of(owner));
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!pending.isEmpty()) {
            String type = pending.poll();
            if (!seen.add(type) || seen.size() > 32) continue;
            if (type.startsWith(GUI_PACKAGE)) return true;
            pending.addAll(host.directSupertypes(type));
        }
        return false;
    }

    /** {@code value} ({@code Lowner;name(desc)}) moved to the renamed method, keeping its descriptor when the host's is the same. */
    public static String renameResourceSelector(FuzzyMethodResolver host, String value) {
        if (value == null || !value.startsWith("L")) return value;
        int semicolon = value.indexOf(';');
        int paren = value.indexOf('(');
        if (semicolon < 0 || paren < semicolon) return value;
        String owner = value.substring(1, semicolon);
        String name = value.substring(semicolon + 1, paren);
        String desc = value.substring(paren);
        FuzzyMethodResolver.MethodInfo renamed = renamed(host, owner, name, desc);
        // The descriptor follows only where the class repair or the refmap repair can prove it.
        return renamed == null ? value : "L" + owner + ";" + renamed.name() + desc;
    }

    private static boolean declares(FuzzyMethodResolver host, String owner, String name, String desc) {
        for (FuzzyMethodResolver.MethodInfo method : host.getDeclaredMethods(owner)) {
            if (method.name().equals(name) && (desc == null || method.descriptor().equals(desc))) return true;
        }
        return false;
    }

    /** The one concrete method of that name, and descriptor unless {@code *}, the owner declares. */
    private static FuzzyMethodResolver.MethodInfo soleConcrete(FuzzyMethodResolver host, String owner, String name,
            String desc) {
        FuzzyMethodResolver.MethodInfo found = null;
        for (FuzzyMethodResolver.MethodInfo method : host.getDeclaredMethods(owner)) {
            if (!method.name().equals(name) || (!desc.equals("*") && !method.descriptor().equals(desc))) continue;
            if ((method.access() & Opcodes.ACC_ABSTRACT) != 0 || found != null) return null;
            found = method;
        }
        return found;
    }

    private static List<Row> load() {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = MixinSelectorRenames.class.getResourceAsStream(TABLE)) {
            if (in == null) return List.of();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] cells = line.split("\t");
                if (cells.length == 6) rows.add(new Row(cells[0], cells[1], cells[2], cells[3], cells[4], cells[5]));
            }
        } catch (IOException unreadable) {
            return List.of();
        }
        return List.copyOf(rows);
    }
}
