/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 26.x GUI 2D-transform migration, Phase 0 (peephole).
 *
 * <p>26.x moved GUI rendering off the 3D {@code com.mojang.blaze3d.vertex.PoseStack} onto a 2D
 * {@code org.joml.Matrix3x2fStack}: {@code GuiGraphics.pose()} now returns the 2D stack, whose ops
 * are {@code pushMatrix()/popMatrix()} (fluent) rather than {@code pushPose()/popPose()} (void).
 * {@code PoseStack} still exists for 3D world rendering, so a type-blind redirect would corrupt 3D
 * (see the design RFC: {@code docs/design/gui-2d-transform-migration.md}).
 *
 * <p><b>Phase 0</b> handles the immediate, no-arg chain {@code guiGraphics.pose().pushPose()} /
 * {@code .popPose()}: {@code pose()} is IMMEDIATELY followed by the op (result consumed on the spot,
 * never stored), a self-contained peephole. Rewritten to {@code pose():Matrix3x2fStack} +
 * {@code pushMatrix()/popMatrix()} + {@code POP}.
 *
 * <p><b>Phase 1</b> handles the immediate arg-carrying float ops {@code guiGraphics.pose().translate
 * (x,y,z)} / {@code .scale(x,y,z)}. Here the {@code pose()} receiver is separated from the op by the
 * pushed args, so a {@code SourceInterpreter} dataflow confirms the op's receiver is a SINGLE
 * {@code GuiGraphics(Extractor).pose()} before rewriting to the 2D {@code translate(x,y)/scale(x,y)}
 * (the z is popped, the fluent {@code Matrix3x2f} result popped). A genuine 3D {@code PoseStack} (a
 * receiver from anywhere but {@code pose()}) is left untouched, so world rendering is never
 * corrupted.
 *
 * <p><b>Phase 2</b> handles the STORED-stack idiom most GUI code uses ({@code var p = gg.pose();
 * p.pushPose(); p.translate(...); p.popPose();}) by retyping the local: a slot is migrated only when
 * its SOLE store is a single {@code pose()} and EVERY load of it feeds a migratable op, so a genuine
 * 3D {@code PoseStack} (a param, a foreign source, a reassigned slot, or a load passed to a method)
 * is left untouched. See {@code migrateStoredStack}.
 *
 * <p>Still left untouched (unresolved exactly as before, never worse): the CROSS-METHOD pattern (a
 * pose stack passed to a render helper - interprocedural). <b>Phase 3</b> (this snapshot) added the
 * {@code double} translate overload and {@code mulPose(Quaternionf[c])} -> 2D {@code rotate}
 * (via the generated {@code RetroQuat2D.zAngle}); both plug into the same Phase 1/2 dataflow
 * proofs. Any re-emit failure returns the original bytes, so
 * this can only migrate a clean case or no-op; it can never make a class worse.
 *
 * <p>Runs both BEFORE the remap (NeoForge/Forge, Mojang-named) and AFTER it (Fabric, whose {@code
 * class_XXXX} names only become {@code GuiGraphics(Extractor)}/{@code PoseStack} post-remap); the
 * second pass is idempotent on an already-migrated class. Gated by the caller to 26.1+ hosts.
 */
