/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.fabric.Fabric_1_21_4_to_1_21_5;
import com.retromod.shim.forge.Forge_1_21_4_to_1_21_5;
import com.retromod.shim.neoforge.NeoForge_1_21_4_to_1_21_5;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.LegacyChunkTicketBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A chunk-loading mod creating its ticket types and adding and releasing a region ticket, on every loader's 1.21.5 shim. */
class LegacyChunkTicketBridgeTest {

    private static final String RADIUS = LegacyChunkTicketBridge.NEW;
    private static final List<String> MOJANG_RESULT = List.of(
            CACHE + ".addTicketWithRadius" + RADIUS, CACHE + ".removeTicketWithRadius" + RADIUS,
            CACHE + ".addTicketWithRadius" + RADIUS, CACHE + ".removeTicketWithRadius" + RADIUS);

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void forgeAndNeoForgeBridgeAllFourMojangShapes() {
        for (VersionShim shim : List.of(new Forge_1_21_4_to_1_21_5(), new NeoForge_1_21_4_to_1_21_5())) {
            assertEquals(MOJANG_RESULT, calls(registered("26.3", shim), mojangCaller(CACHE, "addRegionTicket",
                    "removeRegionTicket")), shim.getShimName());
        }
    }

    @Test
    void aForge1201CallerInSrgNamesEndsOnTheRadiusMethods() {
        RetromodTransformer transformer = registered("1.21.11", new Forge_1_21_4_to_1_21_5());
        transformer.registerSrgNameMappings(
                Map.of("m_8387_", "addRegionTicket", "m_8438_", "removeRegionTicket"), Map.of());

        assertEquals(MOJANG_RESULT, calls(transformer, mojangCaller(CACHE, "m_8387_", "m_8438_")));
    }

    @Test
    void fabricOn26xBridgesTheMojangShapes() {
        assertEquals(MOJANG_RESULT, calls(registered("26.3", new Fabric_1_21_4_to_1_21_5()),
                mojangCaller(CACHE, "addRegionTicket", "removeRegionTicket")));
    }

    @Test
    void fabricOnAnIntermediaryHostBridgesTheIntermediaryIds() {
        RetromodTransformer transformer = registered("1.21.11", new Fabric_1_21_4_to_1_21_5());

        String newDesc = INTERMEDIARY_HEAD + ")V";
        assertEquals(List.of(INTERMEDIARY_CACHE + ".method_66009" + newDesc, INTERMEDIARY_CACHE + ".method_66010" + newDesc),
                calls(transformer, intermediaryCaller()));
        // An intermediary host never sees the Mojang names, so those keys stay unregistered.
        assertEquals(CACHE + ".addRegionTicket" + VANILLA,
                calls(transformer, mojangCaller(CACHE, "addRegionTicket", "removeRegionTicket")).get(0));
    }

    @Test
    void everyLoaderCreatesTicketTypesThroughTheRebuiltHelper() {
        String type = "L" + TICKET_TYPE + ";";
        List<String> expected = List.of(TYPES + ".create(Ljava/lang/String;Ljava/util/Comparator;)" + type,
                TYPES + ".create(Ljava/lang/String;Ljava/util/Comparator;I)" + type);
        for (VersionShim shim : List.of(new Forge_1_21_4_to_1_21_5(), new NeoForge_1_21_4_to_1_21_5(), new Fabric_1_21_4_to_1_21_5())) {
            RetromodTransformer transformer = registered("26.3", shim);
            assertEquals(expected, typeCalls(transformer, createCaller(TICKET_TYPE, "create", "create")), shim.getShimName());
            assertTrue(transformer.getSyntheticClasses().containsKey(TYPES), shim.getShimName() + " adds the helper to the mod");
        }
    }

    @Test
    void aForge1201TicketTypeInSrgNamesReachesTheRebuiltHelper() {
        RetromodTransformer transformer = registered("1.21.11", new Forge_1_21_4_to_1_21_5());
        transformer.registerSrgNameMappings(Map.of("m_9462_", "create", "m_9465_", "create"), Map.of());

        List<String> calls = typeCalls(transformer, createCaller(TICKET_TYPE, "m_9462_", "m_9465_"));
        assertEquals(2, calls.size());
        calls.forEach(call -> assertTrue(call.startsWith(TYPES + ".create("), call));
    }

