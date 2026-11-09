/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** {@code ModLoader.get().runEventGenerator(...)}, as Wither Storm fires its own mod-bus event. */
class LegacyModLoaderBridgeTest {

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The instance call becomes a static call on NeoForge's ModLoader")
    void runEventGeneratorBecomesStatic() {
        assertTrue(LegacyModLoaderBridge.register(transformer, Set.of(LegacyModLoaderBridge.NEO)::contains));
        String forge = LegacyModLoaderBridge.FORGE;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Interactions", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "init", "(Ljava/util/function/Function;)V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, forge, "get", "()L" + forge + ";", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, forge, "runEventGenerator", "(Ljava/util/function/Function;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/Interactions")).accept(out, 0);
        List<String> calls = new ArrayList<>();
        out.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call.getOpcode() + " " + call.owner + "." + call.name);
        }));
        assertEquals(List.of(INVOKESTATIC + " " + LegacyModLoaderBridge.HELPER + ".get",
                        INVOKESTATIC + " " + LegacyModLoaderBridge.HELPER + ".runEventGenerator"), calls,
                "NeoForge has no ModLoader instance, so both calls must go through the static helper");
        assertDoesNotThrow(() -> new ClassReader(LegacyModLoaderBridge.generate()).accept(
                new CheckClassAdapter(new ClassWriter(0), false), 0));
    }

    @Test
    @DisplayName("GeckoLib's data generation check and a loading state check get NeoForge's answers, offline as well")
    void staticChecksGetNeoForgeAnswers() {
        String forge = LegacyModLoaderBridge.FORGE;
        // Offline neither ModLoader can be read; the Forge to NeoForge shim has already checked the target.
        assertTrue(LegacyModLoaderBridge.register(transformer, Set.<String>of()::contains));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Cache", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerReloadListener", "()Z", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, forge, "isDataGenRunning", "()Z", false);
        mv.visitMethodInsn(INVOKESTATIC, forge, "isLoadingStateValid", "()Z", false);
        mv.visitInsn(IAND);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/Cache")).accept(out, 0);
        List<String> calls = new ArrayList<>();
        out.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }));
        assertEquals(List.of(LegacyModLoaderBridge.DATAGEN + ".isRunningDataGen()Z",
                LegacyModLoaderBridge.HELPER + ".isLoadingStateValid()Z"), calls);
    }

    @Test
    @DisplayName("a valid loading state is one without NeoForge loading errors")
    void loadingStateIsValidWithoutErrors() throws Exception {
        for (boolean errors : new boolean[] {false, true}) {
            ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes;
                    if (name.equals(LegacyModLoaderBridge.HELPER.replace('/', '.'))) {
                        bytes = LegacyModLoaderBridge.generate();
                    } else if (name.equals(LegacyModLoaderBridge.NEO.replace('/', '.'))) {
                        bytes = neoModLoader(errors);
                    } else if (name.startsWith("net.neoforged.")) {
                        // The helper's other methods name NeoForge's event type.
                        bytes = emptyClass(name.replace('.', '/'));
                    } else {
                        throw new ClassNotFoundException(name);
                    }
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            Object valid = loader.loadClass(LegacyModLoaderBridge.HELPER.replace('/', '.'))
                    .getMethod("isLoadingStateValid").invoke(null);
            assertEquals(!errors, valid, "hasErrors() was " + errors);
        }
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** NeoForge's ModLoader as far as the helper reads it: a static hasErrors(). */
    private static byte[] neoModLoader(boolean errors) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, LegacyModLoaderBridge.NEO, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "hasErrors", "()Z", null, null);
        mv.visitCode();
        mv.visitInsn(errors ? ICONST_1 : ICONST_0);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("NeoForge 1.20.1 still runs Forge's ModLoader, so nothing is registered there")
    void forgeForkKeepsItsModLoader() {
        assertFalse(LegacyModLoaderBridge.register(transformer,
                Set.of(LegacyModLoaderBridge.FORGE, LegacyModLoaderBridge.NEO)::contains));
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyModLoaderBridge.HELPER));
    }
}
