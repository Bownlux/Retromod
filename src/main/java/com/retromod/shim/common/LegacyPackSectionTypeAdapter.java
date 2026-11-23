/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;

import static org.objectweb.asm.Opcodes.*;

/** Reads the old single pack metadata type where the host split it into one type per pack type and a fallback. */
public final class LegacyPackSectionTypeAdapter {

    public static final String FLAG = "legacy-pack-section-type";
    static final String METADATA = "net/minecraft/server/packs/metadata/pack/PackMetadataSection";
    static final String TYPE = "net/minecraft/server/packs/metadata/MetadataSectionType";
    static final String HELPER = "com/retromod/generated/LegacyPackSectionTypes";
    /** The first release with a pack metadata type per pack type. */
    static final String FIRST_SPLIT_HOST = "1.21.9";
    private static final String TYPE_DESC = "L" + TYPE + ";";
    private static final byte[] NEEDLE = METADATA.getBytes(StandardCharsets.UTF_8);

    private LegacyPackSectionTypeAdapter() {}

    /**
     * Rewrites each comparison with the old {@code PackMetadataSection.TYPE}, which the field redirect has already
     * named {@code FALLBACK_TYPE}. Since 1.21.9 the host asks a pack for its client or server type, and for the
     * fallback only when that throws, so a pack that compares the type it is handed with the old {@code TYPE}, as
     * Moonlight's generated packs do, compares it with whichever of the three it is. Any other read, such as a type
     * passed on to read another pack, stays the fallback type, which reads the old file shape. A mod built before the
     * split never named the fallback type itself.
     */
    public static byte[] apply(byte[] bytes, String modVersion) {
        if (bytes == null || !contains(bytes, NEEDLE)) return bytes;
        // A mod built for the split names the fallback type itself and means it.
        if (modVersion != null && RetromodVersion.compareMcVersions(modVersion, FIRST_SPLIT_HOST) >= 0) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        // The helper's own comparisons, even as a mod's embedded copy, are the host's types already.
        if (node.name.endsWith(HELPER)) return bytes;
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode read) || read.getOpcode() != GETSTATIC || !read.owner.equals(METADATA)
                        || !(read.name.equals("TYPE") || read.name.equals("FALLBACK_TYPE"))) {
                    continue;
                }
                int next = insn.getNext() == null ? -1 : insn.getNext().getOpcode();
                if (next == IF_ACMPEQ || next == IF_ACMPNE) {
                    // The compared value sits just below, so it is matched against the pack types it could equal.
                    InsnList match = new InsnList();
                    match.add(new InsnNode(DUP));
                    match.add(new MethodInsnNode(INVOKESTATIC, HELPER, "matching", "(Ljava/lang/Object;)" + TYPE_DESC, false));
                    method.instructions.insert(insn, match);
                    method.instructions.remove(insn);
                    changed = true;
                } else if (read.name.equals("TYPE")) {
                    read.name = "FALLBACK_TYPE";
                    read.desc = TYPE_DESC;
                    changed = true;
                }
            }
        }
        if (!changed) return bytes;
        // DUP adds one stack slot, so the sizes are recomputed; the frames are unchanged.
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(out);
        return out.toByteArray();
    }

    /** {@code matching(type)}: the type itself when it is one of the host's three pack types, else the fallback type. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "matching", "(Ljava/lang/Object;)" + TYPE_DESC, null, null);
        mv.visitCode();
        org.objectweb.asm.Label same = new org.objectweb.asm.Label();
        for (String field : new String[] {"CLIENT_TYPE", "SERVER_TYPE", "FALLBACK_TYPE"}) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETSTATIC, METADATA, field, TYPE_DESC);
            mv.visitJumpInsn(IF_ACMPEQ, same);
        }
        mv.visitFieldInsn(GETSTATIC, METADATA, "FALLBACK_TYPE", TYPE_DESC);
        mv.visitInsn(ARETURN);
        mv.visitLabel(same);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(CHECKCAST, TYPE);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
