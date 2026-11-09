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

/** Answers the old single pack format number, read through {@code WorldVersion} and given to {@code PackMetadataSection}, with the major and minor form that replaced it. */
public final class LegacyPackVersionBridge {

    static final String WORLD_VERSION = "net/minecraft/WorldVersion";
    static final String PACK_TYPE = "net/minecraft/server/packs/PackType";
    static final String PACK_FORMAT = "net/minecraft/server/packs/metadata/pack/PackFormat";
    static final String HELPER = "com/retromod/generated/LegacyPackVersion";
    static final String OLD_DESC = "(L" + PACK_TYPE + ";)I";
    static final String HELPER_DESC = "(L" + WORLD_VERSION + ";L" + PACK_TYPE + ";)I";
    static final String METADATA = "net/minecraft/server/packs/metadata/pack/PackMetadataSection";
    static final String COMPONENT = "net/minecraft/network/chat/Component";
    static final String RANGE = "net/minecraft/util/InclusiveRange";
    // 1.21.1's constructors: a format with an optional supported range, and 1.20's format alone.
    static final String OLD_METADATA = "(L" + COMPONENT + ";ILjava/util/Optional;)V";
    static final String OLDEST_METADATA = "(L" + COMPONENT + ";I)V";
    static final String NEW_METADATA = "(L" + COMPONENT + ";L" + RANGE + ";)V";
    /** The first host checked without the old method; earlier hosts are read, or keep it offline. */
    static final String FIRST_CHECKED_HOST = "1.21.11";

    private LegacyPackVersionBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
        // The public metadata codec those mods read is gone from the same hosts, so the same shims answer it.
        LegacyPackMetadataCodecBridge.register(t);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostReplacedIt(host)) return;
        t.registerSyntheticClass(HELPER, helper());
        t.registerMethodRedirect(WORLD_VERSION, "getPackVersion", OLD_DESC, HELPER, "getPackVersion", HELPER_DESC, true);
        // Mods built for a host without the method never call it.
        t.limitRedirectToModsBefore(WORLD_VERSION, "getPackVersion", OLD_DESC, FIRST_CHECKED_HOST);
        // The pack metadata a mod builds from that number lost its number constructors in the same change.
        String section = "L" + METADATA + ";";
        t.registerConstructorRedirect(METADATA, OLD_METADATA, HELPER, "packMetadata",
                "(L" + COMPONENT + ";ILjava/util/Optional;)" + section);
        t.registerConstructorRedirect(METADATA, OLDEST_METADATA, HELPER, "packMetadata", "(L" + COMPONENT + ";I)" + section);
    }

    /** Whether the host has {@code packVersion} and no {@code getPackVersion}; offline or unreadable, a target from 1.21.11. */
    static boolean hostReplacedIt(Function<String, ClassNode> host) {
        ClassNode version = host.apply(WORLD_VERSION);
        if (version == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_CHECKED_HOST) >= 0;
        }
        return !declares(version, "getPackVersion", OLD_DESC)
                && declares(version, "packVersion", "(L" + PACK_TYPE + ";)L" + PACK_FORMAT + ";");
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(desc));
    }

    /** The helper: the old number is the format's major one, and an old metadata becomes a range of formats. */
    static byte[] helper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        emitGetPackVersion(cw);
        emitPackMetadata(cw);
        emitSection(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static int getPackVersion(WorldVersion version, PackType type) { return version.packVersion(type).major(); }} */
    private static void emitGetPackVersion(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getPackVersion", HELPER_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, WORLD_VERSION, "packVersion", "(L" + PACK_TYPE + ";)L" + PACK_FORMAT + ";", true);
        mv.visitMethodInsn(INVOKEVIRTUAL, PACK_FORMAT, "major", "()I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    /**
     * {@code packMetadata(description, format, supported)}: the range the old supported formats gave, widened to
     * the format, and {@code packMetadata(description, format)}: that format alone.
     */
    private static void emitPackMetadata(ClassWriter cw) {
        String section = "L" + METADATA + ";";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "packMetadata",
                "(L" + COMPONENT + ";ILjava/util/Optional;)" + section, null, null);
        mv.visitCode();
        mv.visitVarInsn(ILOAD, 1);
        mv.visitVarInsn(ISTORE, 3);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitVarInsn(ISTORE, 4);
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false);
        mv.visitJumpInsn(IFEQ, done);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, RANGE);
        mv.visitVarInsn(ASTORE, 5);
        String[][] ends = {{"minInclusive", "min", "3"}, {"maxInclusive", "max", "4"}};
        for (String[] end : ends) {
            int slot = Integer.parseInt(end[2]);
            mv.visitVarInsn(ILOAD, slot);
            mv.visitVarInsn(ALOAD, 5);
            mv.visitMethodInsn(INVOKEVIRTUAL, RANGE, end[0], "()Ljava/lang/Comparable;", false);
            mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", end[1], "(II)I", false);
            mv.visitVarInsn(ISTORE, slot);
        }
        mv.visitLabel(done);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitMethodInsn(INVOKESTATIC, HELPER, "section", "(L" + COMPONENT + ";II)" + section, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor single = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "packMetadata", "(L" + COMPONENT + ";I)" + section, null, null);
        single.visitCode();
        single.visitVarInsn(ALOAD, 0);
        single.visitVarInsn(ILOAD, 1);
        single.visitVarInsn(ILOAD, 1);
        single.visitMethodInsn(INVOKESTATIC, HELPER, "section", "(L" + COMPONENT + ";II)" + section, false);
        single.visitInsn(ARETURN);
        single.visitMaxs(0, 0);
        single.visitEnd();
    }

    /** Formats {@code min.0} to {@code max} with every minor version, since an old pack named only the major one. */
    private static void emitSection(ClassWriter cw) {
        String format = "L" + PACK_FORMAT + ";";
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "section", "(L" + COMPONENT + ";II)L" + METADATA + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, METADATA);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(NEW, RANGE);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, PACK_FORMAT, "of", "(I)" + format, false);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitLdcInsn(Integer.MAX_VALUE);
        mv.visitMethodInsn(INVOKESTATIC, PACK_FORMAT, "of", "(II)" + format, false);
        mv.visitMethodInsn(INVOKESPECIAL, RANGE, "<init>", "(Ljava/lang/Comparable;Ljava/lang/Comparable;)V", false);
        mv.visitMethodInsn(INVOKESPECIAL, METADATA, "<init>", NEW_METADATA, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
