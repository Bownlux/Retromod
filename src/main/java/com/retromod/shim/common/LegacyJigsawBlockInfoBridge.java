/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Answers 26.2's {@code JigsawBlockInfo} members, which 26.3 rebuilt around a position and a state instead of a block info. */
public final class LegacyJigsawBlockInfoBridge {

    static final String JBI = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$JigsawBlockInfo";
    static final String SBI = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo";
    static final String HELPER = "com/retromod/generated/LegacyJigsawBlockInfo";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String JOINT = "net/minecraft/world/level/block/entity/JigsawBlockEntity$JointType";
    private static final String ID = "Lnet/minecraft/resources/Identifier;";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String TAG = "net/minecraft/nbt/CompoundTag";
    static final String INFO_DESC = "()L" + SBI + ";";
    static final String OLD_CTOR = "(L" + SBI + ";L" + JOINT + ";" + ID + KEY + ID + "II)V";
    static final String NEW_CTOR = "(" + POS + STATE + "L" + JOINT + ";" + ID + KEY + ID + "II)V";
    /** The release that rebuilt the record. */
    static final String VERSION = "26.3";

    private LegacyJigsawBlockInfoBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostRebuiltIt(host)) return;
        t.registerSyntheticClass(HELPER, helper());
        String jbi = "L" + JBI + ";";
        String sbi = "L" + SBI + ";";
        t.registerMethodRedirect(JBI, "info", INFO_DESC, HELPER, "info", "(" + jbi + ")" + sbi, true);
        t.limitRedirectToModsBefore(JBI, "info", INFO_DESC, VERSION);
        t.registerMethodRedirect(JBI, "of", "(" + sbi + ")" + jbi, JBI, "parse", "(" + sbi + ")" + jbi);
        t.limitRedirectToModsBefore(JBI, "of", "(" + sbi + ")" + jbi, VERSION);
        t.registerMethodRedirect(JBI, "withInfo", "(" + sbi + ")" + jbi, HELPER, "withInfo", "(" + jbi + sbi + ")" + jbi, true);
        t.limitRedirectToModsBefore(JBI, "withInfo", "(" + sbi + ")" + jbi, VERSION);
        t.registerConstructorRedirect(JBI, OLD_CTOR, HELPER, "create", OLD_CTOR.replace(")V", ")" + jbi));
        t.limitRedirectToModsBefore(JBI, "<init>", OLD_CTOR, VERSION);
    }

    /** Whether the host's record has no {@code info()}; offline or unreadable, a target from 26.3. */
    static boolean hostRebuiltIt(Function<String, ClassNode> host) {
        ClassNode jigsaw = host.apply(JBI);
        if (jigsaw == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, VERSION) >= 0;
        return jigsaw.methods.stream().noneMatch(m -> m.name.equals("info") && m.desc.equals(INFO_DESC));
    }

    /** The helper class: the old block info rebuilt, the old withInfo, and the old constructor. */
    static byte[] helper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        emitInfo(cw, "info", ACC_PUBLIC | ACC_STATIC);

        String jbi = "L" + JBI + ";";
        MethodVisitor with = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "withInfo", "(" + jbi + "L" + SBI + ";)" + jbi, null, null);
        with.visitCode();
        with.visitVarInsn(ALOAD, 0);
        with.visitVarInsn(ALOAD, 1);
        with.visitMethodInsn(INVOKEVIRTUAL, SBI, "pos", "()" + POS, false);
        with.visitVarInsn(ALOAD, 1);
        with.visitMethodInsn(INVOKEVIRTUAL, SBI, "state", "()" + STATE, false);
        with.visitMethodInsn(INVOKEVIRTUAL, JBI, "withInfo", "(" + POS + STATE + ")" + jbi, false);
        with.visitInsn(ARETURN);
        with.visitMaxs(0, 0);
        with.visitEnd();

        // The old constructor's block info gives the position and state; the jigsaw's fields are passed as they were.
        MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", OLD_CTOR.replace(")V", ")" + jbi), null, null);
        create.visitCode();
        create.visitTypeInsn(NEW, JBI);
        create.visitInsn(DUP);
        create.visitVarInsn(ALOAD, 0);
        create.visitMethodInsn(INVOKEVIRTUAL, SBI, "pos", "()" + POS, false);
        create.visitVarInsn(ALOAD, 0);
        create.visitMethodInsn(INVOKEVIRTUAL, SBI, "state", "()" + STATE, false);
        for (int slot = 1; slot <= 4; slot++) create.visitVarInsn(ALOAD, slot);
        create.visitVarInsn(ILOAD, 5);
        create.visitVarInsn(ILOAD, 6);
        create.visitMethodInsn(INVOKESPECIAL, JBI, "<init>", NEW_CTOR, false);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code static StructureBlockInfo name(JigsawBlockInfo)}: the block info a 26.2 jigsaw carried, with the jigsaw's
     * fields written back under the block entity's own keys, which old code reads from its tag.
     */
    static void emitInfo(ClassWriter cw, String name, int access) {
        MethodVisitor m = cw.visitMethod(access, name, "(L" + JBI + ";)L" + SBI + ";", null, null);
        m.visitCode();
        m.visitTypeInsn(NEW, TAG);
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, TAG, "<init>", "()V", false);
        m.visitVarInsn(ASTORE, 1);
        putString(m, "name", "name", "()" + ID);
        putString(m, "target", "target", "()" + ID);
        // The pool is a registry key; its tag holds the pool's id.
        m.visitVarInsn(ALOAD, 1);
        m.visitLdcInsn("pool");
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, JBI, "pool", "()" + KEY, false);
        m.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/resources/ResourceKey", "identifier", "()" + ID, false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, TAG, "putString", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        m.visitVarInsn(ALOAD, 1);
        m.visitLdcInsn("joint");
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, JBI, "jointType", "()L" + JOINT + ";", false);
        m.visitMethodInsn(INVOKEVIRTUAL, JOINT, "getSerializedName", "()Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, TAG, "putString", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        putInt(m, "placement_priority", "placementPriority");
        putInt(m, "selection_priority", "selectionPriority");
        m.visitTypeInsn(NEW, SBI);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, JBI, "pos", "()" + POS, false);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, JBI, "state", "()" + STATE, false);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESPECIAL, SBI, "<init>", "(" + POS + STATE + "L" + TAG + ";)V", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void putString(MethodVisitor m, String key, String getter, String desc) {
        m.visitVarInsn(ALOAD, 1);
        m.visitLdcInsn(key);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, JBI, getter, desc, false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, TAG, "putString", "(Ljava/lang/String;Ljava/lang/String;)V", false);
    }

    private static void putInt(MethodVisitor m, String key, String getter) {
        m.visitVarInsn(ALOAD, 1);
        m.visitLdcInsn(key);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, JBI, getter, "()I", false);
        m.visitMethodInsn(INVOKEVIRTUAL, TAG, "putInt", "(Ljava/lang/String;I)V", false);
    }
}
