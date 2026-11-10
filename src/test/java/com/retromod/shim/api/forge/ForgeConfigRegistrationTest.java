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

    @Test
    @DisplayName("#394: ForgeConfigSpec.setConfig hands the config to the spec through the rebuilt helper")
    void setConfigGoesToTheHelper() {
        String config = "Lcom/electronwill/nightconfig/core/CommentedConfig;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/ClientConfig", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "load",
                "(Lnet/minecraftforge/common/ForgeConfigSpec;" + config + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraftforge/common/ForgeConfigSpec", "setConfig", "(" + config + ")V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/ClientConfig")).accept(out, 0);
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : out.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode c && c.name.equals("setConfig")) call = c;
        }
        assertNotNull(call, "the setConfig call must remain, routed to the helper");
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals("com/retromod/legacyapi/forge/common/LegacyConfigSpecs", call.owner);
        assertEquals("(Lnet/neoforged/neoforge/common/ModConfigSpec;" + config + ")V", call.desc);
        assertTrue(transformer.getSyntheticClasses().containsKey(call.owner), "the helper travels with the mod");
    }

    @Test
    @DisplayName("#394: a config value read goes through the helper that answers the default before the config loads")
    void valueReadAnswersDefaultBeforeLoad() {
        String value = "net/minecraftforge/common/ForgeConfigSpec$BooleanValue";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Trades", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "(L" + value + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, value, "get", "()Ljava/lang/Object;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/Trades")).accept(out, 0);
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : out.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode c && c.name.equals("get")) call = c;
        }
        assertNotNull(call);
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals("com/retromod/legacyapi/forge/common/LegacyConfigSpecs", call.owner);
        assertEquals("(Lnet/neoforged/neoforge/common/ModConfigSpec$ConfigValue;)Ljava/lang/Object;", call.desc);
    }

    @Test
    @DisplayName("On NeoForge 26.3 a Forge mod's COMMON config reaches LOCAL and keeps modid-common.toml")
    void renamedTypesKeepTheOldFileName() {
        String savedTarget = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        try {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.3";
            transformer.clearRedirectsForTesting();
            new ForgeConfigApiShim().registerRedirects(transformer);

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "test/mod/Types", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "common", "()Ljava/lang/Object;", null, null);
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, "net/minecraftforge/fml/config/ModConfig$Type", "COMMON", FORGE_TYPE);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            ClassNode out = new ClassNode();
            new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/Types")).accept(out, 0);
            String read = null;
            for (AbstractInsnNode insn : out.methods.get(0).instructions) {
                if (insn instanceof org.objectweb.asm.tree.FieldInsnNode f) read = f.owner + "." + f.name;
            }
            assertEquals("net/neoforged/fml/config/ModConfig$Type.LOCAL", read);

            ClassNode bridge = new ClassNode();
            new ClassReader(transformer.getSyntheticClasses().get(ForgeConfigApiShim.CONFIG_BRIDGE)).accept(bridge, 0);
            MethodNode noFileName = bridge.methods.stream().filter(m -> m.desc.endsWith("IConfigSpec;)V")).findFirst().orElseThrow();
            boolean callsHelper = false;
            for (AbstractInsnNode insn : noFileName.instructions) {
                if (insn instanceof MethodInsnNode c && c.owner.endsWith("LegacyModConfigs")) callsHelper = true;
            }
            assertTrue(callsHelper, "a config with no file name goes through the old-name helper");
            assertTrue(transformer.getSyntheticClasses().containsKey(
                    com.retromod.shim.neoforge.LegacyModConfigTypeBridge.HELPER), "the helper travels with the bridge");
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = savedTarget;
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
