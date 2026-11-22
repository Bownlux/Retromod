/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The third round of single removed members, and the 26.3 biome search and named randoms, reach their rebuilt forms. */
class LegacySmallCallsRoundThreeTest {

    private static final String LIVING = "Lnet/minecraft/world/entity/LivingEntity;";
    private static final String LEVEL = "Lnet/minecraft/client/multiplayer/ClientLevel;";
    private static final String SAMPLER = "Lnet/minecraft/world/level/biome/Climate$Sampler;";
    private static final String FACTORY = "()Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;";
    private static final String TINT = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;";
    private static final String BLOCK_STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String APPEARANCE_TAIL = "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)"
            + "Lnet/minecraft/world/level/block/state/BlockState;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("cow sounds, custom entity tags, collision, model parts and overlay coords are bridged on a 26.3 host")
    void thirdRoundIsBridged() {
        RetromodTransformer t = fresh();
        LegacySmallCallsBridge.registerThirdRound(t, Map.of(LegacySmallCallsBridge.SOUND_EVENTS, new ClassNode())::get);

        List<String> refs = refs(t.transformClass(oldCalls(), "test/three/Old"));

        assertTrue(refs.contains(LegacySmallCallsBridge.COW_SOUNDS + ".ambient()Lnet/minecraft/sounds/SoundEvent;"), refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.COW_SOUNDS + ".step()Lnet/minecraft/sounds/SoundEvent;"), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacySmallCallsBridge.WORLD + ".updateCustomEntityTag(")), refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.WORLD + ".canBeCollidedWith(Lnet/minecraft/world/entity/Entity;)Z"), refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.CLIENT + ".getAllParts(Lnet/minecraft/client/model/geom/ModelPart;)"
                + "Ljava/util/stream/Stream;"), refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.CLIENT + ".getOverlayCoords(" + LIVING + "F)I"), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains("COW_") || r.startsWith(LegacySmallCallsBridge.ENTITY_TYPE)), refs.toString());
    }

    @Test
    @DisplayName("a rising particle subclass passes no sprite to the constructor that now takes one")
    void risingParticleGetsSpriteSlot() {
        RetromodTransformer t = fresh();
        LegacySmallCallsBridge.registerThirdRound(t, name -> null);

        List<String> refs = refs(t.transformClass(risingParticle(), "test/three/Feather"));

        assertTrue(refs.contains(LegacySmallCallsBridge.RISING_PARTICLE + ".<init>(" + LEVEL + "DDDDDD"
                + "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"), refs.toString());
    }

    @Test
    @DisplayName("a host that still has each old member keeps it")
    void hostGates() {
        RetromodTransformer t = fresh();
        ClassNode sounds = new ClassNode();
        sounds.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "COW_AMBIENT", "Lnet/minecraft/sounds/SoundEvent;", null, null));
        Map<String, ClassNode> host = Map.of(
                LegacySmallCallsBridge.SOUND_EVENTS, sounds,
                LegacySmallCallsBridge.ENTITY, withMethod("canBeCollidedWith", "()Z"),
                LegacySmallCallsBridge.MODEL_PART, withMethod("getAllParts", "()Ljava/util/stream/Stream;"),
                LegacySmallCallsBridge.LIVING_RENDERER, withMethod("getOverlayCoords", "(" + LIVING + "F)I"),
                LegacySmallCallsBridge.ENTITY_TYPE, withMethod("updateCustomEntityTag", "(Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;"
                        + "Lnet/minecraft/world/item/component/CustomData;)V"));
        LegacySmallCallsBridge.registerThirdRound(t, host::get);

        List<String> refs = refs(t.transformClass(oldCalls(), "test/three/Old"));

        assertTrue(refs.contains("F " + LegacySmallCallsBridge.SOUND_EVENTS + ".COW_AMBIENT"), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.startsWith(LegacySmallCallsBridge.WORLD) || r.startsWith(LegacySmallCallsBridge.CLIENT)),
                refs.toString());
    }

    @Test
    @DisplayName("the 26.3 biome search with a sampler and the ore and aquifer randoms reach the rebuilt members")
    void worldgenSearchesAreBridged() {
        RetromodTransformer t = fresh();
        LegacyWorldgenMembersBridge.registerSearches263(t, true, true);

        List<String> refs = refs(t.transformClass(worldgenCalls(), "test/three/Placement"));

        String members = LegacyWorldgenMembersBridge.MEMBERS_263;
        assertTrue(refs.contains(members + ".findBiomeHorizontal(L" + LegacyWorldgenMembersBridge.BIOME_SOURCE + ";"
                + LegacyWorldgenMembersBridge.FIND_LONG_OLD.substring(1)), refs.toString());
        assertTrue(refs.contains(members + ".oreRandom(L" + LegacyWorldgenMembersBridge.RANDOM_STATE + ";)"
                + FACTORY.substring(2)), refs.toString());
        assertTrue(refs.contains(members + ".aquiferRandom(L" + LegacyWorldgenMembersBridge.RANDOM_STATE + ";)"
                + FACTORY.substring(2)), refs.toString());

        RetromodTransformer kept = fresh();
        LegacyWorldgenMembersBridge.registerSearches263(kept, false, false);
        assertTrue(refs(kept.transformClass(worldgenCalls(), "test/three/Placement")).contains(
                LegacyWorldgenMembersBridge.RANDOM_STATE + ".oreRandom" + FACTORY), "a 26.2 host keeps its own ore random");
    }

    @Test
    @DisplayName("Fabric API's getAppearance takes the light getter on 26.x")
    void fabricAppearanceTakesLightGetter() {
        RetromodTransformer t = fresh();
        Fabric_1_21_11_to_26_1.registerBlockAppearance(t);

        List<String> refs = refs(t.transformClass(appearanceCall(), "test/three/Footsteps"));

        assertTrue(refs.contains("net/minecraft/world/level/block/state/BlockState.getAppearance("
                + "Lnet/minecraft/world/level/BlockAndLightGetter;" + APPEARANCE_TAIL), refs.toString());
        assertTrue(refs.contains("net/minecraft/world/level/block/Block.getAppearance(" + BLOCK_STATE
                + "Lnet/minecraft/world/level/BlockAndLightGetter;" + APPEARANCE_TAIL), "the block-level form too: " + refs);
    }

    private static RetromodTransformer fresh() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        return t;
    }

    private static byte[] oldCalls() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/three/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        for (String cow : new String[]{"COW_AMBIENT", "COW_STEP"}) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, LegacySmallCallsBridge.SOUND_EVENTS, cow, "Lnet/minecraft/sounds/SoundEvent;");
            mv.visitInsn(Opcodes.POP);
        }
        for (int i = 0; i < 4; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacySmallCallsBridge.ENTITY_TYPE, "updateCustomEntityTag",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;"
                        + "Lnet/minecraft/world/item/component/CustomData;)V", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySmallCallsBridge.ENTITY, "canBeCollidedWith", "()Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySmallCallsBridge.MODEL_PART, "getAllParts", "()Ljava/util/stream/Stream;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacySmallCallsBridge.LIVING_RENDERER, "getOverlayCoords", "(" + LIVING + "F)I", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(6, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] risingParticle() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/three/Feather", null, LegacySmallCallsBridge.RISING_PARTICLE, null);
        String desc = "(" + LEVEL + "DDDDDD)V";
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        for (int slot = 2; slot <= 12; slot += 2) mv.visitVarInsn(Opcodes.DLOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacySmallCallsBridge.RISING_PARTICLE, "<init>", desc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(16, 14);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] worldgenCalls() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/three/Placement", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        for (int i = 0; i < 5; i++) mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyWorldgenMembersBridge.BIOME_SOURCE, "findBiomeHorizontal",
                LegacyWorldgenMembersBridge.FIND_LONG_OLD, false);
        mv.visitInsn(Opcodes.POP);
        for (String name : new String[]{"oreRandom", "aquiferRandom"}) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyWorldgenMembersBridge.RANDOM_STATE, name, FACTORY, false);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(10, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] appearanceCall() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/three/Footsteps", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        for (int i = 0; i < 6; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/block/state/BlockState", "getAppearance",
                "(" + TINT + APPEARANCE_TAIL, false);
        mv.visitInsn(Opcodes.POP);
        for (int i = 0; i < 7; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/block/Block", "getAppearance",
                "(" + BLOCK_STATE + TINT + APPEARANCE_TAIL, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(7, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode withMethod(String name, String desc) {
        ClassNode node = new ClassNode();
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null));
        return node;
    }

    private static List<String> refs(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) refs.add("F " + field.owner + "." + field.name);
            }
        }
        return refs;
    }
}
