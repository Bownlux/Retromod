/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #283: Scorched Guns' turret block opens its menu with
 * {@code state.getBlock().getMenuProvider(state, level, pos)}. 1.21 made that block method protected,
 * so the class failed verification with "Bad access to protected data in invokevirtual".
 */
class LegacyProtectedBlockCallBridgeTest {

    private static final LegacyProtectedBlockCallBridge.Names NAMES = LegacyProtectedBlockCallBridge.MOJANG;
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String POS = "net/minecraft/core/BlockPos";
    private static final String MENU = "net/minecraft/world/MenuProvider";
    private static final String BLOCK_DESC = "(L" + NAMES.state() + ";L" + LEVEL + ";L" + POS + ";)L" + MENU + ";";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a call on another block goes to the state's public delegate and runs")
    void outsideCallUsesTheStateDelegate() throws Exception {
        Map<String, byte[]> host = host();
        assertEquals(1, LegacyProtectedBlockCallBridge.register(transformer, NAMES, reader(host)),
                "only the protected method with a state delegate is bridged");

        byte[] turret = transformer.transformClass(turret(), "test/Turret.class");
        MethodInsnNode call = firstCall(turret, "menu", "getMenuProvider");
        assertEquals(NAMES.helper(), call.owner);
        assertEquals(INVOKESTATIC, call.getOpcode());

        HostStubs stubs = new HostStubs();
        host.values().forEach(stubs::add);
        stubs.add(NAMES.helper(), transformer.getSyntheticClasses().get(NAMES.helper()));
        stubs.add(turret);
        Object state = stubs.load(NAMES.state()).getConstructor().newInstance();
        Object menu = stubs.load("test/Turret").getMethod("menu", stubs.load(NAMES.block()), stubs.load(NAMES.state()))
                .invoke(null, null, state);
        assertEquals("menu from the state", menu);
    }

    @Test
    @DisplayName("a super call in the mod's own override stays, or it would run the override again")
    void superCallIsLeftAlone() {
        LegacyProtectedBlockCallBridge.register(transformer, NAMES, reader(host()));

        MethodInsnNode call = firstCall(transformer.transformClass(turret(), "test/Turret.class"),
                "getMenuProvider", "getMenuProvider");

        assertEquals(INVOKESPECIAL, call.getOpcode());
        assertEquals(NAMES.block(), call.owner);
    }

    @Test
    @DisplayName("a host where the method is still public gets nothing")
    void publicMethodIsNotBridged() {
        Map<String, byte[]> host = host();
        host.put(NAMES.behaviour(), behaviour(ACC_PUBLIC));
        assertEquals(0, LegacyProtectedBlockCallBridge.register(transformer, NAMES, reader(host)));
    }

