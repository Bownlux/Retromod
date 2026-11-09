/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.testutil.RelocatedLibraryFixture;
import com.retromod.util.HostLibraryNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A rebuilt class read from the release jar names Retromod's relocated Gson; the copy registered for a mod names the game's. */
class RebuiltApiClassesGsonTest {

    private static final String NAME = RebuiltApiClasses.ROOT + "x/Reader";
    private static final String SHADED = RelocatedLibraryFixture.SHADED_GSON + "JsonObject";
    private static final String HOST = HostLibraryNames.GSON_PACKAGE + "/JsonObject";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Relocated Gson in a rebuilt class becomes the game's Gson when registerTree registers it")
    void relocatedGsonIsRenamedBack() {
        transformer.clearRedirectsForTesting();
        transformer.registerSyntheticClass(NAME, reader(NAME, SHADED));

        byte[] registered = transformer.getSyntheticClasses().get(NAME);
        assertEquals("(L" + HOST + ";)Ljava/lang/Object;", node(registered).methods.get(0).desc);
        assertFalse(RelocatedLibraryFixture.namesShadedLibrary(registered));
    }

    @Test
    @DisplayName("A rebuilt class without relocated Gson is registered as it was")
    void otherClassesAreUnchanged() {
        transformer.clearRedirectsForTesting();
        byte[] plain = reader(NAME, HOST);
        transformer.registerSyntheticClass(NAME, plain);

        assertSame(plain, transformer.getSyntheticClasses().get(NAME));
    }

    /** An abstract class with one {@code Object parse(json)} method taking {@code jsonType}. */
    private static byte[] reader(String name, String jsonType) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, name, null, "java/lang/Object", null);
        String desc = "(L" + jsonType + ";)Ljava/lang/Object;";
        MethodVisitor parse = cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "parse", desc, null, null);
        parse.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
