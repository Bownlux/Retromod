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
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #267 (Grids Are Dumb, Forge 1.20.1 on NeoForge 1.21.1): menus are built with
 * {@code IForgeMenuType.create} and a lambda factory reading a {@code FriendlyByteBuf}, and opened
 * with {@code NetworkHooks.openScreen}. NeoForge deleted both and reads a
 * {@code RegistryFriendlyByteBuf}, so the mod keeps a Forge-shaped factory behind an adapter.
 */
class ForgeMenuBridgeTest {

    private static final String FORGE_FACTORY = "net/minecraftforge/network/IContainerFactory";
    private static final String FORGE_CREATE = "(ILnet/minecraft/world/entity/player/Inventory;"
            + "Lnet/minecraft/network/FriendlyByteBuf;)Lnet/minecraft/world/inventory/AbstractContainerMenu;";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        ForgeMenuBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("IForgeMenuType.create takes the mod's lambda through the adapter")
    void menuTypeCreateUsesTheBridge() {
        List<AbstractInsnNode> out = transform(mv -> {
            Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                    "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);
            Type sam = Type.getMethodType(FORGE_CREATE);
            mv.visitInvokeDynamicInsn("create", "()L" + FORGE_FACTORY + ";", metafactory, sam,
                    new Handle(H_INVOKESTATIC, "test/mod/Menus", "lambda$0", FORGE_CREATE, false), sam);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/common/extensions/IForgeMenuType",
                    "create", "(L" + FORGE_FACTORY + ";)Lnet/minecraft/world/inventory/MenuType;", true);
            mv.visitInsn(POP);
        });

        InvokeDynamicInsnNode lambda = only(out, InvokeDynamicInsnNode.class);
        assertEquals("()L" + ForgeMenuBridge.FACTORY + ";", lambda.desc,
                "the lambda must implement the Forge-shaped factory");
        assertEquals("create", lambda.name);
        MethodInsnNode create = only(out, MethodInsnNode.class);
        assertEquals(INVOKESTATIC, create.getOpcode());
        assertEquals(ForgeMenuBridge.MENUS, create.owner);
        assertFalse(create.itf, "the bridge is a class, not an interface");
    }

    @Test
    @DisplayName("NetworkHooks.openScreen with a BlockPos opens through the bridge")
    void openScreenUsesTheBridge() {
        String desc = "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/MenuProvider;"
                + "Lnet/minecraft/core/BlockPos;)V";
        List<AbstractInsnNode> out = transform(mv -> {
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/network/NetworkHooks", "openScreen",
                    desc, false);
        });

        MethodInsnNode call = only(out, MethodInsnNode.class);
        assertEquals(ForgeMenuBridge.MENUS, call.owner);
        assertEquals(desc, call.desc);
    }

    @Test
    @DisplayName("the generated factory, adapter, and menu helpers are well formed")
    void generatedClassesAreWellFormed() {
        for (byte[] bytes : new byte[][]{ForgeMenuBridge.generateFactory(),
                ForgeMenuBridge.generateAdapter(), ForgeMenuBridge.generateMenus()}) {
            new ClassReader(bytes).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        }
        ClassNode adapter = new ClassNode();
        new ClassReader(ForgeMenuBridge.generateAdapter()).accept(adapter, 0);
        assertEquals(List.of("net/neoforged/neoforge/network/IContainerFactory"), adapter.interfaces);
        assertTrue(adapter.methods.stream().anyMatch(m -> m.name.equals("create")
                && m.desc.contains("RegistryFriendlyByteBuf")),
                "the adapter must implement NeoForge's create");
    }

    private List<AbstractInsnNode> transform(java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Menus", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/Menus")).accept(cn, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (m.name.equals("run")) m.instructions.forEach(insns::add);
        }
        return insns;
    }

    private static <T extends AbstractInsnNode> T only(List<AbstractInsnNode> insns, Class<T> type) {
        List<T> found = insns.stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, found.size(), "expected one " + type.getSimpleName() + ", found " + found.size());
        return found.get(0);
    }
}
