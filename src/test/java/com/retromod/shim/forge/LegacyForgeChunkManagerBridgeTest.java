/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #398: Epic Core API (Forge 1.20.1) sets a forced chunk callback in its mod constructor, and NeoForge has no
 * {@code ForgeChunkManager}, so the mod failed to construct. Its calls reach the rebuild in the id class of each host.
 */
class LegacyForgeChunkManagerBridgeTest {

    private static final String FORGE = LegacyForgeChunkManagerBridge.FORGE;
    private static final String REBUILT = LegacyForgeChunkManagerBridge.REBUILT;
    private static final String CALLBACK = FORGE + "$LoadingValidationCallback";
    private static final String HELPER = FORGE + "$TicketHelper";
    private static final String LEVEL = "Lnet/minecraft/server/level/ServerLevel;";

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("NeoForge 1.21.1 gets the rebuild in ResourceLocation and its ticket sets, 26.3 in Identifier and its own")
    void rebuildNamesTheHostsIdClass() {
        for (String host : List.of("1.21.1", "26.3")) {
            RetromodVersion.TARGET_MC_VERSION = host;
            transformer.clearRedirectsForTesting();
            LegacyForgeChunkManagerBridge.register(transformer);

            byte[] rebuilt = transformer.getSyntheticClasses().get(REBUILT);
            assertNotNull(rebuilt, host);
            String text = new String(rebuilt, StandardCharsets.ISO_8859_1);
            boolean identifierHost = host.equals("26.3");
            assertEquals(identifierHost, text.contains("net/minecraft/resources/Identifier"), host);
            assertEquals(!identifierHost, text.contains("net/minecraft/resources/ResourceLocation"), host);
            for (String nested : List.of("$LoadingValidationCallback", "$TicketHelper")) {
                assertTrue(transformer.getSyntheticClasses().containsKey(REBUILT + nested), host + nested);
            }
            // 1.21.5 renamed a ticket set's halves to normal and natural spawning chunks.
            String helper = new String(transformer.getSyntheticClasses().get(REBUILT + "$TicketHelper"), StandardCharsets.ISO_8859_1);
            assertEquals(identifierHost, helper.contains("naturalSpawning"), host);
            assertEquals(!identifierHost, helper.contains("nonTicking"), host);
            // NeoForge reads tickets Forge saved without a controller name back under <modid>:default.
            assertTrue(text.contains("default") && !text.contains("forge_chunk_manager"), host);
        }
    }

    @Test
    @DisplayName("a mod that names ForgeChunkManager declares its controller in its constructor; other mods are left alone")
    void modsThatForceChunksDeclareTheirController() {
        ModConstructorHooks.JarPlan plan = LegacyForgeChunkManagerBridge.plan(
                Map.of("mod/ChunkLoaderBlockEntity", blockEntityForcingChunks()), Map.of("mod/ChunkLoaders", "chunkloaders"));
        assertNotNull(plan);
        assertEquals(List.of(new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, REBUILT, "declare",
                "(Ljava/lang/String;)V", List.of("chunkloaders"))), plan.callsFor("mod/ChunkLoaders", "chunkloaders"));
        assertNull(LegacyForgeChunkManagerBridge.plan(Map.of("mod/Other", otherClass()), Map.of("mod/Other", "other")));
    }

    /** A chunk loader that forces a chunk at runtime and never sets a callback. */
    private static byte[] blockEntityForcingChunks() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/ChunkLoaderBlockEntity", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "load", "(" + LEVEL + "Lnet/minecraft/core/BlockPos;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("chunkloaders");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKESTATIC, FORGE, "forceChunk", "(" + LEVEL + "Ljava/lang/String;Lnet/minecraft/core/BlockPos;IIZZ)Z", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] otherClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Other", null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("the mod's callback lambda and its calls name the rebuild")
    void modCallsTheRebuild() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyForgeChunkManagerBridge.register(transformer);
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(modConstructor(), "mod/ForceLoading")).accept(out, 0);

        List<String> calls = new ArrayList<>();
        for (MethodNode method : out.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof InvokeDynamicInsnNode indy) calls.add("indy " + indy.desc);
            }
        }
        assertTrue(calls.contains(REBUILT + ".setForcedChunkLoadingCallback(Ljava/lang/String;L" + REBUILT
                + "$LoadingValidationCallback;)V"), calls.toString());
        assertTrue(calls.contains("indy ()L" + REBUILT + "$LoadingValidationCallback;"), calls.toString());
        assertTrue(calls.contains(REBUILT + ".forceChunk(" + LEVEL + "Ljava/lang/String;Ljava/util/UUID;IIZZ)Z"), calls.toString());
        assertFalse(calls.toString().contains(FORGE), calls.toString());
    }

    @Test
    @DisplayName("Forge 26.2, which removed the class, forces chunks through the level")
    void forge262UsesTheLevel() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyForgeChunkManagerBridge.registerForge(transformer);
        String vanilla = LegacyForgeChunkManagerBridge.VANILLA;
        assertEquals(vanilla, transformer.getClassRedirects().get(FORGE));
        assertEquals(vanilla + "$LoadingValidationCallback", transformer.getClassRedirects().get(CALLBACK));
        String text = new String(transformer.getSyntheticClasses().get(vanilla), StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("setChunkForced"), "forceChunk asks the level");

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyForgeChunkManagerBridge.registerForge(transformer);
        assertNull(transformer.getClassRedirects().get(FORGE), "Forge 1.21.1 still has its own");
    }

    @Test
    @DisplayName("a host before 1.21 keeps Forge's own class")
    void olderHostsAreLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        LegacyForgeChunkManagerBridge.register(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(REBUILT));
    }

    /** Epic Core API's shape: a callback lambda set from the constructor, and an owner-id force. */
    private static byte[] modConstructor() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/ForceLoading", null, "java/lang/Object", null);
        MethodVisitor register = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "()V", null, null);
        register.visitCode();
        register.visitLdcInsn("eca");
        String samDesc = "(" + LEVEL + "L" + HELPER + ";)V";
        register.visitInvokeDynamicInsn("validateTickets", "()L" + CALLBACK + ";",
                new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                Type.getMethodType(samDesc), new Handle(H_INVOKESTATIC, "mod/ForceLoading", "validate", samDesc, false),
                Type.getMethodType(samDesc));
        register.visitMethodInsn(INVOKESTATIC, FORGE, "setForcedChunkLoadingCallback",
                "(Ljava/lang/String;L" + CALLBACK + ";)V", false);
        register.visitInsn(ACONST_NULL);
        register.visitLdcInsn("eca");
        register.visitInsn(ACONST_NULL);
        register.visitInsn(ICONST_0);
        register.visitInsn(ICONST_0);
        register.visitInsn(ICONST_1);
        register.visitInsn(ICONST_1);
        register.visitMethodInsn(INVOKESTATIC, FORGE, "forceChunk", "(" + LEVEL + "Ljava/lang/String;Ljava/util/UUID;IIZZ)Z", false);
        register.visitInsn(POP);
        register.visitInsn(RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        MethodVisitor validate = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "validate", "(" + LEVEL + "L" + HELPER + ";)V", null, null);
        validate.visitCode();
        validate.visitInsn(RETURN);
        validate.visitMaxs(0, 0);
        validate.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
