/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Forge 1.20.1 partial tick, light, name tag, modifier, spawn and crafting reads reach Forge 26.2 and newer. */
class LegacyForgeEventReadsTest {

    private static final String EVENT = "net/minecraftforge/client/event/";
    private static final String PLAYER = "net/minecraftforge/event/entity/player/";
    private static final String MOD_CLASS = "test/forgereads/Handlers";
    private static final String READS = LegacyForgeEventMembersBridge.CLIENT_READS;
    private static final String SPAWN = LegacyForgeEventMembersBridge.EVENT_READS;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void reset() {
        transformer.clearRedirectsForTesting();
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Forge: render state reads, the held modifier and the new spawn reach the rebuilt helpers")
    void helperReads(String host) {
        forge(host);
        String living = "(L" + EVENT + "RenderLivingEvent;)";
        String tag = "(L" + EVENT + "RenderNameTagEvent;)";
        String modifier = "()Lnet/minecraftforge/client/settings/KeyModifier;";
        List<String> calls = LegacyEventMembersBridgeTest.calls(transform(EVENT + "RenderLivingEvent$Pre.getPartialTick()F",
                EVENT + "RenderLivingEvent$Post.getPackedLight()I",
                EVENT + "RenderNameTagEvent.getEntity()Lnet/minecraft/world/entity/Entity;",
                EVENT + "RenderNameTagEvent.getPackedLight()I",
                "static net/minecraftforge/client/settings/KeyModifier.getActiveModifier" + modifier,
                PLAYER + "PlayerSetSpawnEvent.getNewSpawn()Lnet/minecraft/core/BlockPos;",
                PLAYER + "PlayerSetSpawnEvent.isForced()Z",
                "net/minecraftforge/event/entity/living/LivingDropsEvent.getLootingLevel()I",
                "static net/minecraftforge/common/ForgeHooks.canEntityDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/entity/LivingEntity;)Z"));
        assertEquals(List.of("INVOKESTATIC " + READS + ".livingPartialTick" + living + "F",
                "INVOKESTATIC " + READS + ".livingLight" + living + "I",
                "INVOKESTATIC " + READS + ".nameTagEntity" + tag + "Lnet/minecraft/world/entity/Entity;",
                "INVOKESTATIC " + READS + ".nameTagLight" + tag + "I",
                "INVOKESTATIC " + READS + ".activeModifier" + modifier,
                "INVOKESTATIC " + SPAWN + ".newSpawn(L" + PLAYER + "PlayerSetSpawnEvent;)Lnet/minecraft/core/BlockPos;",
                "INVOKESTATIC " + SPAWN + ".forced(L" + PLAYER + "PlayerSetSpawnEvent;)Z",
                "INVOKESTATIC " + SPAWN + ".lootingLevel(Lnet/minecraftforge/event/entity/living/LivingDropsEvent;)I",
                "INVOKESTATIC " + SPAWN + ".canEntityDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/entity/LivingEntity;)Z"), calls);
        assertTrue(transformer.getSyntheticClasses().containsKey(READS));
        assertTrue(transformer.getSyntheticClasses().containsKey(SPAWN));
    }

    @Test
    @DisplayName("Forge 26.2: a viewport event's double partial tick reads the float and widens it; renamed getters take the new name")
    void conversionsAndRenames() {
        forge("26.2");
        ClassNode out = transform(EVENT + "ViewportEvent$ComputeCameraAngles.getPartialTick()D",
                "net/minecraftforge/event/level/BlockEvent$FarmlandTrampleEvent.getFallDistance()F",
                PLAYER + "PlayerEvent$ItemCraftedEvent.getInventory()Lnet/minecraft/world/Container;",
                "net/minecraftforge/event/entity/living/MobSpawnEvent$FinalizeSpawn.getSpawnType()Lnet/minecraft/world/entity/EntitySpawnReason;");
        assertEquals(List.of("INVOKEVIRTUAL " + EVENT + "ViewportEvent$ComputeCameraAngles.getPartialTick()F",
                "INVOKEVIRTUAL net/minecraftforge/event/level/BlockEvent$FarmlandTrampleEvent.getFallDistance()D",
                "INVOKEVIRTUAL " + PLAYER + "PlayerEvent$ItemCraftedEvent.getContainer()Lnet/minecraft/world/Container;",
                "INVOKEVIRTUAL net/minecraftforge/event/entity/living/MobSpawnEvent$FinalizeSpawn.getSpawnReason()"
                        + "Lnet/minecraft/world/entity/EntitySpawnReason;"), LegacyEventMembersBridgeTest.calls(out));
        AbstractInsnNode afterCall = firstCall(out).getNext();
        assertEquals(Opcodes.F2D, afterCall.getOpcode(), "the float is widened for the code that reads a double");
    }

    @Test
    @DisplayName("Forge 26.2: the arm's player, skull model registration and an old break event reach the helpers")
    void armSkullsAndBreakEvent() {
        forge("26.2");
        String skulls = EVENT + "EntityRenderersEvent$CreateSkullModels";
        String type = "Lnet/minecraft/world/level/block/SkullBlock$Type;";
        String model = "Lnet/minecraft/client/model/object/skull/SkullModelBase;";
        List<String> calls = LegacyEventMembersBridgeTest.calls(transform(
                EVENT + "RenderArmEvent.getPlayer()Lnet/minecraft/client/player/AbstractClientPlayer;",
                skulls + ".getEntityModelSet()Lnet/minecraft/client/model/geom/EntityModelSet;"));
        assertEquals(List.of("INVOKESTATIC " + READS + ".armPlayer(L" + EVENT + "RenderArmEvent;)Lnet/minecraft/client/player/AbstractClientPlayer;",
                "INVOKESTATIC " + READS + ".skullModelSet(L" + skulls + ";)Lnet/minecraft/client/model/geom/EntityModelSet;"), calls);

        // A skull registration passes two arguments, and a break event is built with new.
        String breakEvent = "net/minecraftforge/event/level/BlockEvent$BreakEvent";
        String oldBreak = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
                + "Lnet/minecraft/world/entity/player/Player;)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitTypeInsn(Opcodes.CHECKCAST, skulls);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, skulls, "registerSkullModel", "(" + type + model + ")V", false);
        mv.visitTypeInsn(Opcodes.NEW, breakEvent);
        mv.visitInsn(Opcodes.DUP);
        for (int i = 0; i < 4; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, breakEvent, "<init>", oldBreak, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), MOD_CLASS)).accept(node, 0);
        assertEquals(List.of("INVOKESTATIC " + READS + ".registerSkullModel(L" + skulls + ";" + type + model + ")V",
                "INVOKESTATIC " + SPAWN + ".breakEvent" + oldBreak.substring(0, oldBreak.length() - 1) + "L" + breakEvent + ";"),
                LegacyEventMembersBridgeTest.calls(node));
    }

    @Test
    @DisplayName("Forge 1.21.1 and NeoForge keep Forge's own members")
    void otherHostsKeepThem() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        transformer.registerMethodRedirect("test/forgereads/Old", "old", "()V", "test/forgereads/New", "now", "()V");
        assertEquals(List.of("INVOKEVIRTUAL " + EVENT + "RenderLivingEvent$Pre.getPartialTick()F"),
                LegacyEventMembersBridgeTest.calls(transform(EVENT + "RenderLivingEvent$Pre.getPartialTick()F")));
    }

    // ---- fixtures ----

    private void forge(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        if (host.equals("26.3")) new Forge_26_2_to_26_3().registerRedirects(transformer);
    }

    /** One method per call {@code owner.name(desc)} on a null receiver, or {@code static owner.name(desc)} without one. */
    private ClassNode transform(String... calls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        int index = 0;
        for (String call : calls) {
            boolean isStatic = call.startsWith("static ");
            String member = isStatic ? call.substring(7) : call;
            String owner = member.substring(0, member.indexOf('.'));
            String name = member.substring(member.indexOf('.') + 1, member.indexOf('('));
            String desc = member.substring(member.indexOf('('));
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call" + index++, "()V", null, null);
            mv.visitCode();
            if (!isStatic) {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
            }
            mv.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, owner, name, desc, false);
            mv.visitInsn(Type.getReturnType(desc).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        byte[] out = transformer.transformClass(bytes, MOD_CLASS);
        ClassNode node = new ClassNode();
        new ClassReader(out != null ? out : bytes).accept(node, 0);
        return node;
    }

    private static MethodInsnNode firstCall(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) return call;
            }
        }
        throw new AssertionError("no call");
    }
}
