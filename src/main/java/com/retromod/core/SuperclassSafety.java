/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * Reads the shape of a class on the host without loading it.
 *
 * <p>Two transforms need this. A class move is applied as a flat rename, so every occurrence of the
 * old internal name becomes the new one. That is correct for a type reference and wrong for an
 * inheritance edge: a destination can be a perfectly good type and still be impossible to extend.
 * Minecraft's own tables contain both kinds. {@code ResourceLocation -> Identifier} is a final class
 * that no mod extends, and the rename is right. {@code RecordItem -> JukeboxSong} is a final record,
 * and a mod that extended the old item class died at class load with
 * {@code IncompatibleClassChangeError} rather than anywhere near the transform (#260).
 *
 * <p>The other is the class-to-interface opcode fix, which has to know whether a call owner is an
 * interface on this host so it can pick the interface opcode and the matching constant pool entry.
 * Taking that from a hardcoded list means the list is wrong on every host it was not written for.
 *
 * <p>Shape comes from the indexed Minecraft jar first, because that is the jar the mod is being
 * transformed for. With no index it falls back to {@link ClassResourceInspector}, which parses the
 * class file straight from loader resources and so answers for the running host on the in-game
 * paths. Neither path defines or initializes the class: initializing a Minecraft class during a
 * transform has crashed an untransformed mod before (#46).
 *
 * <p>An offline transform with no {@code --mc-jar} can read neither, so it answers
 * {@link Verdict#UNKNOWN} and callers keep whatever they did before.
 */
public final class SuperclassSafety {

    private SuperclassSafety() {}

    /** What the host says a name is. */
    public enum Verdict {
        /** A normal class: extendable, and its methods take the class call form. */
        CLASS,
        /** Final. No class can extend it. */
        FINAL_CLASS,
        /** Sealed. Only its own listed subclasses may extend it, and a mod is never one. */
        SEALED,
        /** An interface. It belongs in {@code implements}, and its calls take the interface form. */
        INTERFACE,
        /** Not present on the host at all. */
        ABSENT,
        /** The host could not be read. Callers must not act on this. */
        UNKNOWN
    }

    /**
     * Classify {@code internalName} on the host.
     *
     * @param internalName JVM internal name
     * @param resolver     the indexed Minecraft jar, or null when none is indexed
     */
    public static Verdict classify(String internalName, FuzzyMethodResolver resolver) {
        if (internalName == null) return Verdict.UNKNOWN;

        if (resolver != null && resolver.isIndexed()) {
            int access = resolver.classAccess(internalName);
            if (access >= 0) {
                Verdict verdict = fromAccess(access);
                return verdict == Verdict.CLASS && resolver.isSealed(internalName)
                        ? Verdict.SEALED : verdict;
            }
            // An indexed jar is the target host, so a name it covers and does not have is absent
            // there. Do not fall through to the class loader for those: that would answer for
            // Retromod's own classpath, which is a different Minecraft version.
            if (isIndexedScope(internalName)) return Verdict.ABSENT;
        }

        ClassNode node = ClassResourceInspector.read(internalName);
        if (node == null) return Verdict.UNKNOWN;
        Verdict verdict = fromAccess(node.access);
        return verdict == Verdict.CLASS
                && node.permittedSubclasses != null && !node.permittedSubclasses.isEmpty()
                ? Verdict.SEALED : verdict;
    }

    /**
     * Whether the host says {@code internalName} is an interface, or null when it could not be
     * read. A null answer means "keep doing whatever you did before", not "no".
     */
    public static Boolean isInterfaceOnHost(String internalName, FuzzyMethodResolver resolver) {
        return switch (classify(internalName, resolver)) {
            case INTERFACE -> Boolean.TRUE;
            case CLASS, FINAL_CLASS, SEALED -> Boolean.FALSE;
            case ABSENT, UNKNOWN -> null;
        };
    }

    /** Whether a name in an {@code extends} slot would fail at class load. */
    public static boolean cannotBeExtended(Verdict verdict) {
        return verdict == Verdict.FINAL_CLASS
                || verdict == Verdict.SEALED
                || verdict == Verdict.INTERFACE
                || verdict == Verdict.ABSENT;
    }

    private static Verdict fromAccess(int access) {
        if ((access & Opcodes.ACC_INTERFACE) != 0) return Verdict.INTERFACE;
        if ((access & Opcodes.ACC_FINAL) != 0) return Verdict.FINAL_CLASS;
        return Verdict.CLASS;
    }

    /** Whether the indexed jar is expected to carry this name. It indexes only these two roots. */
    private static boolean isIndexedScope(String internalName) {
        return internalName.startsWith("net/minecraft/") || internalName.startsWith("com/mojang/");
    }
}
