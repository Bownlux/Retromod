/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;

/** Checks that the loader's ASM can run Retromod and read the running Minecraft's classes. */
public final class HostAsmSupport {

    // Retromod calls nothing newer than ASM 9.5 (HostAsmFloorTest), the first ASM that reads Java 21 class files.
    static final int FLOOR_CLASS_VERSION = 65;
    private static final int JAVA_25_CLASS_VERSION = 69;
    // Minecraft's server main class keeps its name in every mapping, and client jars carry it too.
    private static final String MINECRAFT_MAIN = "net/minecraft/server/Main.class";

    private HostAsmSupport() {}

    /** Returns what Retromod found wrong with the loader's ASM for this Minecraft, or null when nothing is. */
    public static String problem(ClassLoader gameLoader, String minecraftVersion) {
        String asm = "ASM " + asmVersion();
        if (!reads(FLOOR_CLASS_VERSION)) {
            return "Retromod uses ASM 9.5 or newer, and this loader provides " + asm + ".";
        }
        int needed = Math.max(FLOOR_CLASS_VERSION, minecraftClassVersion(gameLoader, minecraftVersion));
        if (!reads(needed)) {
            return "Minecraft " + minecraftVersion + " uses Java " + javaVersion(needed) + " class files, and " + asm
                    + " from this loader reads Java " + javaVersion(newestReadable(needed)) + " class files at most.";
        }
        return null;
    }

    /** Answers whether the loader's ClassReader accepts class files of this major version. */
    static boolean reads(int classVersion) {
        // The smallest input ClassReader parses: magic, version, an empty constant pool, and the class header.
        byte[] header = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0,
                (byte) (classVersion >> 8), (byte) classVersion, 0, 1, 0, 0x21, 0, 0, 0, 0, 0, 0};
        try {
            new ClassReader(header);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The class-file version of Minecraft's main class, or the one its release implies when it cannot be read. */
    static int minecraftClassVersion(ClassLoader gameLoader, String minecraftVersion) {
        if (gameLoader != null) {
            // Read the raw header, so neither ASM nor class loading is involved.
            try (InputStream in = gameLoader.getResourceAsStream(MINECRAFT_MAIN)) {
                byte[] header = in == null ? new byte[0] : in.readNBytes(8);
                if (header.length == 8 && (header[0] & 0xFF) == 0xCA && (header[1] & 0xFF) == 0xFE) {
                    return ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                }
            } catch (IOException e) {
                // Fall back to the release number below.
            }
        }
        return releaseNumber(minecraftVersion) >= 26 ? JAVA_25_CLASS_VERSION : FLOOR_CLASS_VERSION;
    }

    private static int newestReadable(int unreadable) {
        int version = unreadable - 1;
        while (version > FLOOR_CLASS_VERSION && !reads(version)) version--;
        return version;
    }

    private static int javaVersion(int classVersion) {
        return classVersion - 44;
    }

    private static int releaseNumber(String minecraftVersion) {
        String digits = minecraftVersion == null ? "" : minecraftVersion.trim().replaceFirst("\\D.*$", "");
        return digits.isEmpty() || digits.length() > 4 ? 0 : Integer.parseInt(digits);
    }

    private static String asmVersion() {
        // The jar manifest names the release; a loader that repackages ASM may drop it.
        Package asm = ClassReader.class.getPackage();
        String version = asm == null ? null : asm.getImplementationVersion();
        return version != null ? version : "(release not named in its manifest)";
    }
}
