/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Generates {@code com/retromod/polyfill/minecraft/RetroSimpleJsonReloadListener}: the synthesized
 * superclass half of the {@code SimpleJsonResourceReloadListener(Gson, String)} bridge (see
 * {@link com.retromod.polyfill.minecraft.RetroReloadScan}). It re-declares the deleted Gson-based
 * listener on top of 26.x's {@code SimplePreparableReloadListener} so a 1.21.x mod that extends the
 * old class (calling {@code super(gson, dir)} and overriding {@code apply(Map, ...)}) can be
 * superclass-rebased onto it and keep working.
 *
 * <p>It must be SYNTHESIZED (not a compiled class) because it {@code extends} a Minecraft type
 * Retromod can't see at build time. It's {@code abstract}: it implements the ctor and
 * {@code prepare(...)} (delegating the scan to the reflective helper) but leaves the erased
 * {@code apply(Object, ...)} unimplemented, which the rebased mod subclass already provides (its
 * javac-generated bridge to its own {@code apply(Map, ...)}). So the reload system's
 * {@code prepare()} -> our scan -> {@code apply(map)} flows straight into the mod's code.
 */
public final class ReloadListenerSynthetic {

    private ReloadListenerSynthetic() {}

    public static final String INTERNAL = "com/retromod/polyfill/minecraft/RetroSimpleJsonReloadListener";
    private static final String BASE = "net/minecraft/server/packs/resources/SimplePreparableReloadListener";
    private static final String RESOURCE_MANAGER = "net/minecraft/server/packs/resources/ResourceManager";
    private static final String PROFILER = "net/minecraft/util/profiling/ProfilerFiller";
    private static final String SCAN = "com/retromod/polyfill/minecraft/RetroReloadScan";
    private static final String GSON = "com/google/gson/Gson";

    /** ClassWriter whose frame computation never needs the (absent) MC hierarchy. */
    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    public static byte[] generate() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, INTERNAL, null, BASE, null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "gson", "L" + GSON + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "directory", "Ljava/lang/String;", null, null).visitEnd();

        // public <init>(Gson gson, String directory) { super(); this.gson = gson; this.directory = directory; }
        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(L" + GSON + ";Ljava/lang/String;)V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, BASE, "<init>", "()V", false);
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 1);
        c.visitFieldInsn(PUTFIELD, INTERNAL, "gson", "L" + GSON + ";");
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 2);
        c.visitFieldInsn(PUTFIELD, INTERNAL, "directory", "Ljava/lang/String;");
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        // protected Map prepare(ResourceManager rm, ProfilerFiller pf) {
        //     return RetroReloadScan.scan(this.gson, this.directory, rm);
        // }
        // The old SimpleJsonResourceReloadListener bound T = Map<Identifier, JsonElement>, so its
        // prepare returned Map (concrete). A rebased subclass' `super.prepare(...)` call and its own
        // prepare override are keyed on that concrete Map return, so we declare prepare()Map here.
        String prepMapDesc = "(L" + RESOURCE_MANAGER + ";L" + PROFILER + ";)Ljava/util/Map;";
        MethodVisitor p = cw.visitMethod(ACC_PROTECTED, "prepare", prepMapDesc, null, null);
        p.visitCode();
        p.visitVarInsn(ALOAD, 0);
        p.visitFieldInsn(GETFIELD, INTERNAL, "gson", "L" + GSON + ";");
        p.visitVarInsn(ALOAD, 0);
        p.visitFieldInsn(GETFIELD, INTERNAL, "directory", "Ljava/lang/String;");
        p.visitVarInsn(ALOAD, 1); // ResourceManager
        p.visitMethodInsn(INVOKESTATIC, SCAN, "scan",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
        p.visitTypeInsn(CHECKCAST, "java/util/Map");
        p.visitInsn(ARETURN);
        p.visitMaxs(0, 0);
        p.visitEnd();

        // The erased bridge protected Object prepare(RM, PF) { return this.prepare(rm, pf); } that
        // the T-binding class (formerly SimpleJsonResourceReloadListener) generated: it overrides
        // SimplePreparableReloadListener.prepare (erased Object), and virtual-dispatches to the
        // most-derived prepare()Map (the subclass' override, if any, else this class'). Without it a
        // rebased subclass that doesn't override prepare would inherit the abstract Object form.
        MethodVisitor b = cw.visitMethod(ACC_PROTECTED | ACC_BRIDGE | ACC_SYNTHETIC, "prepare",
                "(L" + RESOURCE_MANAGER + ";L" + PROFILER + ";)Ljava/lang/Object;", null, null);
        b.visitCode();
        b.visitVarInsn(ALOAD, 0);
        b.visitVarInsn(ALOAD, 1);
        b.visitVarInsn(ALOAD, 2);
        b.visitMethodInsn(INVOKEVIRTUAL, INTERNAL, "prepare", prepMapDesc, false);
        b.visitInsn(ARETURN);
        b.visitMaxs(0, 0);
        b.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
