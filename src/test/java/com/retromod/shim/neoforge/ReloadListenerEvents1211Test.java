/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #366: ProjectE subscribes to {@code AddReloadListenerEvent}, which NeoForge 1.21.11 already ships under its newer name. */
class ReloadListenerEvents1211Test {

    private static final String OLD_EVENT = "net/neoforged/neoforge/event/AddReloadListenerEvent";
    private static final String NEW_EVENT = "net/neoforged/neoforge/event/AddServerReloadListenersEvent";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On a 1.21.11 host, an old addListener call reaches the new event through the id-making shim")
    void oldListenerCallReachesTheNewEvent() {
        List<String> calls = callsAfterRegistering(Set.of(NEW_EVENT));
        assertEquals(List.of("com/retromod/shim/neoforge/embedded/ReloadListenerEventShim.addListener"), calls);
    }

    @Test
    @DisplayName("A host that still has the old event is left alone")
    void olderHostsAreLeftAlone() {
        assertEquals(List.of(OLD_EVENT + ".addListener"), callsAfterRegistering(Set.of(OLD_EVENT, NEW_EVENT)));
    }

    private static List<String> callsAfterRegistering(Set<String> hostClasses) {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        ClassLoader host = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                String internal = name.endsWith(".class") ? name.substring(0, name.length() - 6) : name;
                return hostClasses.contains(internal) ? new ByteArrayInputStream(emptyClass(internal)) : null;
            }
        };
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(host);
        try {
            new NeoForge_1_21_10_to_1_21_11().registerRedirects(transformer);
        } finally {
            thread.setContextClassLoader(previous);
        }
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(listener(), "mod/Reload.class")).accept(node, 0);
        List<String> calls = new ArrayList<>();
        node.methods.forEach(method -> method.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }));
        return calls;
    }

    /** {@code event.addListener(listener)}, as a 1.21.1 mod's reload handler writes it. */
    private static byte[] listener() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/Reload", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onReload",
                "(L" + OLD_EVENT + ";Lnet/minecraft/server/packs/resources/PreparableReloadListener;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_EVENT, "addListener", "(Lnet/minecraft/server/packs/resources/PreparableReloadListener;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
