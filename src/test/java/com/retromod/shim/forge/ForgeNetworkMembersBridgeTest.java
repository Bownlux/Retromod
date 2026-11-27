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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The Forge 1.20.1 network members the corpus surveys found missing on NetworkShim, rewritten onto members it has. */
class ForgeNetworkMembersBridgeTest {

    private static final String FORGE = "net/minecraftforge/network/";
    private static final String DIRECTION = FORGE + "NetworkDirection";
    private static final String CHANNEL = FORGE + "simple/SimpleChannel";
    private static final String BUILDER = CHANNEL + "$MessageBuilder";
    private static final String DISTRIBUTOR = FORGE + "PacketDistributor";
    private static final String TARGET = DISTRIBUTOR + "$PacketTarget";
    private static final String PACKET = "net/minecraft/network/protocol/Packet";
    private static final String SHIM = "com/retromod/shim/forge/embedded/NetworkShim";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void bridge() {
        transformer.clearRedirectsForTesting();
        transformer.setModSourceVersion("1.20.1");
        Forge_1_20_to_NeoForge_1_21.registerNetworkBridge(transformer);
    }

    @AfterEach
    void clear() {
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Every member a Forge 1.20.1 network user names resolves on NetworkShim after translation")
    void everyMemberResolves() throws Exception {
        List<AbstractInsnNode> out = instructions(transformer.transformClass(networkUser(), "test/Net"));
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (AbstractInsnNode insn : out) {
            if (insn instanceof MethodInsnNode call && call.owner.startsWith(SHIM)) {
                checked++;
                if (!declares(call.owner, call.name, call.desc, false)) missing.add(call.owner + "." + call.name + call.desc);
            } else if (insn instanceof FieldInsnNode field && field.owner.startsWith(SHIM)) {
                checked++;
                if (!declares(field.owner, field.name, field.desc, true)) missing.add(field.owner + "." + field.name);
            }
        }
        assertTrue(checked >= 11, "the calls must reach NetworkShim: " + checked);
        assertTrue(missing.isEmpty(), "NetworkShim lacks: " + missing);
    }

    @Test
    @DisplayName("A direction's side and a channel's vanilla packet come back as Object and are cast to the mod's types")
    void loaderAndMinecraftResultsAreCastBack() {
        List<AbstractInsnNode> out = instructions(transformer.transformClass(networkUser(), "test/Net"));
        assertCastAfter(out, "getReceptionSide", "net/minecraftforge/fml/LogicalSide");
        assertCastAfter(out, "getOriginationSide", "net/neoforged/fml/LogicalSide");
        assertCastAfter(out, "toVanillaPacket", PACKET);
        MethodInsnNode send = call(out, "send");
        assertEquals(SHIM + "$PacketDistributor$PacketTarget", send.owner);
        assertEquals("(Ljava/lang/Object;)V", send.desc, "the built packet is passed as Object");
    }

    /** One use of each member: a direction's sides, the channel's packet, a target's send and direction, and the rest. */
    private static byte[] networkUser() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Net", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "use",
                "(L" + CHANNEL + ";L" + TARGET + ";L" + BUILDER + ";Ljava/util/function/BiFunction;Ljava/lang/Object;)V",
                null, null);
        mv.visitCode();
        String direction = "L" + DIRECTION + ";";
        sideCall(mv, "getReceptionSide", "Lnet/minecraftforge/fml/LogicalSide;");
        sideCall(mv, "getOriginationSide", "Lnet/neoforged/fml/LogicalSide;");
        // channel.toVanillaPacket(message, PLAY_TO_CLIENT)
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitFieldInsn(GETSTATIC, DIRECTION, "PLAY_TO_CLIENT", direction);
        mv.visitMethodInsn(INVOKEVIRTUAL, CHANNEL, "toVanillaPacket", "(Ljava/lang/Object;" + direction + ")L" + PACKET + ";", false);
        mv.visitVarInsn(ASTORE, 5);
        // target.send(packet); target.getDirection()
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEVIRTUAL, TARGET, "send", "(L" + PACKET + ";)V", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, TARGET, "getDirection", "()" + direction, false);
        mv.visitInsn(POP);
        // new PacketDistributor(functor, PLAY_TO_CLIENT)
        mv.visitTypeInsn(NEW, DISTRIBUTOR);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitFieldInsn(GETSTATIC, DIRECTION, "PLAY_TO_CLIENT", direction);
        mv.visitMethodInsn(INVOKESPECIAL, DISTRIBUTOR, "<init>", "(Ljava/util/function/BiFunction;" + direction + ")V", false);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, FORGE + "NetworkRegistry", "ACCEPTVANILLA", "Ljava/lang/String;");
        mv.visitInsn(POP);
        // The login members of the message builder.
        String self = ")L" + BUILDER + ";";
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "loginIndex", "(Ljava/util/function/Function;Ljava/util/function/BiConsumer;" + self, false);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "buildLoginPacketList", "(Ljava/util/function/Function;" + self, false);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "markAsLoginPacket", "(" + self, false);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "noResponse", "(" + self, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void sideCall(MethodVisitor mv, String name, String side) {
        mv.visitFieldInsn(GETSTATIC, DIRECTION, "PLAY_TO_SERVER", "L" + DIRECTION + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, DIRECTION, name, "()" + side, false);
        mv.visitInsn(POP);
    }

    private static void assertCastAfter(List<AbstractInsnNode> out, String name, String type) {
        MethodInsnNode call = call(out, name);
        assertTrue(call.desc.endsWith(")Ljava/lang/Object;"), name + " must return Object on the shim: " + call.desc);
        AbstractInsnNode next = out.get(out.indexOf(call) + 1);
        assertTrue(next instanceof TypeInsnNode cast && cast.getOpcode() == CHECKCAST && cast.desc.equals(type),
                name + "'s result must be cast back to " + type);
    }

    private static MethodInsnNode call(List<AbstractInsnNode> out, String name) {
        return out.stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError("no call to " + name));
    }

    private static List<AbstractInsnNode> instructions(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<AbstractInsnNode> out = new ArrayList<>();
        node.methods.stream().filter(m -> m.name.equals("use")).findFirst().orElseThrow().instructions.forEach(out::add);
        return out;
    }

    /** Whether Retromod's compiled class declares the member, as the game resolves it. */
    private static boolean declares(String owner, String name, String desc, boolean field) throws Exception {
        try (InputStream in = ForgeNetworkMembersBridgeTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            if (in == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            if (field) return node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc));
            return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
        }
    }
}
