/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Registers classes Retromod rebuilt for APIs a host removed (built from {@code src/legacyapi}) as synthetics. */
public final class RebuiltApiClasses {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    public static final String ROOT = "com/retromod/legacyapi/";

    private RebuiltApiClasses() {}

    /** Registers a rebuilt class and every rebuilt class it names, read from Retromod's own jar. */
    public static void registerTree(RetromodTransformer t, String root) {
        registerTree(t, root, null);
    }

    /** Registers the same tree rewritten by {@code names}, such as into a Fabric host's intermediary names, each under its new name. */
    public static void registerTree(RetromodTransformer t, String root, Remapper names) {
        ArrayDeque<String> work = new ArrayDeque<>(List.of(root));
        Set<String> seen = new HashSet<>();
        while (!work.isEmpty()) {
            String name = work.poll();
            String registered = names == null ? name : names.mapType(name);
            if (!seen.add(name) || t.getSyntheticClasses().containsKey(registered)) continue;
            byte[] bytes = read(name);
            if (bytes == null) {
                LOGGER.warn("Retromod's rebuilt class {} is missing from its jar, so it was not added to "
                        + "this mod. Report this line with the Retromod version.", name);
                continue;
            }
            // registerSyntheticClass renames Retromod's relocated Gson and SLF4J to the game's own.
            t.registerSyntheticClass(registered, names == null ? bytes : rename(bytes, names));
            for (String referenced : referencedClasses(bytes)) {
                if (!seen.contains(referenced)) work.add(referenced);
            }
        }
    }

    private static byte[] rename(byte[] bytes, Remapper names) {
        // Only names change, so the class keeps its own stack map frames.
        ClassWriter out = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(out, names), 0);
        return out.toByteArray();
    }

    private static byte[] read(String name) {
        try (InputStream in = RebuiltApiClasses.class.getClassLoader().getResourceAsStream(name + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** Every rebuilt class a class file names, through its descriptors, signatures, and nested classes too. */
    public static Set<String> referencedClasses(byte[] bytes) {
        Set<String> names = new HashSet<>();
        Remapper collector = new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName.startsWith(ROOT)) names.add(internalName);
                return internalName;
            }
        };
        // A ClassNode below the remapper, so method bodies are visited too.
        new ClassReader(bytes).accept(new ClassRemapper(new ClassNode(), collector), 0);
        return names;
    }
}
