/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** NeoForge 1.21.1 mods call event and network members that later NeoForge releases renamed or retyped. */
class NeoForgeMovedMembersTest {

    private static final String NEO = "net/neoforged/neoforge/";
    private static final String PAYLOAD = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;";
    private final String target = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On 26.3, old network, key, fall, explosion, and tag event calls reach their current members")
    void oldCallsReachCurrentMembers() {
        List<String> refs = transformedRefs("26.3");

        assertTrue(refs.contains(INVOKESTATIC + " " + NEO + "client/network/ClientPacketDistributor.sendToServer("
                + PAYLOAD + "[" + PAYLOAD + ")V"), refs.toString());
        assertTrue(refs.contains(INVOKEVIRTUAL + " " + NEO + "client/event/ScreenEvent$KeyPressed$Pre.getKey()I"), refs.toString());
        assertTrue(refs.contains(INVOKEVIRTUAL + " " + NEO + "client/event/ScreenEvent$KeyPressed$Pre.getKeycode()I"),
                "the old scan code is the new keycode: " + refs);
        assertTrue(refs.contains(INVOKESTATIC + " com/retromod/legacyapi/neoforge/event/LivingFallMembers.fallDistance(L"
                + NEO + "event/entity/living/LivingFallEvent;)F"), refs.toString());
        assertTrue(refs.contains(INVOKEVIRTUAL + " " + NEO
                + "event/level/ExplosionEvent$Detonate.getExplosion()Lnet/minecraft/world/level/ServerExplosion;"), refs.toString());
        assertTrue(refs.contains(INVOKESTATIC + " com/retromod/legacyapi/neoforge/event/TagsUpdateCause.of(L" + NEO
                + "event/TagsUpdatedEvent;)Lcom/retromod/legacyapi/neoforge/event/TagsUpdateCause;"), refs.toString());
        assertTrue(refs.contains("com/retromod/legacyapi/neoforge/event/TagsUpdateCause.SERVER_DATA_LOAD"), refs.toString());
    }

    @Test
    @DisplayName("On 26.2 the key events and the tag update cause keep their old names")
    void olderHostKeepsWhatItStillHas() {
        List<String> refs = transformedRefs("26.2");

        assertTrue(refs.contains(INVOKEVIRTUAL + " " + NEO + "client/event/ScreenEvent$KeyPressed$Pre.getKeyCode()I"), refs.toString());
        assertTrue(refs.contains(INVOKEVIRTUAL + " " + NEO + "event/TagsUpdatedEvent.getUpdateCause()L" + NEO
                + "event/TagsUpdatedEvent$UpdateCause;"), refs.toString());
        assertTrue(refs.contains(INVOKESTATIC + " " + NEO + "client/network/ClientPacketDistributor.sendToServer("
                + PAYLOAD + "[" + PAYLOAD + ")V"), "sendToServer moved before 26.1: " + refs);
    }

    @Test
    @DisplayName("An old loot modifier extends the base for its host: rebuilt on 26.3, generated on 26.2, none on 1.21.11")
    void oldLootModifierExtendsTheRebuiltBase() {
        String legacy = "com/retromod/legacyapi/neoforge/common/loot/LegacyLootModifier";
        ClassNode on263 = transformed("26.3", oldLootModifier());
        assertEquals(legacy, on263.superName);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : on263.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
            }
        }
        assertTrue(refs.contains(legacy + ".<init>([Lnet/minecraft/world/level/storage/loot/predicates/LootItemCondition;)V"), refs.toString());
        assertTrue(refs.stream().anyMatch(ref -> ref.startsWith(legacy + ".codecStart(")), refs.toString());

        ClassNode on262 = transformed("26.2", oldLootModifier());
        assertEquals(LegacyLootModifierBridge.BASE_26_2, on262.superName, "26.2 kept the array and added a priority");
        assertEquals(NEO + "common/loot/LootModifier", transformed("1.21.11", oldLootModifier()).superName);
    }

    private static ClassNode transformed(String targetVersion, byte[] bytes) {
        RetromodVersion.TARGET_MC_VERSION = targetVersion;
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyLootModifierBridge.register(t);
        ClassNode node = new ClassNode();
        new ClassReader(t.transformClass(bytes, "mod/Drops.class")).accept(node, 0);
        return node;
    }

    @Test
    @DisplayName("The generated 26.2 loot modifier base and its getter verify")
    void generated262BaseVerifies() {
        for (byte[] bytes : List.of(LegacyLootModifierBridge.generateBase262(), LegacyLootModifierBridge.generateGetter262())) {
            new ClassReader(bytes).accept(new org.objectweb.asm.util.CheckClassAdapter(new ClassWriter(0), true), 0);
        }
    }

    /** A 1.21.1 loot modifier: {@code super(conditions)} and a call to the old codec start. */
    private static byte[] oldLootModifier() {
        String modifier = NEO + "common/loot/LootModifier";
        String conditions = "[Lnet/minecraft/world/level/storage/loot/predicates/LootItemCondition;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, "mod/Drops", null, modifier, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + conditions + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, modifier, "<init>", "(" + conditions + ")V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor codec = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "codec",
                "(Lcom/mojang/serialization/codecs/RecordCodecBuilder$Instance;)Ljava/lang/Object;", null, null);
        codec.visitCode();
        codec.visitVarInsn(ALOAD, 0);
        codec.visitMethodInsn(INVOKESTATIC, modifier, "codecStart",
                "(Lcom/mojang/serialization/codecs/RecordCodecBuilder$Instance;)Lcom/mojang/datafixers/Products$P1;", false);
        codec.visitInsn(ARETURN);
        codec.visitMaxs(0, 0);
        codec.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Every call and field read in the transformed 1.21.1 class, offline with the given target. */
    private static List<String> transformedRefs(String targetVersion) {
        RetromodVersion.TARGET_MC_VERSION = targetVersion;
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        NeoForgeMovedMembers.register(t);

        ClassNode node = new ClassNode();
        new ClassReader(t.transformClass(oldListener(), "mod/Listener.class")).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) refs.add(field.owner + "." + field.name);
            }
        }
        return refs;
    }

    /** A 1.21.1 listener that calls each old member once. */
    private static byte[] oldListener() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/Listener", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        m.visitCode();
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ICONST_0);
        m.visitTypeInsn(ANEWARRAY, "net/minecraft/network/protocol/common/custom/CustomPacketPayload");
        m.visitMethodInsn(INVOKESTATIC, NEO + "network/PacketDistributor", "sendToServer", "(" + PAYLOAD + "[" + PAYLOAD + ")V", false);
        call(m, NEO + "client/event/ScreenEvent$KeyPressed$Pre", "getKeyCode", "()I");
        call(m, NEO + "client/event/ScreenEvent$KeyPressed$Pre", "getScanCode", "()I");
        call(m, NEO + "event/entity/living/LivingFallEvent", "getDistance", "()F");
        call(m, NEO + "event/level/ExplosionEvent$Detonate", "getExplosion", "()Lnet/minecraft/world/level/Explosion;");
        call(m, NEO + "event/TagsUpdatedEvent", "getUpdateCause", "()L" + NEO + "event/TagsUpdatedEvent$UpdateCause;");
        m.visitFieldInsn(GETSTATIC, NEO + "event/TagsUpdatedEvent$UpdateCause", "SERVER_DATA_LOAD",
                "L" + NEO + "event/TagsUpdatedEvent$UpdateCause;");
        m.visitInsn(POP);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void call(MethodVisitor m, String owner, String name, String desc) {
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKEVIRTUAL, owner, name, desc, false);
        m.visitInsn(POP);
    }
}
