/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.forge.ForgeCorePolyfill;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/** Forge keeps MinecraftForge, ICapabilityProvider and LazyOptional, so a Forge target never replaces them. */
class ForgeCorePolyfillTargetLoaderTest {

    private static final String PROVIDER = "net/minecraftforge/common/capabilities/ICapabilityProvider";
    private static final String LAZY = "net/minecraftforge/common/util/LazyOptional";
    private static final String FORGE = "net/minecraftforge/common/MinecraftForge";
    private static final String CAPABILITY = "net/minecraftforge/common/capabilities/Capability";
    private static final String ATTACH = "net/minecraftforge/event/AttachCapabilitiesEvent";
    private static final String ADD_CAPABILITY =
            "(Lnet/minecraft/resources/Identifier;L" + PROVIDER + ";)V";

    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @AfterEach
    void restore() {
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A Forge target offline, where no Forge class can be found, registers no stand-in")
    void forgeTargetKeepsTheHostClasses() {
        Map<String, String> redirects = redirectsFor(true, false);
        for (String kept : List.of(PROVIDER, LAZY, FORGE)) {
            assertFalse(redirects.containsKey(kept), kept + " went to " + redirects.get(kept));
        }
    }

    @Test
    @DisplayName("A Forge target's capability provider keeps the host interface and addCapability's descriptor")
    void providerStillLinksOnForge() {
        redirectsFor(true, false);
        ClassNode provider = transform(provider(), "mod/ChargeProvider");

        assertEquals(List.of(PROVIDER), provider.interfaces);
        MethodNode getCapability = method(provider, "getCapability");
        assertEquals("(L" + CAPABILITY + ";Lnet/minecraft/core/Direction;)L" + LAZY + ";", getCapability.desc);
        MethodInsnNode attach = call(method(provider, "attach"), "addCapability");
        assertEquals(ATTACH, attach.owner);
        assertEquals(ADD_CAPABILITY, attach.desc);
    }

    @Test
    @DisplayName("A NeoForge target is unchanged: the NeoForge bridge, not this polyfill, owns these names")
    void neoForgeTargetIsUnchanged() {
        Map<String, String> redirects = redirectsFor(false, true);
        for (String name : List.of(PROVIDER, LAZY, FORGE)) {
            assertFalse(redirects.containsKey(name), name);
        }
        assertEquals(redirects, redirectsFor(true, true), "the Forge flag changes nothing on NeoForge");
    }

    @Test
    @DisplayName("With no target loader, the stand-ins still replace classes the probe cannot find")
    void noTargetLoaderKeepsTheProbe() {
        Map<String, String> redirects = redirectsFor(false, false);
        assertEquals("com/retromod/shim/api/forge/embedded/LazyOptionalShim", redirects.get(LAZY));
        assertTrue(redirects.containsKey(PROVIDER));
        assertTrue(redirects.containsKey(FORGE));
    }

    private static Map<String, String> redirectsFor(boolean forge, boolean neoForge) {
        McReflect.setForceForge(forge);
        McReflect.setForceNeoForge(neoForge);
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new ForgeCorePolyfill().registerPolyfills(t);
        return Map.copyOf(t.getClassRedirects());
    }

    private static ClassNode transform(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(RetromodTransformer.getInstance().transformClass(bytes, name)).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no method " + name));
    }

    private static MethodInsnNode call(MethodNode method, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals(name)) return call;
        }
        throw new AssertionError("no call to " + name);
    }

    /**
     * A 1.20.1 provider: it implements ICapabilityProvider, answers with a LazyOptional, and
     * attaches itself with {@code event.addCapability(id, this)}.
     */
    private static byte[] provider() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/ChargeProvider", null, "java/lang/Object",
                new String[]{PROVIDER});

        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "getCapability",
                "(L" + CAPABILITY + ";Lnet/minecraft/core/Direction;)L" + LAZY + ";", null, null);
        get.visitCode();
        get.visitMethodInsn(INVOKESTATIC, LAZY, "empty", "()L" + LAZY + ";", false);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        MethodVisitor attach = cw.visitMethod(ACC_PUBLIC, "attach",
                "(L" + ATTACH + ";Lnet/minecraft/resources/Identifier;)V", null, null);
        attach.visitCode();
        attach.visitVarInsn(ALOAD, 1);
        attach.visitVarInsn(ALOAD, 2);
        attach.visitVarInsn(ALOAD, 0);
        attach.visitMethodInsn(INVOKEVIRTUAL, ATTACH, "addCapability", ADD_CAPABILITY, false);
        attach.visitInsn(RETURN);
        attach.visitMaxs(0, 0);
        attach.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