    @Test
    @DisplayName("intermediary names pair method_17454 with its state delegate method_26196")
    void intermediaryDelegateHasItsOwnId() {
        LegacyProtectedBlockCallBridge.Names names = LegacyProtectedBlockCallBridge.INTERMEDIARY;
        String desc = "(L" + names.state() + ";Lnet/minecraft/class_1937;Lnet/minecraft/class_2338;)Lnet/minecraft/class_3908;";
        ClassWriter behaviour = new ClassWriter(0);
        behaviour.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, names.behaviour(), null, "java/lang/Object", null);
        behaviour.visitMethod(ACC_PROTECTED | ACC_ABSTRACT, "method_17454", desc, null, null).visitEnd();
        behaviour.visitEnd();
        ClassWriter stateBase = new ClassWriter(0);
        stateBase.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, names.stateBase(), null, "java/lang/Object", null);
        stateBase.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "method_26196",
                "(Lnet/minecraft/class_1937;Lnet/minecraft/class_2338;)Lnet/minecraft/class_3908;", null, null).visitEnd();
        stateBase.visitEnd();
        Map<String, byte[]> host = Map.of(names.behaviour(), behaviour.toByteArray(),
                names.stateBase(), stateBase.toByteArray());

        assertEquals(1, LegacyProtectedBlockCallBridge.register(transformer, names, reader(new HashMap<>(host))),
                "the bundled map names both getMenuProvider, so they pair");
        ClassNode helper = new ClassNode();
        new ClassReader(transformer.getSyntheticClasses().get(names.helper())).accept(helper, 0);
        MethodInsnNode delegate = List.of(helper.methods.get(0).instructions.toArray()).stream()
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).findFirst().orElseThrow();
        assertEquals("method_26196", delegate.name, "the state delegate is called by its own id");
    }

    /**
     * {@code class Turret extends Block}: a static {@code menu(Block, BlockState)} that calls
     * {@code block.getMenuProvider(state, null, null)}, and an override that calls super.
     */
    private static byte[] turret() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Turret", null, NAMES.block(), null);
        MethodVisitor menu = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "menu",
                "(L" + NAMES.block() + ";L" + NAMES.state() + ";)Ljava/lang/Object;", null, null);
        menu.visitCode();
        menu.visitVarInsn(ALOAD, 0);
        menu.visitVarInsn(ALOAD, 1);
        menu.visitInsn(ACONST_NULL);
        menu.visitInsn(ACONST_NULL);
        menu.visitMethodInsn(INVOKEVIRTUAL, NAMES.block(), "getMenuProvider", BLOCK_DESC, false);
        menu.visitInsn(ARETURN);
        menu.visitMaxs(0, 0);
        menu.visitEnd();
        MethodVisitor override = cw.visitMethod(ACC_PROTECTED, "getMenuProvider", BLOCK_DESC, null, null);
        override.visitCode();
        override.visitVarInsn(ALOAD, 0);
        override.visitVarInsn(ALOAD, 1);
        override.visitVarInsn(ALOAD, 2);
        override.visitVarInsn(ALOAD, 3);
        override.visitMethodInsn(INVOKESPECIAL, NAMES.block(), "getMenuProvider", BLOCK_DESC, false);
        override.visitInsn(ARETURN);
        override.visitMaxs(0, 0);
        override.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * BlockBehaviour with a protected {@code getMenuProvider(state, level, pos)} and a protected
     * {@code useWithoutItem(state, level)} that has no state delegate; Block extends it; the state's
     * {@code getMenuProvider(level, pos)} answers a marker.
     */
    private static Map<String, byte[]> host() {
        Map<String, byte[]> host = new HashMap<>();
        host.put(NAMES.behaviour(), behaviour(ACC_PROTECTED));
        host.put(NAMES.block(), HostStubs.plainClass(NAMES.block(), NAMES.behaviour()));
        ClassWriter base = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        base.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, NAMES.stateBase(), null, "java/lang/Object", null);
        HostStubs.defaultConstructor(base, "java/lang/Object");
        MethodVisitor delegate = base.visitMethod(ACC_PUBLIC, "getMenuProvider",
                "(L" + LEVEL + ";L" + POS + ";)L" + MENU + ";", null, null);
        delegate.visitCode();
        delegate.visitLdcInsn("menu from the state");
        delegate.visitInsn(ARETURN);
        delegate.visitMaxs(0, 0);
        delegate.visitEnd();
        base.visitEnd();
        host.put(NAMES.stateBase(), base.toByteArray());
        host.put(NAMES.state(), HostStubs.plainClass(NAMES.state(), NAMES.stateBase()));
        // MenuProvider is an interface, which the verifier accepts any object for.
        host.put(MENU, HostStubs.iface(MENU));
        return host;
    }

    private static byte[] behaviour(int access) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, NAMES.behaviour(), null, "java/lang/Object", null);
        HostStubs.defaultConstructor(cw, "java/lang/Object");
        cw.visitMethod(access | ACC_ABSTRACT, "getMenuProvider", BLOCK_DESC, null, null).visitEnd();
        cw.visitMethod(ACC_PROTECTED | ACC_ABSTRACT, "useWithoutItem",
                "(L" + NAMES.state() + ";L" + LEVEL + ";)V", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static java.util.function.Function<String, ClassNode> reader(Map<String, byte[]> host) {
        return name -> {
            byte[] bytes = host.get(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        };
    }

    private static MethodInsnNode firstCall(byte[] bytes, String method, String called) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        var instructions = node.methods.stream().filter(m -> m.name.equals(method)).findFirst()
                .orElseThrow().instructions.toArray();
        return List.of(instructions).stream()
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(call -> call.name.equals(called)).findFirst().orElseThrow();
    }
}
