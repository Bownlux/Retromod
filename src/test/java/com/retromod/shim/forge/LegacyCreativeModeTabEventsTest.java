/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.mapping.TargetSrgMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #364: Super Mario Mania 2.0 (Forge 1.19.4, MCreator) builds its tabs on Forge's
 * {@code CreativeModeTabEvent}, which Forge 1.20 removed, and stopped at construction on Forge
 * 1.20.1 with {@code NoClassDefFoundError: CreativeModeTabEvent$BuildContents}.
 */
class LegacyCreativeModeTabEventsTest {

    private static final String TAB = "Lnet/minecraft/world/item/CreativeModeTab;";
    private static final String ID = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String TABS = "net/minecraft/world/item/CreativeModeTabs";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.setSrgSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Both tab events and the tab constants land on Forge 1.20's registry and contents event")
    void eventsAndConstantsAreBridged() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyCreativeModeTabEvents.register(transformer);

        ClassNode tabs = transform(transformer, modTabs(TABS, "SPAWN_EGGS"), "mod/ModTabs");

        assertNotNull(method(tabs, "build", "(L" + LegacyCreativeModeTabEvents.BUILD_EVENT + ";)V"));
        assertNotNull(method(tabs, "register", "(L" + LegacyCreativeModeTabEvents.REGISTER_EVENT + ";)V"));
        List<String> calls = calls(tabs);
        assertTrue(calls.contains(LegacyCreativeModeTabEvents.CALLS + ".SPAWN_EGGS"), calls.toString());
        assertTrue(calls.contains(LegacyCreativeModeTabEvents.CALLS + ".registerCreativeModeTab"), calls.toString());
    }

    @Test
    @DisplayName("On Forge 1.20.1 an SRG mod's tab constant, void damage type, and player teleport reach the host")
    void srgHostReadsTheNewShapes() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        SrgToMojangMapper.getInstance().applyTo(transformer);
        TargetSrgMapper.forVersion("1.20.1").applyTo(transformer);
        transformer.setSrgSourceVersion("1.19.4");
        new Forge_1_19_4_to_1_20().registerRedirects(transformer);

        ClassNode tabs = transform(transformer, modTabs(TABS, "f_256731_"), "mod/ModTabs");
        ClassNode damage = transform(transformer, voidDamage(), "mod/VoidDamage");
        ClassNode teleporter = transform(transformer, teleporter(), "mod/Teleporter");

        assertTrue(calls(tabs).contains(LegacyCreativeModeTabEvents.CALLS + ".SPAWN_EGGS"), calls(tabs).toString());
        FieldInsnNode read = (FieldInsnNode) first(damage, FieldInsnNode.class);
        assertEquals("f_268724_", read.name, "1.19.4 OUT_OF_WORLD is 1.20.1 FELL_OUT_OF_WORLD under the same id");
        MethodInsnNode move = (MethodInsnNode) first(teleporter, MethodInsnNode.class);
        assertEquals("m_284127_", move.name, "1.19.4 setLevel is 1.20.1 setServerLevel");
    }

    @Test
    @DisplayName("The generated calls verify, before and after 1.21.2 changed Registry.getOrThrow")
    void generatedCallsVerify() {
        for (String host : List.of("1.20.1", "1.21.1", "26.3")) {
            new ClassReader(LegacyCreativeModeTabEvents.generateCalls(host))
                    .accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
        assertTrue(calls(read(LegacyCreativeModeTabEvents.generateCalls("26.3"))).contains("net/minecraft/core/Registry.getValueOrThrow"));
    }

    /** An MCreator tab class: a contents handler that compares with a vanilla tab, and a register handler. */
    private static byte[] modTabs(String tabsOwner, String spawnEggs) {
        String build = "net/minecraftforge/event/CreativeModeTabEvent$BuildContents";
        String register = "net/minecraftforge/event/CreativeModeTabEvent$Register";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/ModTabs", null, "java/lang/Object", null);
        MethodVisitor contents = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "build", "(L" + build + ";)V", null, null);
        contents.visitCode();
        contents.visitVarInsn(ALOAD, 0);
        contents.visitMethodInsn(INVOKEVIRTUAL, build, "getTab", "()" + TAB, false);
        contents.visitFieldInsn(GETSTATIC, tabsOwner, spawnEggs, TAB);
        contents.visitInsn(POP2);
        contents.visitInsn(RETURN);
        contents.visitMaxs(0, 0);
        contents.visitEnd();
        MethodVisitor registry = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "(L" + register + ";)V", null, null);
        registry.visitCode();
        registry.visitVarInsn(ALOAD, 0);
        registry.visitInsn(ACONST_NULL);
        registry.visitInsn(ACONST_NULL);
        registry.visitMethodInsn(INVOKEVIRTUAL, register, "registerCreativeModeTab",
                "(" + ID + "Ljava/util/function/Consumer;)" + TAB, false);
        registry.visitInsn(POP);
        registry.visitInsn(RETURN);
        registry.visitMaxs(0, 0);
        registry.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code DamageTypes.f_268724_}, which 1.19.4 called OUT_OF_WORLD. */
    private static byte[] voidDamage() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/VoidDamage", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "key", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/world/damagesource/DamageTypes", "f_268724_",
                "Lnet/minecraft/resources/ResourceKey;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A teleporter moving a player with {@code ServerPlayer.m_143425_}, 1.19.4's setLevel. */
    private static byte[] teleporter() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Teleporter", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "move",
                "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerLevel;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/server/level/ServerPlayer", "m_143425_",
                "(Lnet/minecraft/server/level/ServerLevel;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode transform(RetromodTransformer transformer, byte[] bytes, String name) {
        return read(transformer.transformClass(bytes, name));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    private static AbstractInsnNode first(ClassNode node, Class<?> type) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (type.isInstance(insn)) return insn;
            }
        }
        return null;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        return calls;
    }
}
