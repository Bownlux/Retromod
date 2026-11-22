/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Keeps an old mod's own text content type, {@code ComponentContents.Type}, which the host replaced with {@code codec()}. */
public final class LegacyComponentContentsBridge {

    public static final String FLAG = "legacyComponentContents";
    static final String CONTENTS = "net/minecraft/network/chat/ComponentContents";
    static final String OLD_TYPE = CONTENTS + "$Type";
    /** The generated stand-in for the old type record, embedded per mod. */
    public static final String STAND_IN = "com/retromod/generated/LegacyComponentContentsType";
    static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    static final String CODEC_DESC = "()L" + MAP_CODEC + ";";
    static final String TYPE_DESC = "()L" + STAND_IN + ";";

    private LegacyComponentContentsBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostUsesCodec(host)) return;
        t.registerSyntheticClass(STAND_IN, generate());
        t.registerClassRedirect(OLD_TYPE, STAND_IN);
        t.enablePostProcess(FLAG);
    }

    /** Whether the host's text contents answer {@code codec()} and the old type class is gone. */
    static boolean hostUsesCodec(Function<String, ClassNode> host) {
        ClassNode contents = host.apply(CONTENTS);
        return contents != null && host.apply(OLD_TYPE) == null
                && contents.methods.stream().anyMatch(m -> m.name.equals("codec") && m.desc.equals(CODEC_DESC));
    }

    /**
     * Gives a mod's content class, which answers the old {@code type()}, the {@code codec()} the host
     * calls instead, read from that type. A class that declares {@code codec()} itself is left alone.
     */
    public static byte[] apply(byte[] classBytes) {
        // Only a class that names the stand-in can answer the old type(), so the rest are skipped unread.
        if (!new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains(STAND_IN)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if ((node.access & ACC_INTERFACE) != 0 || node.interfaces == null || !node.interfaces.contains(CONTENTS)) return classBytes;
        boolean hasType = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("codec") && method.desc.equals(CODEC_DESC)) return classBytes;
            hasType |= method.name.equals("type") && method.desc.equals(TYPE_DESC) && (method.access & ACC_STATIC) == 0;
        }
        if (!hasType) return classBytes;
        MethodVisitor codec = node.visitMethod(ACC_PUBLIC, "codec", CODEC_DESC, null, null);
        codec.visitCode();
        codec.visitVarInsn(ALOAD, 0);
        codec.visitMethodInsn(INVOKEVIRTUAL, node.name, "type", TYPE_DESC, false);
        codec.visitMethodInsn(INVOKEVIRTUAL, STAND_IN, "codec", CODEC_DESC, false);
        codec.visitInsn(ARETURN);
        codec.visitMaxs(1, 1);
        codec.visitEnd();
        // The new method has no branches, so the class keeps its frames as they are.
        ClassWriter out = new ClassWriter(0);
        node.accept(out);
        return out.toByteArray();
    }

    /** The stand-in: the old record's codec and id, and the serialized name the old type answered with. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, STAND_IN, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "codec", "L" + MAP_CODEC + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "id", "Ljava/lang/String;", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + MAP_CODEC + ";Ljava/lang/String;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, STAND_IN, "codec", "L" + MAP_CODEC + ";");
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 2);
        init.visitFieldInsn(PUTFIELD, STAND_IN, "id", "Ljava/lang/String;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        getter(cw, "codec", "codec", "L" + MAP_CODEC + ";");
        getter(cw, "id", "id", "Ljava/lang/String;");
        getter(cw, "getSerializedName", "id", "Ljava/lang/String;");
        getter(cw, "toString", "id", "Ljava/lang/String;");
        // A Fabric mod names the old record's accessors by their intermediary ids, which no map covers once the type is gone.
        getter(cw, "comp_1983", "codec", "L" + MAP_CODEC + ";");
        getter(cw, "comp_1984", "id", "Ljava/lang/String;");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void getter(ClassWriter cw, String name, String field, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "()" + desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, STAND_IN, field, desc);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
