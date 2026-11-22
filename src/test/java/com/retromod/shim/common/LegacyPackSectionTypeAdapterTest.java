/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static com.retromod.shim.common.LegacyPackSectionTypeAdapter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Moonlight's generated packs answer {@code getMetadataSection(type)} only for the old single {@code PackMetadataSection.TYPE}. */
class LegacyPackSectionTypeAdapterTest {

    private static final String TYPE_DESC = "L" + TYPE + ";";

    @Test
    @DisplayName("a pack compared with the old TYPE answers the host's client, server and fallback types, and no other")
    void comparisonMatchesEachPackType() throws Exception {
        HostStubs host = new HostStubs();
        host.add(HostStubs.plainClass(TYPE, "java/lang/Object"));
        host.add(splitMetadata());
        host.add(generateHelper());
        // As written, and as the field redirect leaves it before this pass.
        host.add(apply(modPack("mod/Pack", "TYPE"), "1.21.1"));
        host.add(apply(modPack("mod/RedirectedPack", "FALLBACK_TYPE"), null));

        Class<?> metadata = host.load(METADATA);
        for (String pack : new String[] {"mod/Pack", "mod/RedirectedPack"}) {
            var answer = host.method(pack, "section");
            for (String field : new String[] {"CLIENT_TYPE", "SERVER_TYPE", "FALLBACK_TYPE"}) {
                assertEquals("pack.mcmeta", answer.invoke(null, metadata.getField(field).get(null)), pack + " " + field);
            }
            assertNull(answer.invoke(null, host.load(TYPE).getConstructor().newInstance()), pack + ": another section type");
            assertSame(metadata.getField("FALLBACK_TYPE").get(null), host.method(pack, "type").invoke(null),
                    pack + ": a type read for any other use is the fallback type");
        }
    }

    @Test
    @DisplayName("a class that does not name the pack metadata is returned as it is")
    void untouchedClass() {
        byte[] plain = HostStubs.plainClass("mod/Other", "java/lang/Object");
        assertSame(plain, apply(plain, "1.21.1"));
        byte[] helper = generateHelper();
        assertSame(helper, apply(helper, "1.21.1"), "the helper compares with the host's types and is never rewritten");
    }

    @Test
    @DisplayName("a mod built for 1.21.9 or newer keeps its own comparison with the fallback type")
    void newerModKeepsFallbackComparison() {
        byte[] pack = modPack("mod/NewPack", "FALLBACK_TYPE");
        assertSame(pack, apply(pack, "1.21.9"));
        assertSame(pack, apply(pack, "26.3"));
    }

    /** The 1.21.9 and newer shape: three type constants, made when the class loads. */
    private static byte[] splitMetadata() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, METADATA, null, "java/lang/Object", null);
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (String field : new String[] {"CLIENT_TYPE", "SERVER_TYPE", "FALLBACK_TYPE"}) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, field, TYPE_DESC, null, null).visitEnd();
            clinit.visitTypeInsn(NEW, TYPE);
            clinit.visitInsn(DUP);
            clinit.visitMethodInsn(INVOKESPECIAL, TYPE, "<init>", "()V", false);
            clinit.visitFieldInsn(PUTSTATIC, METADATA, field, TYPE_DESC);
        }
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code section(type)}: {@code type == PackMetadataSection.<field> ? "pack.mcmeta" : null}, and {@code type()} returning the field. */
    private static byte[] modPack(String name, String field) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "section", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        Label other = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, METADATA, field, TYPE_DESC);
        mv.visitJumpInsn(IF_ACMPNE, other);
        mv.visitLdcInsn("pack.mcmeta");
        mv.visitInsn(ARETURN);
        mv.visitLabel(other);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        MethodVisitor type = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "type", "()Ljava/lang/Object;", null, null);
        type.visitCode();
        type.visitFieldInsn(GETSTATIC, METADATA, field, TYPE_DESC);
        type.visitInsn(ARETURN);
        type.visitMaxs(0, 0);
        type.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
