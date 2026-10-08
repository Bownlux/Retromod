/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * 26.2 removed {@code Minecraft.getToastManager()}. The same {@code ToastManager} instance now
 * hangs off the client GUI as {@code Minecraft.gui.toastManager()} (verified against the 26.2 and
 * 26.3 client jars, where vanilla's own tutorial and narrator code reach it that way). Toasts are
 * the usual way a client mod reports an error or a notice, so the old call is common.
 *
 * <p>The receiver is a {@code Minecraft} on the stack and the replacement needs its {@code gui}
 * field first, so a plain rename cannot express it. A generated static forwarder takes the
 * receiver as its first argument, and the transformer devirtualizes the call onto it. That shape
 * also keeps a {@code @Redirect} or {@code @WrapOperation} handler on the old call valid, because
 * its parameters mirror {@code (receiver)} either way.
 *
 * <p>On 26.1 the manager was created early in {@code Minecraft}'s constructor, right after
 * {@code options}, while {@code gui} comes much later. A client entrypoint that asks for the
 * manager in that window would hit a null {@code gui}. The forwarder then returns one early
 * manager built the way 26.1 built its own, and {@code null} before {@code options} exists,
 * which is what 26.1 returned there. A toast queued on the early manager is not shown, because
 * the GUI later creates its own.
 *
 * <p>The forwarder references 26.2 classes Retromod cannot see at build time, so it is generated
 * and registered as a synthetic for the per-mod embedder. 26.2 epoch: registered from
 * {@link Mc26_1To26_2CoreMoves} only.
 */
public final class LegacyToastManagerSynthetic {

    private LegacyToastManagerSynthetic() {}

    public static final String INTERNAL = "com/retromod/generated/LegacyToastManager";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    static final String TOAST_MANAGER_DESC =
            "()Lnet/minecraft/client/gui/components/toasts/ToastManager;";
    static final String FORWARDER_DESC =
            "(L" + MINECRAFT + ";)Lnet/minecraft/client/gui/components/toasts/ToastManager;";

    private static final String OPTIONS = "net/minecraft/client/Options";
    private static final String TOAST_MANAGER = "net/minecraft/client/gui/components/toasts/ToastManager";
    private static final String EARLY_FIELD = "early";

    public static byte[] generate() {
        // Frame merges only meet identical locals, so the writer never needs the MC hierarchy.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, INTERNAL, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, EARLY_FIELD, "L" + TOAST_MANAGER + ";", null, null).visitEnd();

        // getToastManager(mc): mc.gui != null ? mc.gui.toastManager() : early(mc)
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getToastManager",
                FORWARDER_DESC, null, null);
        mv.visitCode();
        Label noGui = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, MINECRAFT, "gui", "L" + GUI + ";");
        mv.visitInsn(DUP);
        mv.visitJumpInsn(IFNULL, noGui);
        mv.visitMethodInsn(INVOKEVIRTUAL, GUI, "toastManager", TOAST_MANAGER_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitLabel(noGui);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, INTERNAL, EARLY_FIELD, FORWARDER_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // early(mc): one manager built from mc.options, or null while options is still unset.
        mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNCHRONIZED, EARLY_FIELD, FORWARDER_DESC, null, null);
        mv.visitCode();
        Label ready = new Label();
        Label noOptions = new Label();
        mv.visitFieldInsn(GETSTATIC, INTERNAL, EARLY_FIELD, "L" + TOAST_MANAGER + ";");
        mv.visitJumpInsn(IFNONNULL, ready);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, MINECRAFT, "options", "L" + OPTIONS + ";");
        mv.visitJumpInsn(IFNULL, noOptions);
        mv.visitTypeInsn(NEW, TOAST_MANAGER);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, MINECRAFT, "options", "L" + OPTIONS + ";");
        mv.visitMethodInsn(INVOKESPECIAL, TOAST_MANAGER, "<init>",
                "(L" + MINECRAFT + ";L" + OPTIONS + ";)V", false);
        mv.visitFieldInsn(PUTSTATIC, INTERNAL, EARLY_FIELD, "L" + TOAST_MANAGER + ";");
        mv.visitLabel(ready);
        mv.visitFieldInsn(GETSTATIC, INTERNAL, EARLY_FIELD, "L" + TOAST_MANAGER + ";");
        mv.visitInsn(ARETURN);
        mv.visitLabel(noOptions);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    public static void register(RetromodTransformer t) {
        if (!t.getSyntheticClasses().containsKey(INTERNAL)) {
            t.registerSyntheticClass(INTERNAL, generate());
        }
        // Receiver-as-arg0 target: the transformer devirtualizes to INVOKESTATIC.
        t.registerMethodRedirect(MINECRAFT, "getToastManager", TOAST_MANAGER_DESC,
                INTERNAL, "getToastManager", FORWARDER_DESC);
    }
}
