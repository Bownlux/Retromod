/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.shim.forge.embedded.LegacyEventResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;

import static com.retromod.shim.forge.LegacyEventResultAdapter.RESULT;
import static org.junit.jupiter.api.Assertions.*;

/** Forge's shared {@code Event.Result} against NeoForge's per-event result enums. */
class LegacyEventResultTest {

    private static final String POSITION_CHECK =
            "net/neoforged/neoforge/event/entity/living/MobSpawnEvent$PositionCheck";
    private static final String L_RESULT = "L" + RESULT + ";";

    /** The shape of {@code MobSpawnEvent.PositionCheck}: its own SUCCEED, DEFAULT, FAIL enum. */
    public enum SpawnResult { SUCCEED, DEFAULT, FAIL }

    public static final class PositionCheck {
        private SpawnResult result = SpawnResult.DEFAULT;
        public void setResult(SpawnResult result) { this.result = result; }
        public SpawnResult getResult() { return result; }
    }

    /** The shape of an interaction event that moved to {@code TriState}. */
    public enum TriState { TRUE, DEFAULT, FALSE }

    public static final class RightClickBlock {
        private TriState useBlock = TriState.DEFAULT;
        public void setUseBlock(TriState useBlock) { this.useBlock = useBlock; }
        public TriState getUseBlock() { return useBlock; }
    }

    @Test
    void aForgeDenyBecomesTheEventsOwnFailure() {
        PositionCheck event = new PositionCheck();

        LegacyEventResult.apply(event, LegacyEventResult.DENY, "setResult");

        assertEquals(SpawnResult.FAIL, event.getResult());
        assertEquals(LegacyEventResult.DENY, LegacyEventResult.read(event, "getResult"));
    }

    @Test
    void aForgeAllowBecomesATriStateTrue() {
        RightClickBlock event = new RightClickBlock();

        LegacyEventResult.apply(event, LegacyEventResult.ALLOW, "setUseBlock");

        assertEquals(TriState.TRUE, event.getUseBlock());
        assertEquals(LegacyEventResult.ALLOW, LegacyEventResult.read(event, "getUseBlock"));
    }

    @Test
    void anEventWithoutAResultIgnoresTheCallAndReadsDefault() {
        Object event = new Object();

        LegacyEventResult.apply(event, LegacyEventResult.ALLOW, "setResult");

        assertEquals(LegacyEventResult.DEFAULT, LegacyEventResult.read(event, "getResult"));
        assertFalse(LegacyEventResult.hasResult(event));
        assertTrue(LegacyEventResult.hasResult(new PositionCheck()));
    }

    @Test
    void resultCallsOnANeoForgeEventBecomeStaticCallsThatNameTheMethod() {
        ClassNode node = node(LegacyEventResultAdapter.apply(listener(POSITION_CHECK)));

        List<String> calls = new ArrayList<>();
        List<Object> names = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            if (insn instanceof LdcInsnNode ldc) names.add(ldc.cst);
        }

        assertTrue(calls.contains(RESULT + ".apply(Ljava/lang/Object;" + L_RESULT + "Ljava/lang/String;)V"),
                "setResult must reach the stand-in: " + calls);
        assertTrue(calls.contains(RESULT + ".read(Ljava/lang/Object;Ljava/lang/String;)" + L_RESULT),
                "getResult must reach the stand-in: " + calls);
        assertEquals(List.of("setResult", "getResult"), names, "each call passes the event's own method name");
    }

    @Test
    void aModsOwnEventIsLeftAlone() {
        byte[] original = listener("com/example/MyEvent");

        assertSame(original, LegacyEventResultAdapter.apply(original));
    }

    /** {@code event.setResult(Result.DENY); return event.getResult();} as a Forge listener wrote it. */
    private static byte[] listener(String eventOwner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/SpawnListener", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onCheck",
                "(L" + eventOwner + ";)" + L_RESULT, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, RESULT, "DENY", L_RESULT);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, eventOwner, "setResult", "(" + L_RESULT + ")V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, eventOwner, "getResult", "()" + L_RESULT, false);
        mv.visitInsn(Opcodes.ARETURN);
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
}
