/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Helper for {@link LegacyTooltipAdapter}, embedded into a pre-1.21.5 mod.
 *
 * <p>It is a {@code Consumer} that appends to a {@code List}, so a mod's old super call can hand
 * its tooltip list to the 1.21.5 method, which takes a {@code Consumer}. Registering it is also the
 * adapter's gate: only the 1.21.4 to 1.21.5 shims register it, so a host older than 1.21.5 never
 * runs the adapter. A mod built for 1.21.5 or newer has only the new descriptor and is left alone.
 */
public final class LegacyTooltipSynthetic {

    private LegacyTooltipSynthetic() {}

    public static final String INTERNAL = "com/retromod/shim/common/embedded/LegacyTooltipSink";

    static final String SINK_DESC = "(Ljava/util/List;)Ljava/util/function/Consumer;";

    /** Registers the helper, which enables the adapter for the mod being transformed. */
    public static void register(RetromodTransformer transformer) {
        if (!transformer.getSyntheticClasses().containsKey(INTERNAL)) {
            transformer.registerSyntheticClass(INTERNAL, generate());
        }
    }

    public static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, INTERNAL, null, "java/lang/Object",
                new String[]{"java/util/function/Consumer"});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "lines", "Ljava/util/List;", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "(Ljava/util/List;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, INTERNAL, "lines", "Ljava/util/List;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // static Consumer sink(List lines) { return new LegacyTooltipSink(lines); }
        MethodVisitor sink = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sink", SINK_DESC, null, null);
        sink.visitCode();
        sink.visitTypeInsn(NEW, INTERNAL);
        sink.visitInsn(DUP);
        sink.visitVarInsn(ALOAD, 0);
        sink.visitMethodInsn(INVOKESPECIAL, INTERNAL, "<init>", "(Ljava/util/List;)V", false);
        sink.visitInsn(ARETURN);
        sink.visitMaxs(0, 0);
        sink.visitEnd();

        // public void accept(Object line) { lines.add(line); }
        MethodVisitor accept = cw.visitMethod(ACC_PUBLIC, "accept", "(Ljava/lang/Object;)V", null, null);
        accept.visitCode();
        accept.visitVarInsn(ALOAD, 0);
        accept.visitFieldInsn(GETFIELD, INTERNAL, "lines", "Ljava/util/List;");
        accept.visitVarInsn(ALOAD, 1);
        accept.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        accept.visitInsn(POP);
        accept.visitInsn(RETURN);
        accept.visitMaxs(0, 0);
        accept.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
