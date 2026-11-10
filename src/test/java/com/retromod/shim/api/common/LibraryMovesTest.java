/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticLibraries;
import com.retromod.loaderapi.LoaderApiFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A library rename applies only where the library the mods run with dropped the old name. */
class LibraryMovesTest {

    private static final String OLD_CLASS = "lib/api/OldWidget";
    private static final String NEW_CLASS = "lib/api/NewWidget";
    private static final Map<String, String> MOVES = Map.of(OLD_CLASS, NEW_CLASS);
    private static final LibraryMoves.Table TABLE = new LibraryMoves.Table("1.21", "1.21");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() {
        transformer.clearLinkedLibraries();
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a library that still has the old class keeps its users on it, whatever the version table says")
    void installedOldClassKeepsTheName() {
        Set<String> moved = LibraryMoves.register(transformer, MOVES, TABLE, "26.3", Set.of(OLD_CLASS, NEW_CLASS)::contains);
        assertTrue(moved.isEmpty());
        assertFalse(transformer.getClassRedirects().containsKey(OLD_CLASS), "EMI 1.1.24 still ships EmiTexture, so it stays");
    }

    @Test
    @DisplayName("a library that has only the new class takes the move for every mod")
    void installedNewClassMoves() {
        LibraryMoves.register(transformer, MOVES, TABLE, "1.20.1", Set.of(NEW_CLASS)::contains);
        transformer.setModSourceVersion("1.21.1");
        assertEquals(NEW_CLASS, typeAfterTransform(), "the installed library decides, not the mod's version");
    }

    @Test
    @DisplayName("with no library readable, the proven table moves only mods built before its version")
    void tableMovesOlderModsOnly() {
        LibraryMoves.register(transformer, MOVES, TABLE, "26.3", name -> false);
        transformer.setModSourceVersion("1.20.1");
        assertEquals(NEW_CLASS, typeAfterTransform());
        transformer.setModSourceVersion("1.21.1");
        assertEquals(OLD_CLASS, typeAfterTransform(), "a mod built for the renamed library never names the old class");
    }

    @Test
    @DisplayName("with no library readable and a host the table does not cover, nothing moves")
    void uncoveredHostKeepsTheName() {
        assertTrue(LibraryMoves.register(transformer, MOVES, TABLE, "1.20.1", name -> false).isEmpty());
        assertTrue(LibraryMoves.register(transformer, MOVES, null, "26.3", name -> false).isEmpty(),
                "a library with no proven table is never moved blind");
    }

    @Test
    @DisplayName("a mod translated with a library that still defines the old class keeps it")
    void linkedLibraryKeepsTheName() throws Exception {
        LibraryMoves.register(transformer, MOVES, TABLE, "26.3", name -> false);
        Path library = dir.resolve("old-lib.jar");
        Files.write(library, LoaderApiFixtures.jar(Map.of(OLD_CLASS + ".class", emptyClass(OLD_CLASS))));
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(library);
        // As in a batch: the mod's version first, then the libraries translated before it.
        transformer.setModSourceVersion("1.20.1");
        transformer.setLinkedLibraries(libraries);
        assertEquals(OLD_CLASS, typeAfterTransform(), "the batch's own copy of the library still has the class");
    }

    @Test
    @DisplayName("a member redirect registers only where the installed class lacks the member")
    void memberRedirectFollowsTheInstalledClass() {
        ClassNode keeps = classWithMethod("lib/api/Helper", "find", "()Ljava/lang/Object;");
        assertFalse(LibraryMoves.registerMemberRedirect(transformer, "lib/api/Helper", "find", "()Ljava/lang/Object;",
                "lib/Bridge", "find", "()Ljava/lang/Object;", TABLE, "26.3", name -> keeps));
        ClassNode dropped = classWithMethod("lib/api/Helper", "other", "()V");
        assertTrue(LibraryMoves.registerMemberRedirect(transformer, "lib/api/Helper", "find", "()Ljava/lang/Object;",
                "lib/Bridge", "find", "()Ljava/lang/Object;", TABLE, "1.20.1", name -> dropped));
        transformer.clearRedirectsForTesting();
        assertFalse(LibraryMoves.registerMemberRedirect(transformer, "lib/api/Helper", "find", "()Ljava/lang/Object;",
                "lib/Bridge", "find", "()Ljava/lang/Object;", TABLE, "1.20.1", name -> null),
                "no class readable and a host the table does not cover");
        assertTrue(LibraryMoves.registerMemberRedirect(transformer, "lib/api/Helper", "find", "()Ljava/lang/Object;",
                "lib/Bridge", "find", "()Ljava/lang/Object;", TABLE, "26.3", name -> null));
    }

    /** The type a mod's {@code new OldWidget()} names after the transform. */
    private String typeAfterTransform() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/mod/Uses", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, OLD_CLASS);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/Uses")).accept(out, 0);
        MethodNode make = out.methods.stream().filter(m -> m.name.equals("make")).findFirst().orElseThrow();
        for (var insn : make.instructions) {
            if (insn instanceof TypeInsnNode type) return type.desc;
        }
        return fail("no NEW left in make()");
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode classWithMethod(String owner, String name, String desc) {
        ClassNode node = new ClassNode();
        node.name = owner;
        node.methods.add(new MethodNode(ACC_PUBLIC | ACC_STATIC, name, desc, null, null));
        return node;
    }
}
