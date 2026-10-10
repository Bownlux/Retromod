/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Porting Lib's {@code DeferredHolder} implements {@code Holder}, which 26.2 sealed to its own
 * {@code Direct} and {@code Reference}. On 26.3 the class failed to load, so Sophisticated
 * Backpacks, which worked on 26.1.2, lost its entrypoint and every recipe naming its items.
 */
class SealedSupertypeUnsealerTest {

    private static final String HOLDER = "net/minecraft/core/Holder";

    /** A host whose Holder permits only its own two classes. */
    private static final Function<String, ClassNode> SEALED_HOST = name -> {
        if (!name.equals(HOLDER)) return null;
        ClassNode holder = new ClassNode();
        holder.name = HOLDER;
        holder.permittedSubclasses = List.of(HOLDER + "$Direct", HOLDER + "$Reference");
        return holder;
    };

    @Test
    @DisplayName("a jar without a widener gets one that unseals Holder, named in fabric.mod.json")
    void createsWidener(@TempDir Path jar) throws Exception {
        writeMod(jar, "{\"schemaVersion\":1,\"id\":\"porting_lib_lazy_registration\"}");
        writeClass(jar, "io/example/DeferredHolder", HOLDER);

        assertEquals(Set.of(HOLDER), SealedSupertypeUnsealer.unseal(jar, SEALED_HOST));
        assertEquals("accessWidener\tv2\tofficial\nextendable\tclass\t" + HOLDER + "\n",
                Files.readString(jar.resolve(SealedSupertypeUnsealer.CREATED_WIDENER)));
        assertTrue(Files.readString(jar.resolve("fabric.mod.json"))
                .contains("\"accessWidener\": \"" + SealedSupertypeUnsealer.CREATED_WIDENER + "\""));
    }

    @Test
    @DisplayName("an official-namespace widener the jar already has gains the entry")
    void appendsToExistingWidener(@TempDir Path jar) throws Exception {
        writeMod(jar, "{\"schemaVersion\":1,\"id\":\"lib\",\"accessWidener\":\"lib.accesswidener\"}");
        Files.writeString(jar.resolve("lib.accesswidener"), "accessWidener\tv2\tofficial\naccessible\tclass\tnet/minecraft/Foo\n");
        writeClass(jar, "io/example/DeferredHolder", HOLDER);

        SealedSupertypeUnsealer.unseal(jar, SEALED_HOST);
        assertEquals("accessWidener\tv2\tofficial\naccessible\tclass\tnet/minecraft/Foo\nextendable\tclass\t" + HOLDER + "\n",
                Files.readString(jar.resolve("lib.accesswidener")));
        assertFalse(Files.exists(jar.resolve(SealedSupertypeUnsealer.CREATED_WIDENER)));
    }

    @Test
    @DisplayName("a widener still in the intermediary namespace is left alone")
    void leavesIntermediaryWidener(@TempDir Path jar) throws Exception {
        writeMod(jar, "{\"schemaVersion\":1,\"id\":\"lib\",\"accessWidener\":\"lib.accesswidener\"}");
        Files.writeString(jar.resolve("lib.accesswidener"), "accessWidener\tv2\tintermediary\n");
        writeClass(jar, "io/example/DeferredHolder", HOLDER);

        assertTrue(SealedSupertypeUnsealer.unseal(jar, SEALED_HOST).isEmpty());
        assertEquals("accessWidener\tv2\tintermediary\n", Files.readString(jar.resolve("lib.accesswidener")));
    }

    @Test
    @DisplayName("an unsealed host type, or a permitted class, needs nothing")
    void nothingToUnseal(@TempDir Path jar) throws Exception {
        writeMod(jar, "{\"schemaVersion\":1,\"id\":\"lib\"}");
        writeClass(jar, "io/example/DeferredHolder", HOLDER);
        assertTrue(SealedSupertypeUnsealer.unseal(jar, name -> null).isEmpty());

        writeClass(jar, "io/example/DeferredHolder", "java/lang/Runnable");
        assertTrue(SealedSupertypeUnsealer.unseal(jar, SEALED_HOST).isEmpty());
        assertFalse(Files.exists(jar.resolve(SealedSupertypeUnsealer.CREATED_WIDENER)));
    }

    private static void writeMod(Path jar, String json) throws Exception {
        Files.writeString(jar.resolve("fabric.mod.json"), json);
    }

    private static void writeClass(Path jar, String name, String iface) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, name, null, "java/lang/Object", new String[]{iface});
        cw.visitEnd();
        Path file = jar.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, cw.toByteArray());
    }
}