    @Test
    void fabricOnAnIntermediaryHostCreatesThroughTheIntermediaryHelper() {
        RetromodTransformer transformer = registered("1.21.11", new Fabric_1_21_4_to_1_21_5());

        String type = "L" + INTERMEDIARY_TICKET_TYPE + ";";
        assertEquals(List.of(INTERMEDIARY_TYPES + ".create(Ljava/lang/String;Ljava/util/Comparator;)" + type,
                        INTERMEDIARY_TYPES + ".create(Ljava/lang/String;Ljava/util/Comparator;I)" + type),
                typeCalls(transformer, createCaller(INTERMEDIARY_TICKET_TYPE, "method_14291", "method_20628")));
        // The helper itself names the host's intermediary class, never the Mojang one it was compiled against.
        byte[] helper = transformer.getSyntheticClasses().get(INTERMEDIARY_TYPES);
        assertNotNull(helper, "the intermediary helper is added to the mod");
        ClassNode node = new ClassNode();
        new ClassReader(helper).accept(node, 0);
        assertTrue(node.methods.stream().anyMatch(m -> m.desc.endsWith(")" + type)), "create returns the intermediary type");
        assertFalse(node.methods.stream().anyMatch(m -> m.desc.contains(TICKET_TYPE)), "no Mojang name is left");
    }

    @Test
    void hostsBefore1215NeverTakeTheBridge() {
        for (VersionShim shim : List.of(new Forge_1_21_4_to_1_21_5(), new NeoForge_1_21_4_to_1_21_5(),
                new Fabric_1_21_4_to_1_21_5())) {
            assertFalse(ShimRegistry.isAvailableOnHost(shim, "1.21.4"), shim.getShimName());
        }
    }

    private static RetromodTransformer registered(String target, VersionShim shim) {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        shim.registerRedirects(transformer);
        return transformer;
    }

    /** Every call on the chunk cache, in order, after the transform. */
    private static List<String> calls(RetromodTransformer transformer, byte[] caller) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(caller, "mod/Tickets.class")).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && (call.owner.equals(CACHE) || call.owner.equals(INTERMEDIARY_CACHE))) {
                calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    /** Every static call, in order, after the transform. */
    private static List<String> typeCalls(RetromodTransformer transformer, byte[] caller) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(caller, "mod/Types.class")).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    /** A ticket type made the way Chunky makes its own: one that lasts until removed, then one that lapses after 300 ticks. */
    private static byte[] createCaller(String type, String create, String createTimed) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Types", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "types", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn("chunky");
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, type, create, "(Ljava/lang/String;Ljava/util/Comparator;)L" + type + ";", false);
        mv.visitInsn(POP);
        mv.visitLdcInsn("chunky_ticking");
        mv.visitInsn(ACONST_NULL);
        mv.visitIntInsn(SIPUSH, 300);
        mv.visitMethodInsn(INVOKESTATIC, type, createTimed, "(Ljava/lang/String;Ljava/util/Comparator;I)L" + type + ";", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Add and release with the vanilla 4-argument shape, then with the Forge forceTicks shape. */
    private static byte[] mojangCaller(String owner, String add, String remove) {
        return caller(owner, "Lnet/minecraft/server/level/TicketType;", "Lnet/minecraft/world/level/ChunkPos;",
                new String[][]{{add, VANILLA}, {remove, VANILLA}, {add, FORCE_TICKS}, {remove, FORCE_TICKS}});
    }

    /** Add and release as a Fabric 1.21.4 mod compiled them. */
    private static byte[] intermediaryCaller() {
        String oldDesc = INTERMEDIARY_HEAD + "Ljava/lang/Object;)V";
        return caller(INTERMEDIARY_CACHE, "Lnet/minecraft/class_3230;", "Lnet/minecraft/class_1923;",
                new String[][]{{"method_17297", oldDesc}, {"method_17300", oldDesc}});
    }

    private static byte[] caller(String owner, String ticketType, String chunkPos, String[][] calls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Tickets", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "hold",
                "(L" + owner + ";" + ticketType + chunkPos + "Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        for (String[] call : calls) {
            for (int slot = 0; slot <= 2; slot++) mv.visitVarInsn(ALOAD, slot);
            mv.visitInsn(ICONST_2);
            mv.visitVarInsn(ALOAD, 3);
            if (Type.getArgumentTypes(call[1]).length == 5) mv.visitInsn(ICONST_1);
            mv.visitMethodInsn(INVOKEVIRTUAL, owner, call[0], call[1], false);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
