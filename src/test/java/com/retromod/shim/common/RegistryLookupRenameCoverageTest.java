/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.forge.Forge_1_21_1_to_1_21_2;
import com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The 1.21.2 registry lookup renames reach Forge and NeoForge mods, on every receiver type javac names. */
class RegistryLookupRenameCoverageTest {

    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String LOOKUP_DESC = "(" + KEY + ")Lnet/minecraft/core/Registry;";
    private static final String HOLDER_DESC = "(" + KEY + ")Lnet/minecraft/core/Holder$Reference;";
    private static final String MODERN_LOOKUP_DESC = "(" + KEY + ")Lnet/minecraft/core/HolderLookup$RegistryLookup;";

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("the Forge and NeoForge 1.21.2 shims rename registryOrThrow and getHolderOrThrow")
    void loaderShimsRenameTheLookups() {
        for (VersionShim shim : List.of(new NeoForge_1_21_1_to_1_21_2(), new Forge_1_21_1_to_1_21_2())) {
            transformer.clearRedirectsForTesting();
            shim.registerRedirects(transformer);
            List<String> out = trace(transformer.transformClass(registryCalls(), "test/mod/Registries"));
            String loader = shim.getShimName();

            assertEquals(List.of(
                    "INVOKEINTERFACE net/minecraft/core/RegistryAccess.lookupOrThrow" + LOOKUP_DESC,
                    "INVOKEINTERFACE net/minecraft/core/RegistryAccess$Frozen.lookupOrThrow" + LOOKUP_DESC,
                    "INVOKEINTERFACE net/minecraft/core/Registry.getOrThrow" + HOLDER_DESC,
                    "INVOKEINTERFACE net/minecraft/core/DefaultedRegistry.getOrThrow" + HOLDER_DESC,
                    "INVOKEINTERFACE net/minecraft/core/RegistryAccess.lookupOrThrow" + MODERN_LOOKUP_DESC),
                    out, loader + ": a 1.21.1 lookup fails with NoSuchMethodError from 1.21.2");
        }
    }

    @Test
    @DisplayName("the common 26.1 set carries registryOrThrow for offline 26.x transforms too")
    void common26SetRenamesTheLookup() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        Common_1_21_11_to_26_1_ClassMoves.register(transformer);
        List<String> out = trace(transformer.transformClass(registryCalls(), "test/mod/Registries"));

        assertTrue(out.contains("INVOKEINTERFACE net/minecraft/core/RegistryAccess.lookupOrThrow" + LOOKUP_DESC),
                out.toString());
        assertTrue(out.stream().noneMatch(s -> s.contains("registryOrThrow")), out.toString());
    }

    /** A 1.21.1 mod's registry lookups on each owner javac can name, then one current call. */
    private static byte[] registryCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Registries", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "lookups",
                "(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/core/RegistryAccess$Frozen;"
                        + "Lnet/minecraft/core/Registry;Lnet/minecraft/core/DefaultedRegistry;" + KEY + ")V",
                null, null);
        mv.visitCode();
        call(mv, 0, "net/minecraft/core/RegistryAccess", "registryOrThrow", LOOKUP_DESC);
        call(mv, 1, "net/minecraft/core/RegistryAccess$Frozen", "registryOrThrow", LOOKUP_DESC);
        call(mv, 2, "net/minecraft/core/Registry", "getHolderOrThrow", HOLDER_DESC);
        call(mv, 3, "net/minecraft/core/DefaultedRegistry", "getHolderOrThrow", HOLDER_DESC);
        call(mv, 0, "net/minecraft/core/RegistryAccess", "lookupOrThrow", MODERN_LOOKUP_DESC);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void call(MethodVisitor mv, int receiver, String owner, String name, String desc) {
        mv.visitVarInsn(ALOAD, receiver);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, owner, name, desc, true);
        mv.visitInsn(POP);
    }

    private static List<String> trace(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) {
                    String op = c.getOpcode() == INVOKEINTERFACE ? "INVOKEINTERFACE" : "op" + c.getOpcode();
                    out.add(op + " " + c.owner + "." + c.name + c.desc);
                }
            }
        }
        return out;
    }
}
