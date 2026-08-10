/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.polyfill.fabric.FabricNetworkingPolyfill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fabric ClientPlayNetworking v1 raw-channel bridge on 26.1+ (#51): {@code send}/
 * {@code registerGlobalReceiver}/{@code canSend} redirect to {@code ClientPlayNetworkingV1Bridge},
 * and the {@code PlayChannelHandler} SAM is kept as a synthetic interface and class-redirected onto
 * it. The reflective packet round-trip only resolves on a 26.1 client, so it's verified in-game.
 * Host gate lives in {@link Fabric26ApiBridgeGateTest}.
 */
class FabricClientNetworkingV1ShimTest {

    private static final String OLD_CPN = "net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking";
    private static final String OLD_SAM = OLD_CPN + "$PlayChannelHandler";
    private static final String NEW_SAM = "com/retromod/generated/legacynet/LegacyPlayChannelHandler";
    private static final String BRIDGE  = "com/retromod/shim/api/fabric/embedded/ClientPlayNetworkingV1Bridge";
    private static final String ID  = "Lnet/minecraft/class_2960;";
    private static final String BUF = "Lnet/minecraft/class_2540;";

    @BeforeEach
    void pin26AndRegister() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new FabricClientNetworkingV1Shim().registerRedirects(t);
    }

    @AfterEach
    void restore() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.1"; // other shim tests expect this default
    }

    @Test
    void playChannelHandlerSamKeptAndRedirected() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        assertEquals(NEW_SAM, t.getClassRedirects().get(OLD_SAM),
                "removed PlayChannelHandler SAM must redirect to the kept synthetic");

        byte[] sam = t.getSyntheticClasses().get(NEW_SAM);
        assertNotNull(sam, "the synthetic SAM interface must be registered");
        int[] access = {0};
        boolean[] hasReceive = {false};
        new ClassReader(sam).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int a, String n, String s, String sup, String[] i) { access[0] = a; }
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                if ("receive".equals(n)) hasReceive[0] = true;
                return null;
            }
        }, 0);
        assertNotEquals(0, access[0] & Opcodes.ACC_INTERFACE, "kept SAM must be an interface");
        assertTrue(hasReceive[0], "kept SAM must declare receive(...)");
    }

    @Test
    void removedSendRedirectedToBridge() {
        byte[] out = RetromodTransformer.getInstance()
                .transformClass(fixtureCalling("send", "(" + ID + BUF + ")V"), "test/NetFixture");
        assertCallSite(out, "send");
    }

    @Test
    void removedRegisterGlobalReceiverRedirectedToBridge() {
        // class redirect rewrites the old-SAM handler param to NEW_SAM before the method redirect (keyed on NEW_SAM) matches
        byte[] out = RetromodTransformer.getInstance()
                .transformClass(fixtureCalling("registerGlobalReceiver", "(" + ID + "L" + OLD_SAM + ";)Z"),
                        "test/NetFixture");
        assertCallSite(out, "registerGlobalReceiver");
    }

    @Test
    void removedCanSendRedirectedToBridge() {
        byte[] out = RetromodTransformer.getInstance()
                .transformClass(fixtureCalling("canSend", "(" + ID + ")Z"), "test/NetFixture");
        assertCallSite(out, "canSend");
    }

    @Test
    void pre26HostUsesIntermediarySamAndSurvivesPolyfillRegistration() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        new FabricClientNetworkingV1Shim().registerRedirects(t);
        new FabricNetworkingPolyfill().registerPolyfills(t);

        byte[] sam = t.getSyntheticClasses().get(NEW_SAM);
        assertNotNull(sam);
        String[] receive = {null};
        new ClassReader(sam).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                if ("receive".equals(n)) receive[0] = d;
                return null;
            }
        }, 0);
        assertEquals("(Lnet/minecraft/class_310;Lnet/minecraft/class_634;"
                + "Lnet/minecraft/class_2540;Lnet/fabricmc/fabric/api/networking/v1/PacketSender;)V",
                receive[0]);

        byte[] out = t.transformClass(
                fixtureCalling("registerGlobalReceiver", "(" + ID + "L" + OLD_SAM + ";)Z"),
                "test/NetFixture");
        assertCallSite(out, "registerGlobalReceiver");
    }

    /** {@code test/NetFixture.go()} calling {@code ClientPlayNetworking.<name><desc>}. */
    private static byte[] fixtureCalling(String name, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/NetFixture", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "()V", null, null);
        mv.visitCode();
        for (Type ignored : Type.getArgumentTypes(desc)) mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, OLD_CPN, name, desc, false);
        if (Type.getReturnType(desc).getSort() != Type.VOID) mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Assert the named call now targets the bridge and no call to the removed API remains. */
    private static void assertCallSite(byte[] clazz, String name) {
        boolean[] toBridge = {false};
        boolean[] leftover = {false};
        new ClassReader(clazz).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int op, String o, String nm, String de, boolean itf) {
                        if (BRIDGE.equals(o) && name.equals(nm)) toBridge[0] = true;
                        if (OLD_CPN.equals(o) && name.equals(nm)) leftover[0] = true;
                    }
                };
            }
        }, 0);
        assertTrue(toBridge[0], name + "() must be redirected to " + BRIDGE);
        assertFalse(leftover[0], "no leftover call to the removed ClientPlayNetworking." + name + "()");
    }
}
