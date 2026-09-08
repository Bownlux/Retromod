/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.mixin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A generated bridge has to declare enough stack for the code it emits.
 *
 * <p>A bridged class is re-emitted without stack computation, so whatever the generator puts in
 * {@code maxStack} is what ships. It was counted by hand, and the Pose invoker got it wrong: the
 * body peaks at seven slots and the method declared six. That is a {@code VerifyError} at class
 * load, and it reaches the user as the mod failing to load with nothing pointing at the bridge.
 * Found by running a dataflow analysis over a real transformed mod, not by a unit test, which is why
 * this one exists.
 */
class BridgeMaxStackTest {

    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String POSE_CTOR_DESC = "(Ljava/lang/String;I)Lnet/minecraft/world/entity/Pose;";

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String POSE = "net/minecraft/world/entity/Pose";

    /** A mixin carrying the old two-argument Pose enum factory, the shape the bridge looks for. */
    private static ClassNode poseMixin() {
        ClassNode cn = new ClassNode();
        // A CLASS mixin, not an interface: the bridge only emits a call to a private target member
        // when the mixin merges into the target.
        cn.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mixin/PoseMixin", null,
                "java/lang/Object", null);
        AnnotationNode mixin = new AnnotationNode(MIXIN);
        mixin.values = new ArrayList<>(List.of("value",
                new ArrayList<>(List.of(Type.getObjectType(POSE)))));
        cn.visibleAnnotations = new ArrayList<>(List.of(mixin));

        MethodNode invoker = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT,
                "newPose", POSE_CTOR_DESC, null, null);
        AnnotationNode ann = new AnnotationNode(INVOKER);
        ann.values = new ArrayList<>(List.of("value", "<init>"));
        invoker.visibleAnnotations = new ArrayList<>(List.of(ann));
        cn.methods.add(invoker);
        return cn;
    }

    /** Every generated body must survive a stack-depth analysis against its declared maxStack. */
    private static void assertStackFits(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            assertDoesNotThrow(
                    () -> new Analyzer<BasicValue>(new BasicVerifier()).analyze(cn.name, m),
                    m.name + m.desc + " declares maxStack=" + m.maxStack
                    + ", which the emitted body exceeds; that is a VerifyError at class load");
        }
    }

    @Test
    @DisplayName("the Pose invoker declares enough stack for the body it emits")
    void poseInvokerStackFits() {
        ClassNode cn = poseMixin();
        boolean applied = MixinLegacyMemberBridge.apply(cn);
        assumeApplied(applied, "the Pose bridge did not recognize its own fixture");

        MethodNode invoker = cn.methods.stream()
                .filter(m -> m.desc.equals(POSE_CTOR_DESC))
                .findFirst().orElseThrow();
        assertTrue(invoker.maxStack >= 7,
                "the body pushes seven values before the constructor call, so six is not enough; "
                + "declared " + invoker.maxStack);
        assertStackFits(cn);
    }

    @Test
    @DisplayName("maxStack is computed, not carried from the generator's own count")
    void maxStackIsComputedNotHandWritten() {
        // Nothing in the emitted body changed here; what changed is that the value comes from ASM.
        // A generator that grows its body by one instruction must not silently ship a stale count.
        ClassNode cn = poseMixin();
        assumeApplied(MixinLegacyMemberBridge.apply(cn), "fixture not recognized");

        MethodNode invoker = cn.methods.stream()
                .filter(m -> m.desc.equals(POSE_CTOR_DESC))
                .findFirst().orElseThrow();
        int peak = peakStack(invoker);
        assertEquals(peak, invoker.maxStack,
                "maxStack should equal the real peak; a hand-written number drifts from the body");
    }

    /** Straight-line peak stack depth, which is all these generated bodies need. */
    private static int peakStack(MethodNode m) {
        int cur = 0, peak = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
            int op = insn.getOpcode();
            if (op < 0) continue;
            cur += stackDelta(insn, op);
            peak = Math.max(peak, cur);
        }
        return peak;
    }

    private static int stackDelta(org.objectweb.asm.tree.AbstractInsnNode insn, int op) {
        switch (op) {
            case Opcodes.NEW: case Opcodes.ACONST_NULL: case Opcodes.GETSTATIC:
                return op == Opcodes.GETSTATIC
                        ? Type.getType(((org.objectweb.asm.tree.FieldInsnNode) insn).desc).getSize()
                        : 1;
            case Opcodes.DUP: return 1;
            case Opcodes.ALOAD: case Opcodes.ILOAD: case Opcodes.FLOAD: return 1;
            case Opcodes.LLOAD: case Opcodes.DLOAD: return 2;
            case Opcodes.ASTORE: case Opcodes.ISTORE: case Opcodes.FSTORE: return -1;
            case Opcodes.LSTORE: case Opcodes.DSTORE: return -2;
            case Opcodes.ARETURN: case Opcodes.IRETURN: case Opcodes.FRETURN: return -1;
            case Opcodes.CHECKCAST: case Opcodes.INSTANCEOF: return 0;
            case Opcodes.RETURN: return 0;
            default:
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                    int delta = -(Type.getArgumentsAndReturnSizes(call.desc) >> 2)
                            + (Type.getArgumentsAndReturnSizes(call.desc) & 0x03);
                    // getArgumentsAndReturnSizes counts the receiver slot; a static call has none.
                    if (op == Opcodes.INVOKESTATIC) delta += 1;
                    return delta;
                }
                return 0;
        }
    }

    private static void assumeApplied(boolean applied, String why) {
        assertTrue(applied, why);
    }
}
