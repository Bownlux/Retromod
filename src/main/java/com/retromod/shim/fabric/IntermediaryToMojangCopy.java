/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Copies a class generated for an intermediary-named bridge into Mojang names, so a bridge for a
 * Forge or NeoForge host reuses the Fabric bridge's code instead of duplicating it.
 *
 * <p>Each table is explicit and keyed by owner where a name is ambiguous. Method names the
 * runtime helpers look up reflectively are string constants, so they are translated through the
 * {@code strings} table, which a caller can vary per class when the same intermediary name means
 * two different Mojang methods.
 */
final class IntermediaryToMojangCopy {

    private IntermediaryToMojangCopy() {}

    /**
     * @param classes internal class names
     * @param members {@code owner.name} keys, with the intermediary owner, for methods and fields
     * @param strings string constants, such as reflective method and class names
     */
    static byte[] translate(byte[] generated, Map<String, String> classes, Map<String, String> members,
            Map<String, String> strings) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(generated).accept(new ClassRemapper(writer, new Remapper() {
            @Override
            public String map(String internalName) {
                return classes.getOrDefault(internalName, internalName);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                return members.getOrDefault(owner + "." + name, name);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                return members.getOrDefault(owner + "." + name, name);
            }

            @Override
            public Object mapValue(Object value) {
                if (value instanceof String text) return strings.getOrDefault(text, text);
                return super.mapValue(value);
            }
        }), 0);
        return writer.toByteArray();
    }

    /** A compiled runtime helper's bytes, translated the same way. */
    static byte[] translateResource(Class<?> helper, Map<String, String> classes, Map<String, String> members,
            Map<String, String> strings) {
        String resource = helper.getName().replace('.', '/') + ".class";
        try (InputStream in = helper.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Retromod is missing " + resource);
            return translate(in.readAllBytes(), classes, members, strings);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }
}
