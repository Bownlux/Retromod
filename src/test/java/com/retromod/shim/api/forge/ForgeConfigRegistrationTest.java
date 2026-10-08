/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.api.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.McReflect;
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
import org.objectweb.asm.util.CheckClassAdapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #267 and #283: a Forge 1.20.1 mod registers its config with
 * {@code ModLoadingContext.get().registerConfig(type, spec)}. NeoForge moved that method to
 * {@code ModContainer} and dropped Forge's generic {@code IConfigSpec}, so the mod failed in its
 * own constructor.
 */
class ForgeConfigRegistrationTest {

    private static final String FORGE_CONTEXT = "net/minecraftforge/fml/ModLoadingContext";
    private static final String FORGE_TYPE = "Lnet/minecraftforge/fml/config/ModConfig$Type;";
    private static final String FORGE_SPEC = "Lnet/minecraftforge/fml/config/IConfigSpec;";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        McReflect.setForceNeoForge(true);
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new ForgeConfigApiShim().registerRedirects(transformer);
    }

    @AfterEach
    void tearDown() {
        McReflect.setForceNeoForge(false);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("ModLoadingContext.registerConfig goes through the active container on NeoForge")
    void registerConfigUsesTheBridge() {
        MethodInsnNode call = registerCall(FORGE_TYPE + FORGE_SPEC);

        assertEquals(INVOKESTATIC, call.getOpcode(), "the context becomes the first argument");
        assertEquals(ForgeConfigApiShim.CONFIG_BRIDGE, call.owner);
        assertEquals("(Lnet/neoforged/fml/ModLoadingContext;Lnet/neoforged/fml/config/ModConfig$Type;"
                + "Lnet/neoforged/fml/config/IConfigSpec;)V", call.desc);
    }

    @Test
    @DisplayName("the overload with a file name keeps the file name")
    void registerConfigWithFileName() {
        MethodInsnNode call = registerCall(FORGE_TYPE + FORGE_SPEC + "Ljava/lang/String;");

        assertEquals(ForgeConfigApiShim.CONFIG_BRIDGE, call.owner);
        assertTrue(call.desc.endsWith("Lnet/neoforged/fml/config/IConfigSpec;Ljava/lang/String;)V"),
                call.desc);
    }

    @Test
    @DisplayName("the bridge forwards to ModContainer.registerConfig and is valid bytecode")
    void bridgeForwardsToTheContainer() {
        byte[] bridge = ForgeConfigApiShim.generateConfigBridge();
        new ClassReader(bridge).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);

        ClassNode cn = new ClassNode();
        new ClassReader(bridge).accept(cn, 0);
        assertEquals(2, cn.methods.size());
        for (MethodNode m : cn.methods) {
            boolean forwards = false;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c && c.owner.equals("net/neoforged/fml/ModContainer")
                        && c.name.equals("registerConfig")) {
                    forwards = true;
                }
            }
            assertTrue(forwards, m.name + m.desc + " must call ModContainer.registerConfig");
        }
    }

    /** Transforms {@code ModLoadingContext.get().registerConfig(null, null[, null])} and returns the call. */
    private MethodInsnNode registerCall(String params) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Configs", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, FORGE_CONTEXT, "get", "()L" + FORGE_CONTEXT + ";", false);
        int args = params.endsWith("String;") ? 3 : 2;
        for (int i = 0; i < args; i++) mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, FORGE_CONTEXT, "registerConfig", "(" + params + ")V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/Configs")).accept(out, 0);
        for (MethodNode m : out.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c && c.name.equals("registerConfig")) return c;
            }
        }
        return fail("no registerConfig call in the output");
    }
}
