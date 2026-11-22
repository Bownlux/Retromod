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

import static org.objectweb.asm.Opcodes.*;

/** Points the pack metadata serializer that newer hosts folded into MetadataSectionType at that type. */
public final class LegacyMetadataSectionBridge {

    private static final String PACKS = "net/minecraft/server/packs/";
    static final String OLD = PACKS + "metadata/MetadataSectionSerializer";
    static final String TYPE = PACKS + "metadata/MetadataSectionType";
    static final String HELPER = "com/retromod/generated/LegacyMetadataSections";
    private static final String METADATA = PACKS + "resources/ResourceMetadata";
    private static final String FROM_STREAM = "(L" + TYPE + ";Ljava/io/InputStream;)Ljava/lang/Object;";

    private LegacyMetadataSectionBridge() {}

    public static void register(RetromodTransformer t) {
        if (!hostFoldedIt(ClassResourceInspector.read(TYPE) != null, ClassResourceInspector.read(OLD) != null)) return;
        t.registerSyntheticClass(HELPER, generateHelper());
        // Every method that took a serializer takes the type now, and pack classes override the new form.
        t.registerClassRedirect(OLD, TYPE);
        t.registerMethodRedirect(TYPE, "getMetadataSectionName", "()Ljava/lang/String;", TYPE, "name", "()Ljava/lang/String;");
        // The static stream reader went with the serializer; 26.3 also renamed the class that held it.
        for (String owner : new String[]{PACKS + "AbstractPackResources", PACKS + "AbstractPackMetadataResources"}) {
            for (String serializer : new String[]{OLD, TYPE}) {
                t.registerMethodRedirect(owner, "getMetadataFromStream", "(L" + serializer + ";Ljava/io/InputStream;)Ljava/lang/Object;",
                        HELPER, "metadataFromStream", FROM_STREAM);
            }
        }
    }

    /** Whether the host has the type and no serializer; offline, a target from 1.21.4, the first without the serializer. */
    static boolean hostFoldedIt(boolean hasType, boolean hasOld) {
        if (!hasType && !hasOld) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.4") >= 0;
        return hasType && !hasOld;
    }

    /**
     * {@code metadataFromStream(type, in)}: the section from a pack.mcmeta stream, or null as the old reader gave, also
     * for a file it cannot read, which the old reader logged instead of throwing.
     */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_VOLATILE, "reported", "Z", null, null).visitEnd();
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "metadataFromStream", FROM_STREAM, null, null);
        m.visitCode();
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label failed = new org.objectweb.asm.Label();
        m.visitTryCatchBlock(start, end, failed, "java/lang/Exception");
        m.visitLabel(start);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESTATIC, METADATA, "fromJsonStream", "(Ljava/io/InputStream;)L" + METADATA + ";", true);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEINTERFACE, METADATA, "getSection", "(L" + TYPE + ";)Ljava/util/Optional;", true);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        m.visitLabel(end);
        m.visitInsn(ARETURN);
        // A broken file is reported once, with its cause, and reads as no section.
        m.visitLabel(failed);
        m.visitVarInsn(ASTORE, 2);
        org.objectweb.asm.Label quiet = new org.objectweb.asm.Label();
        m.visitFieldInsn(GETSTATIC, HELPER, "reported", "Z");
        m.visitJumpInsn(IFNE, quiet);
        m.visitInsn(ICONST_1);
        m.visitFieldInsn(PUTSTATIC, HELPER, "reported", "Z");
        com.retromod.util.GeneratedLog.pushLogger(m);
        m.visitLdcInsn("An old mod read pack metadata that could not be loaded, so it gets no section, as before. Further failures are not logged.");
        m.visitVarInsn(ALOAD, 2);
        com.retromod.util.GeneratedLog.warnWithCause(m);
        m.visitLabel(quiet);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
