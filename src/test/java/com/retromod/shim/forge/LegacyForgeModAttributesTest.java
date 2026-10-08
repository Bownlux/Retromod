/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A Forge 1.20.1 mod's {@code ForgeMod} attribute reads against NeoForge 1.21. */
class LegacyForgeModAttributesTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void forgeModReachBecomesAHolderForTheVanillaAttribute() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        String id = "net/minecraft/resources/ResourceLocation";
        // The Forge bridge renames RegistryObject too, so the field's descriptor no longer matches as written.
        transformer.registerClassRedirect("net/minecraftforge/registries/RegistryObject", LegacyForgeModAttributes.HOLDER);
        LegacyForgeModAttributes.registerRedirects(transformer, id);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Reach", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reach", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, LegacyForgeModAttributes.FORGE_MOD, "ENTITY_REACH",
                LegacyForgeModAttributes.REGISTRY_OBJECT);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        List<String> calls = calls(transformer.transformClass(cw.toByteArray(), "test/Reach.class"), "reach");

        assertTrue(calls.contains(LegacyForgeModAttributes.HELPER + ".ENTITY_REACH()L"
                + LegacyForgeModAttributes.HOLDER + ";"), "the reach read must use the vanilla attribute: " + calls);
        ClassNode helper = node(LegacyForgeModAttributes.generateHelper(id));
        for (MethodNode method : helper.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
        }
    }

    private static List<String> calls(byte[] bytes, String methodName) {
        List<String> calls = new ArrayList<>();
        MethodNode method = node(bytes).methods.stream().filter(m -> m.name.equals(methodName))
                .findFirst().orElseThrow();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
