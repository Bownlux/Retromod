/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.nio.charset.StandardCharsets;

/** Renames Retromod's relocated Gson and SLF4J in a class copied into a mod back to the game's own. */
public final class ShadedLibraryNames {

    // The release jar moves Gson and SLF4J under com/retromod/shaded, in the classes Retromod copies into
    // mods too, and a mod can only see the game's copies. Built at run time, like HostLibraryNames, so no
    // constant here is one the relocation could rewrite.
    private static final String SHADED_GSON = String.join("/", "com", "retromod", "shaded", "gson") + "/";
    private static final String SHADED_SLF4J = String.join("/", "com", "retromod", "shaded", "slf4j") + "/";

    private static final byte[] GSON_BYTES = SHADED_GSON.getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] SLF4J_BYTES = SHADED_SLF4J.getBytes(StandardCharsets.ISO_8859_1);

    private static final Remapper HOST_NAMES = new Remapper() {
        @Override
        public String map(String internalName) {
            if (internalName.startsWith(SHADED_GSON)) {
                return HostLibraryNames.GSON_PACKAGE + "/" + internalName.substring(SHADED_GSON.length());
            }
            if (internalName.startsWith(SHADED_SLF4J)) {
                return HostLibraryNames.SLF4J_PACKAGE + "/" + internalName.substring(SHADED_SLF4J.length());
            }
            return internalName;
        }
    };

    private ShadedLibraryNames() {}

    /** The class naming the game's Gson and SLF4J, or the same array when it names neither relocated copy. */
    public static byte[] toHostNames(byte[] classBytes) {
        if (classBytes == null || !(contains(classBytes, GSON_BYTES) || contains(classBytes, SLF4J_BYTES))) {
            return classBytes;
        }
        // Only names change, so the class keeps its own stack map frames.
        ClassWriter out = new ClassWriter(0);
        new ClassReader(classBytes).accept(new ClassRemapper(out, HOST_NAMES), 0);
        return out.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
