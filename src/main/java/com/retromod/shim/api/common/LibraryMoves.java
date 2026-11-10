/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Applies a third-party library's renames only where the library the mods run with has dropped the old names. */
public final class LibraryMoves {

    /**
     * What a transform may rely on when no library can be read: every release of the library for hosts from
     * {@code firstHost} lacks the old names, and a mod built for {@code firstModVersion} or later never names them.
     */
    public record Table(String firstHost, String firstModVersion) {
        boolean covers(String host) {
            return host != null && !host.isBlank() && RetromodVersion.compareMcVersions(host, firstHost) >= 0;
        }
    }

    /** How one rename was decided. */
    enum Decision { KEEP, INSTALLED, BY_TABLE }

    private LibraryMoves() {}

    /** Registers each class move the library shows, and returns the old names that move. */
    public static Set<String> register(RetromodTransformer transformer, Map<String, String> moves, Table table) {
        return register(transformer, moves, table, RetromodVersion.TARGET_MC_VERSION, LibraryMoves::installedHasClass);
    }

    static Set<String> register(RetromodTransformer transformer, Map<String, String> moves, Table table, String host,
            Predicate<String> installedHasClass) {
        Set<String> moved = new LinkedHashSet<>();
        for (Map.Entry<String, String> move : moves.entrySet()) {
            String from = move.getKey();
            Decision decision = decide(from, move.getValue(), table, host, installedHasClass);
            if (decision == Decision.KEEP) continue;
            transformer.registerClassRedirect(from, move.getValue());
            // A jar that ships, or is translated with, a library still defining the old class keeps that class.
            transformer.yieldClassRedirectToLibrary(from, from);
            // Only the version table decided, so a mod built for the renamed library is left alone.
            if (decision == Decision.BY_TABLE) transformer.limitClassRedirectToModsBefore(from, table.firstModVersion());
            moved.add(from);
        }
        return moved;
    }

    /** Whether a reference to {@code from} becomes {@code to}: the library dropped one and has the other. */
    static Decision decide(String from, String to, Table table, String host, Predicate<String> installedHasClass) {
        // The library still has the old class, so the mods built against it keep it.
        if (installedHasClass.test(from)) return Decision.KEEP;
        if (installedHasClass.test(to)) return Decision.INSTALLED;
        // Neither name can be read, so only a proven version table may decide.
        return table != null && table.covers(host) ? Decision.BY_TABLE : Decision.KEEP;
    }

    /**
     * Registers a redirect of a library member only where the library lacks that member, limited to mods built
     * before {@code table}'s version when only the table decided. Returns whether it registered.
     */
    public static boolean registerMemberRedirect(RetromodTransformer transformer, String owner, String name, String desc,
            String newOwner, String newName, String newDesc, Table table) {
        return registerMemberRedirect(transformer, owner, name, desc, newOwner, newName, newDesc, table,
                RetromodVersion.TARGET_MC_VERSION, LibraryMoves::installedClass);
    }

    static boolean registerMemberRedirect(RetromodTransformer transformer, String owner, String name, String desc,
            String newOwner, String newName, String newDesc, Table table, String host,
            Function<String, ClassNode> installedClass) {
        ClassNode installed = installedClass.apply(owner);
        if (installed != null) {
            // The library still declares the member, so its callers keep the call.
            if (declares(installed, name, desc)) return false;
            transformer.registerMethodRedirect(owner, name, desc, newOwner, newName, newDesc);
            return true;
        }
        if (table == null || !table.covers(host)) return false;
        transformer.registerMethodRedirect(owner, name, desc, newOwner, newName, newDesc);
        transformer.limitRedirectToModsBefore(owner, name, desc, table.firstModVersion());
        return true;
    }

    private static boolean declares(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    /** Reads, never loads, a class from the game's class loader or from a library linked to an offline transform. */
    static boolean installedHasClass(String internalName) {
        // In a game the loader has put the jars in mods/ on this class loader before the shims register.
        return ClassResourceInspector.exists(internalName)
                || RetromodTransformer.readLinkedLibraryClassForAdapter(internalName) != null;
    }

    private static ClassNode installedClass(String internalName) {
        ClassNode game = ClassResourceInspector.read(internalName);
        if (game != null) return game;
        byte[] linked = RetromodTransformer.readLinkedLibraryClassForAdapter(internalName);
        if (linked == null) return null;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(linked).accept(node, ClassReader.SKIP_CODE);
            return node;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
