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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A 1.20.1 or 1.21.1 trade listener's profession check reads registry keys as professions on NeoForge 1.21.5 to 1.21.11. */
class LegacyTradeProfessionKeysTest {

    static final String EVENT = LegacyTradeBridge.NEOFORGE_VILLAGER_EVENT;
    static final String MOVED = "net/minecraft/world/entity/npc/villager/VillagerProfession";
    static final String OLD = "net/minecraft/world/entity/npc/VillagerProfession";
    static final String KEY = "Lnet/minecraft/resources/ResourceKey;";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.setModSourceVersion("1.20.1");
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        transformer.setModSourceVersion(null);
    }

    @Test
    @DisplayName("on a 1.21.11 host the event's profession and FARMER both read through the registry, in the moved package")
    void keyedProfessionsOn12111() {
        LegacyTradeBridge.registerProfessionKeys(transformer, host(MOVED, true)::get);

        ClassNode handler = node(transformer.transformClass(listener(MOVED), "mod/Trades"));

        assertTrue(calls(handler).contains(LegacyTradeBridge.KEYED_PROFESSIONS + ".type(L" + EVENT + ";)L" + MOVED + ";"), calls(handler).toString());
        assertTrue(calls(handler).contains(LegacyTradeBridge.KEYED_PROFESSIONS + ".of(" + KEY + ")L" + MOVED + ";"), calls(handler).toString());
        assertTrue(fields(handler).contains(MOVED + ".FARMER:" + KEY), fields(handler).toString());
        ClassNode helper = node(transformer.getSyntheticClasses().get(LegacyTradeBridge.KEYED_PROFESSIONS));
        assertTrue(helper.methods.stream().anyMatch(m -> m.name.equals("type")));
    }

    @Test
    @DisplayName("a 1.21.5 to 1.21.10 host keeps the profession in npc, and a call naming either package is taken")
    void keyedProfessionsBeforeTheMove() {
        LegacyTradeBridge.registerProfessionKeys(transformer, host(OLD, true)::get);

        ClassNode handler = node(transformer.transformClass(listener(MOVED), "mod/Trades"));

        assertTrue(calls(handler).contains(LegacyTradeBridge.KEYED_PROFESSIONS + ".type(L" + EVENT + ";)L" + OLD + ";"), calls(handler).toString());
    }

    @Test
    @DisplayName("a 1.21.1 host, whose event still returns the profession, is left alone, and so is a mod built for 1.21.5")
    void untouchedWhereTheHostOrModHasTheProfession() {
        LegacyTradeBridge.registerProfessionKeys(transformer, host(OLD, false)::get);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyTradeBridge.KEYED_PROFESSIONS));
        assertTrue(calls(node(transformer.transformClass(listener(OLD), "mod/Trades"))).contains(EVENT + ".getType()L" + OLD + ";"));

        transformer.clearRedirectsForTesting();
        transformer.setModSourceVersion("1.21.5");
        LegacyTradeBridge.registerProfessionKeys(transformer, host(MOVED, true)::get);
        assertTrue(calls(node(transformer.transformClass(listener(MOVED), "mod/Trades"))).contains(EVENT + ".getType()L" + MOVED + ";"));
    }

    /** The event and profession shapes of a host: keyed from 1.21.5, plain on 1.21.1. */
    private static Map<String, ClassNode> host(String profession, boolean keyed) {
        Map<String, ClassNode> host = new HashMap<>();
        ClassNode event = new ClassNode();
        event.methods.add(new MethodNode(ACC_PUBLIC, "getType", "()" + (keyed ? KEY : "L" + profession + ";"), null, null));
        host.put(EVENT, event);
        ClassNode type = new ClassNode();
        type.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "FARMER", keyed ? KEY : "L" + profession + ";", null, null));
        host.put(profession, type);
        return host;
    }

    /** {@code if (event.getType() == VillagerProfession.FARMER)}, as a farmer trade listener checks it. */
    private static byte[] listener(String profession) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Trades", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onTrades", "(L" + EVENT + ";)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, EVENT, "getType", "()L" + profession + ";", false);
        mv.visitFieldInsn(GETSTATIC, profession, "FARMER", "L" + profession + ";");
        mv.visitInsn(POP2);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    private static List<String> fields(ClassNode node) {
        List<String> fields = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) fields.add(field.owner + "." + field.name + ":" + field.desc);
            }
        }
        return fields;
    }
}