public final class Gui2DTransformMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-gui2d");

    private static final String GUI = "net/minecraft/client/gui/GuiGraphics";
    private static final String GUI_EXTRACTOR = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String POSESTACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String MATRIX3X2 = "org/joml/Matrix3x2fStack";
    private static final String POSE_DESC_OLD = "()Lcom/mojang/blaze3d/vertex/PoseStack;";
    private static final String POSE_DESC_NEW = "()Lorg/joml/Matrix3x2fStack;";
    private static final String MATRIX_OP_DESC = "()Lorg/joml/Matrix3x2fStack;";

    private static final String MATRIX3X2F = "org/joml/Matrix3x2f"; // the 2D ops' declared owner/return

    /** void PoseStack op -> the fluent Matrix3x2fStack op it became (no-arg only for Phase 0). */
    private static final Map<String, String> NO_ARG_OPS = Map.of(
            "pushPose", "pushMatrix",
            "popPose", "popMatrix");

    /**
     * Arg-carrying 3D PoseStack ops on the GUI stack, keyed on {@code name+desc}. Phase 1 shipped
     * the {@code float} translate/scale overloads; <b>Phase 3</b> adds the {@code double} translate
     * overload (exact: drop z with {@code POP2}, then {@code D2F}-convert y and x with a
     * {@code DUP_X2/POP/SWAP} juggle) and the rotation {@code mulPose(Quaternionf[c])} (becomes the
     * 2D {@code rotate(zAngle)}, where the generated {@code RetroQuat2D.zAngle} extracts
     * {@code 2*atan2(z,w)}: exact for the pure-Z rotations that are the only meaningful GUI case;
     * a non-Z quaternion never rendered sanely in 2D screen space anyway). All 2D ops are fluent,
     * so their result is popped.
     */
    private static final int KIND_FLOAT3 = 0;   // (FFF)V   -> op(FF), drop z with POP
    private static final int KIND_DOUBLE3 = 1;  // (DDD)V   -> op(FF), POP2 + D2F juggle
    private static final int KIND_MULPOSE = 2;  // (Quat)V  -> rotate(F) via RetroQuat2D.zAngle

    private static final Map<String, int[]> ARG_OPS = Map.of(
            // key -> {kind, argValueEntries}; target name/desc resolved in applyArgOpRewrite
            "translate(FFF)V", new int[]{KIND_FLOAT3, 3},
            "scale(FFF)V",     new int[]{KIND_FLOAT3, 3},
            "translate(DDD)V", new int[]{KIND_DOUBLE3, 3},
            "mulPose(Lorg/joml/Quaternionfc;)V", new int[]{KIND_MULPOSE, 1},
            "mulPose(Lorg/joml/Quaternionf;)V",  new int[]{KIND_MULPOSE, 1});

    /** The generated quaternion-to-2D-angle helper (see {@link Quat2DSynthetic}). */
    static final String QUAT2D = "com/retromod/generated/RetroQuat2D";

    /**
     * Splice one confirmed arg-op rewrite: adjust the args for the 2D form, retarget the op to the
     * fluent 2D op, and pop its result. The receiver is already proven to be a GUI 2D stack.
     */
    private static void applyArgOpRewrite(MethodNode m, MethodInsnNode op) {
        int kind = ARG_OPS.get(op.name + op.desc)[0];
        switch (kind) {
            case KIND_FLOAT3 -> {
                // [recv, x, y, z] -> drop z.
                m.instructions.insertBefore(op, new InsnNode(Opcodes.POP));
                op.desc = "(FF)L" + MATRIX3X2F + ";";
            }
            case KIND_DOUBLE3 -> {
                // [recv, dx, dy, dz]: POP2 drops dz; D2F converts dy; DUP_X2 (cat1 over cat2) +
                // POP + D2F + SWAP converts dx under the already-converted fy.
                InsnList seq = new InsnList();
                seq.add(new InsnNode(Opcodes.POP2));
                seq.add(new InsnNode(Opcodes.D2F));
                seq.add(new InsnNode(Opcodes.DUP_X2));
                seq.add(new InsnNode(Opcodes.POP));
                seq.add(new InsnNode(Opcodes.D2F));
                seq.add(new InsnNode(Opcodes.SWAP));
                m.instructions.insertBefore(op, seq);
                op.desc = "(FF)L" + MATRIX3X2F + ";";
            }
            case KIND_MULPOSE -> {
                // [recv, quat] -> [recv, zAngleRadians]; Quaternionf implements Quaternionfc, so the
                // helper's interface param accepts either spelling.
                m.instructions.insertBefore(op, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        QUAT2D, "zAngle", "(Lorg/joml/Quaternionfc;)F", false));
                op.name = "rotate";
                op.desc = "(F)L" + MATRIX3X2F + ";";
            }
            default -> throw new IllegalStateException("kind " + kind);
        }
        op.owner = MATRIX3X2;
        m.instructions.insert(op, new InsnNode(Opcodes.POP)); // pop the fluent 2D result
    }

    private Gui2DTransformMigration() {}

    /**
     * Rewrite the immediate {@code guiGraphics.pose().pushPose()/popPose()} peephole. Returns the
     * input unchanged when nothing matches or on any failure (so it can never ship broken bytecode).
     */
    public static byte[] migrate(byte[] classBytes) {
        // Cheap pre-filter: skip the parse unless a pushPose/popPose (Phase 0) or a PoseStack ref
        // (Phase 1's translate/scale ops resolve on PoseStack) is even in the constant pool.
        if (!referencesPoseOp(classBytes)) return classBytes;
        try {
            ClassReader cr = new ClassReader(classBytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);
            int rewrites = 0;
            for (MethodNode m : cn.methods) rewrites += migrateMethod(m, cn.name);
            if (rewrites == 0) return classBytes;
            SafeClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            byte[] out = cw.toByteArray();
            LOGGER.debug("GUI 2D migration: rewrote {} pose().push/popPose peephole(s) in {}",
                    rewrites, cn.name);
            return out;
        } catch (Throwable t) {
            // Never ship a failed transform: leave the class exactly as it was.
            LOGGER.debug("GUI 2D migration skipped a class ({}); left unchanged", t.toString());
            return classBytes;
        }
    }

    private static int migrateMethod(MethodNode m, String className) {
        // Phase 2 (stored-stack locals) first, then Phase 1 (immediate arg ops), both on bytecode
        // untouched by Phase 0's adjacency loop below (all three target disjoint op instances: a
        // pose() result is either stored, immediately-consumed with args, or immediately-consumed
        // no-arg). The dataflow phases re-analyze as needed; each op is migrated by exactly one.
        int n = migrateStoredStack(m, className);
        n += migrateArgOps(m, className);
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode op)) continue;
            if (op.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            String matrixOp = NO_ARG_OPS.get(op.name);
            if (matrixOp == null || !POSESTACK.equals(op.owner) || !"()V".equals(op.desc)) continue;

            // The receiver must be produced by an IMMEDIATELY-preceding GuiGraphics(Extractor).pose().
            AbstractInsnNode prev = prevRealInsn(op);
            if (!(prev instanceof MethodInsnNode pose)) continue;
            if (pose.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !"pose".equals(pose.name)
                    || !(GUI.equals(pose.owner) || GUI_EXTRACTOR.equals(pose.owner))
                    || !POSE_DESC_OLD.equals(pose.desc)) {
                continue;
            }

            // Retype the pose() result to the 2D stack and turn the void op into the fluent 2D op,
            // popping its return. Stack effect is unchanged (receiver consumed, nothing left).
            pose.desc = POSE_DESC_NEW;
            op.owner = MATRIX3X2;
            op.name = matrixOp;
            op.desc = MATRIX_OP_DESC;
            m.instructions.insert(op, new InsnNode(Opcodes.POP));
            n++;
        }
        return n;
    }

    /** One confirmed Phase-1 rewrite: the op to retarget and the pose() call feeding its receiver. */
    private record ArgRewrite(MethodInsnNode op, MethodInsnNode pose) {}

    /**
     * Phase 1: rewrite {@code guiGraphics.pose().translate(x,y,z)} / {@code .scale(x,y,z)} (the
     * {@code float} overloads) to the 2D stack's {@code translate(x,y)} / {@code scale(x,y)}. Unlike
     * the no-arg peephole, the receiver (the {@code pose()} result) is separated from the op by the
     * pushed args, so a {@code SourceInterpreter} dataflow confirms the op's receiver was produced by
     * a SINGLE {@code GuiGraphics(Extractor).pose()} before touching anything. A merged / stored /
     * non-{@code pose()} receiver (i.e. a genuine 3D {@code PoseStack}) is left untouched, so world
     * rendering is never corrupted. The 2D op is fluent, so its returned {@code Matrix3x2f} is popped
     * and the dropped z is popped before the call; the net stack effect matches the old void op.
     */
    private static int migrateArgOps(MethodNode m, String className) {
        // Cheap scan for candidate ops before paying for the analysis.
        List<MethodInsnNode> candidates = new ArrayList<>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode op && op.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && POSESTACK.equals(op.owner) && ARG_OPS.containsKey(op.name + op.desc)) {
                candidates.add(op);
            }
        }
        if (candidates.isEmpty()) return 0;

        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(className, m);
        } catch (Exception e) {
            return 0; // unanalyzable: leave every candidate exactly as it was
        }

        // PASS 1: decide the rewrites from the (unmutated) frames, so instruction indices stay valid.
        List<ArgRewrite> rewrites = new ArrayList<>();
        for (MethodInsnNode op : candidates) {
            int idx = m.instructions.indexOf(op);
            if (idx < 0 || idx >= frames.length) continue;
            Frame<SourceValue> f = frames[idx];
            if (f == null) continue; // unreachable op
            // Receiver depth = 1 + the op's argument VALUE count (ASM analysis frames are
            // value-indexed, so a double is one entry): [receiver, args...].
            int recv = f.getStackSize() - 1 - ARG_OPS.get(op.name + op.desc)[1];
            if (recv < 0) continue;
            SourceValue rv = f.getStack(recv);
            if (rv == null || rv.insns.size() != 1) continue; // merged/ambiguous producer -> skip
            AbstractInsnNode producer = rv.insns.iterator().next();
            if (!(producer instanceof MethodInsnNode pose)
                    || pose.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !"pose".equals(pose.name) || !POSE_DESC_OLD.equals(pose.desc)
                    || !(GUI.equals(pose.owner) || GUI_EXTRACTOR.equals(pose.owner))) {
                continue; // not a GuiGraphics.pose() receiver -> a real 3D PoseStack, leave it alone
            }
            rewrites.add(new ArgRewrite(op, pose));
        }

        // PASS 2: apply. Retype pose() to the 2D stack, adapt the args, retarget, pop the result.
        for (ArgRewrite r : rewrites) {
            r.pose().desc = POSE_DESC_NEW;
            applyArgOpRewrite(m, r.op());
        }
        return rewrites.size();
    }

    /**
     * Phase 2: the STORED-stack pattern most GUI code uses -
     * {@code var p = guiGraphics.pose(); p.pushPose(); p.translate(x,y,z); ...; p.popPose();}. The
     * op receivers are {@code ALOAD}s of a local, so a plain peephole can't reach them; instead a
     * per-slot analysis retypes the local (via its {@code pose()} store) to the 2D stack and migrates
     * every op on it.
     *
     * <p><b>Strictly conservative</b>, so 3D world rendering can never be corrupted: a slot is
     * migrated ONLY when (a) its sole store is a single {@code GuiGraphics(Extractor).pose()}
     * (a 3D {@code PoseStack} param has no store; a stack from any other source stores a non-pose
     * value; a reassigned/reused slot has >1 store - all excluded), and (b) EVERY load of the slot
     * feeds a migratable op (pushPose/popPose/translate(FFF)/scale(FFF)). If a load is used any other
     * way (passed to a method, the {@code double}/{@code mulPose} ops, ...) the whole slot is left
     * untouched. {@code COMPUTE_FRAMES} then retypes the local from the now-2D store, so no local
     * table surgery is needed.
     */
    private static int migrateStoredStack(MethodNode m, String className) {
        // 1. Candidate slots: a pose() call IMMEDIATELY stored (pose(); ASTORE slot). Track the store
        //    instruction; a slot seen with two different pose stores is dropped (ambiguous).
        Map<Integer, MethodInsnNode> slotPose = new HashMap<>();
        Set<Integer> drop = new HashSet<>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode pose) || !isGuiPose(pose)) continue;
            if (nextRealInsn(pose) instanceof VarInsnNode st && st.getOpcode() == Opcodes.ASTORE) {
                if (slotPose.putIfAbsent(st.var, pose) != null) drop.add(st.var);
            }
        }
        slotPose.keySet().removeAll(drop);
        if (slotPose.isEmpty()) return 0;

        // 2. Any ASTORE to a candidate slot that is NOT its pose store means the slot holds something
        //    else too (reassignment / slot reuse) -> drop it.
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode st && st.getOpcode() == Opcodes.ASTORE
                    && slotPose.containsKey(st.var) && prevRealInsn(st) != slotPose.get(st.var)) {
                drop.add(st.var);
            }
        }
        slotPose.keySet().removeAll(drop);
        if (slotPose.isEmpty()) return 0;

        // 3. Analyze; collect, per candidate slot, the migratable ops receiving an ALOAD of it, and
        //    the ALOADs that feed them. (Compute BEFORE any mutation so instruction indices hold.)
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(className, m);
        } catch (Exception e) {
            return 0;
        }
        Map<Integer, List<MethodInsnNode>> slotOps = new HashMap<>();
        Map<Integer, Set<AbstractInsnNode>> slotGoodLoads = new HashMap<>();
        int i = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext(), i++) {
            if (!(insn instanceof MethodInsnNode op) || op.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !POSESTACK.equals(op.owner)) continue;
            boolean noArg = NO_ARG_OPS.containsKey(op.name) && "()V".equals(op.desc);
            boolean argOp = ARG_OPS.containsKey(op.name + op.desc);
            if (!noArg && !argOp) continue;
            if (i >= frames.length || frames[i] == null) continue;
            int argEntries = argOp ? ARG_OPS.get(op.name + op.desc)[1] : 0; // value-indexed frames
            int recv = frames[i].getStackSize() - 1 - argEntries;
            if (recv < 0) continue;
            SourceValue rv = frames[i].getStack(recv);
            if (rv == null || rv.insns.size() != 1) continue;
            if (!(rv.insns.iterator().next() instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD || !slotPose.containsKey(load.var)) continue;
            slotOps.computeIfAbsent(load.var, k -> new ArrayList<>()).add(op);
            slotGoodLoads.computeIfAbsent(load.var, k -> new HashSet<>()).add(load);
        }

        // 4. Every ALOAD of a candidate slot must be one of those migratable-op receivers; if any load
        //    is used another way, the slot is unsafe to retype -> drop it.
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode ld && ld.getOpcode() == Opcodes.ALOAD
                    && slotPose.containsKey(ld.var)) {
                Set<AbstractInsnNode> good = slotGoodLoads.get(ld.var);
                if (good == null || !good.contains(ld)) drop.add(ld.var);
            }
        }
        slotPose.keySet().removeAll(drop);
        slotOps.keySet().retainAll(slotPose.keySet());
        if (slotOps.isEmpty()) return 0;

        // 5. Apply: retype each surviving slot's pose() to the 2D stack and migrate every op on it.
        int n = 0;
        for (Map.Entry<Integer, List<MethodInsnNode>> e : slotOps.entrySet()) {
            slotPose.get(e.getKey()).desc = POSE_DESC_NEW;
            for (MethodInsnNode op : e.getValue()) {
                if (NO_ARG_OPS.containsKey(op.name) && "()V".equals(op.desc)) {
                    op.owner = MATRIX3X2;
                    op.name = NO_ARG_OPS.get(op.name);
                    op.desc = MATRIX_OP_DESC;
                    m.instructions.insert(op, new InsnNode(Opcodes.POP)); // pop the fluent stack
                } else {
                    applyArgOpRewrite(m, op); // shared per-kind splice (float/double/mulPose)
                }
                n++;
            }
        }
        return n;
    }

    /** True if this is a {@code GuiGraphics(Extractor).pose()} returning the (old 3D) PoseStack. */
    private static boolean isGuiPose(MethodInsnNode pose) {
        return pose.getOpcode() == Opcodes.INVOKEVIRTUAL && "pose".equals(pose.name)
                && POSE_DESC_OLD.equals(pose.desc)
                && (GUI.equals(pose.owner) || GUI_EXTRACTOR.equals(pose.owner));
    }

    /** The next real bytecode instruction, skipping labels/line-numbers/frames. */
    private static AbstractInsnNode nextRealInsn(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getNext();
        while (p != null && (p instanceof LabelNode || p instanceof LineNumberNode || p instanceof FrameNode)) {
            p = p.getNext();
        }
        return p;
    }

    /** The previous real bytecode instruction, skipping labels/line-numbers/frames. */
    private static AbstractInsnNode prevRealInsn(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && (p instanceof LabelNode || p instanceof LineNumberNode || p instanceof FrameNode)) {
            p = p.getPrevious();
        }
        return p;
    }

    /** Cheap UTF-8 byte scan: does the class pool mention pushPose/popPose or PoseStack at all? */
    private static boolean referencesPoseOp(byte[] b) {
        return indexOf(b, "pushPose".getBytes()) >= 0 || indexOf(b, "popPose".getBytes()) >= 0
                || indexOf(b, "PoseStack".getBytes()) >= 0;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
