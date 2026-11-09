/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A Forge 1.20.1 mod reads FMLEnvironment's fields, as GeckoLib's example registration checks production. */
class LegacyFmlEnvironmentBridgeTest {

    private static final String FORGE = "net/minecraftforge/fml/loading/FMLEnvironment";
    private static final String NEO = LegacyFmlEnvironmentBridge.NEO;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void setUp() {
        transformer.clearRedirectsForTesting();
        McReflect.setForceNeoForge(true);
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("offline for NeoForge 26.3, the Forge to NeoForge shim reads dist and production through their getters")
    void gettersOnNewNeoForge() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        new Forge_1_20_to_NeoForge_1_21().registerRedirects(transformer);

        assertEquals(List.of("INVOKESTATIC " + NEO + ".isProduction()Z",
                "INVOKESTATIC " + NEO + ".getDist()Lnet/neoforged/api/distmarker/Dist;",
                "INVOKESTATIC " + LegacyFmlEnvironmentBridge.HELPER + ".naming()Ljava/lang/String;"),
                reads(transformer.transformClass(environmentReader(), "test/Environment")));
    }

    @Test
    @DisplayName("NeoForge 1.21.1 keeps the dist and production fields, and only naming needs an answer")
    void fieldsStayOnOlderNeoForge() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        new Forge_1_20_to_NeoForge_1_21().registerRedirects(transformer);

        assertEquals(List.of("GETSTATIC " + NEO + ".production:Z",
                "GETSTATIC " + NEO + ".dist:Lnet/neoforged/api/distmarker/Dist;",
                "INVOKESTATIC " + LegacyFmlEnvironmentBridge.HELPER + ".naming()Ljava/lang/String;"),
                reads(transformer.transformClass(environmentReader(), "test/Environment")));
    }

    @Test
    @DisplayName("a readable host decides: fields it still has are left as they are")
    void readableHostDecides() {
        ClassNode withFields = host(true);
        LegacyFmlEnvironmentBridge.register(transformer, withFields);
        transformer.registerClassRedirect(FORGE, NEO);
        transformer.registerClassRedirect("net/minecraftforge/api/distmarker/Dist", "net/neoforged/api/distmarker/Dist");
        List<String> reads = reads(transformer.transformClass(environmentReader(), "test/Environment"));
        assertEquals("GETSTATIC " + NEO + ".production:Z", reads.get(0));

        transformer.clearRedirectsForTesting();
        LegacyFmlEnvironmentBridge.register(transformer, host(false));
        transformer.registerClassRedirect(FORGE, NEO);
        transformer.registerClassRedirect("net/minecraftforge/api/distmarker/Dist", "net/neoforged/api/distmarker/Dist");
        assertEquals("INVOKESTATIC " + NEO + ".isProduction()Z",
                reads(transformer.transformClass(environmentReader(), "test/Environment")).get(0));
    }

    @Test
    @DisplayName("FMLLoader's statics are bridged only where NeoForge made FMLLoader an instance")
    void loaderStaticsFollowTheHost() {
        ClassNode instanceLoader = new ClassNode();
        instanceLoader.methods.add(new MethodNode(ACC_PUBLIC | ACC_STATIC, "getCurrent",
                "()L" + LegacyFmlEnvironmentBridge.LOADER + ";", null, null));
        assertTrue(LegacyFmlEnvironmentBridge.loaderIsInstance(instanceLoader), "NeoForge 1.21.9 and newer");
        ClassNode staticLoader = new ClassNode();
        staticLoader.methods.add(new MethodNode(ACC_PUBLIC | ACC_STATIC, "getDist",
                "()Lnet/neoforged/api/distmarker/Dist;", null, null));
        assertFalse(LegacyFmlEnvironmentBridge.loaderIsInstance(staticLoader), "NeoForge 1.21.1 keeps its statics");
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertFalse(LegacyFmlEnvironmentBridge.loaderIsInstance(null), "offline for 1.21.1");
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(LegacyFmlEnvironmentBridge.loaderIsInstance(null), "offline for 26.3");
    }

    @Test
    @DisplayName("naming answers mcp, the name Forge gave the Mojang names NeoForge runs")
    void namingAnswersMcp() throws Exception {
        byte[] helper = LegacyFmlEnvironmentBridge.generateHelper();
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals(LegacyFmlEnvironmentBridge.HELPER.replace('/', '.'))) throw new ClassNotFoundException(name);
                return defineClass(name, helper, 0, helper.length);
            }
        };
        assertEquals("mcp", loader.loadClass(LegacyFmlEnvironmentBridge.HELPER.replace('/', '.'))
                .getMethod("naming").invoke(null));
    }

    /** NeoForge's FMLEnvironment with the 1.21.1 fields, or with the 1.21.9 getters only. */
    private static ClassNode host(boolean fields) {
        ClassNode node = new ClassNode();
        node.name = NEO;
        if (fields) {
            node.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "dist", "Lnet/neoforged/api/distmarker/Dist;",
                    null, null));
            node.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "production", "Z", null, null));
        } else {
            node.methods.add(new MethodNode(ACC_PUBLIC | ACC_STATIC, "getDist", "()Lnet/neoforged/api/distmarker/Dist;",
                    null, null));
            node.methods.add(new MethodNode(ACC_PUBLIC | ACC_STATIC, "isProduction", "()Z", null, null));
        }
        return node;
    }

    /** Reads production, dist and naming, as a Forge 1.20.1 mod does. */
    private static byte[] environmentReader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Environment", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, FORGE, "production", "Z");
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, FORGE, "dist", "Lnet/minecraftforge/api/distmarker/Dist;");
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, FORGE, "naming", "Ljava/lang/String;");
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Each field read or call in {@code read()}, as {@code OPCODE owner.name desc}. */
    private static List<String> reads(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> reads = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals("read")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode field) {
                    reads.add("GETSTATIC " + field.owner + "." + field.name + ":" + field.desc);
                } else if (insn instanceof MethodInsnNode call) {
                    reads.add("INVOKESTATIC " + call.owner + "." + call.name + call.desc);
                }
            }
        }
        return reads;
    }
}
