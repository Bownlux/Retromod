/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

/**
 * 1.3.0: 26.2 moved 17 public {@code Gui} members onto the new {@code Hud} class (reached through
 * the public final {@code Gui.hud} field), including the most mod-called client-UI accessors there
 * are: {@code getChat()} (how a client mod prints chat messages), {@code getFont()},
 * {@code getTabList()}, {@code getBossOverlay()}, {@code getDebugOverlay()}, the whole title API
 * ({@code setTitle}/{@code setSubtitle}/{@code setTimes}/{@code clearTitles}/{@code resetTitleTimes}),
 * {@code setOverlayMessage}, and {@code getGuiTicks()}. Verified by a full 26.1-snapshot-10 vs 26.2
 * public-method diff; every descriptor is IDENTICAL on both sides, only the owner changed.
 *
 * <p>A plain method redirect can't express this: the receiver on the stack is a {@code Gui} sitting
 * UNDER the arguments, and the replacement needs {@code gui.hud} instead. So each moved instance
 * method gets a generated static forwarder {@code ret m(Gui g, args...) { return g.hud.m(args...); }}
 * on {@code com/retromod/generated/GuiToHudHop}, and the call is redirected there with the
 * receiver-as-arg0 shape (the transformer auto-devirtualizes: {@code INVOKEVIRTUAL Gui.m(args)} ->
 * {@code INVOKESTATIC GuiToHudHop.m(Gui,args)}; the stack is untouched). This shape is also
 * handler-preserving for {@code @Redirect}/{@code @WrapOperation}/{@code @WrapWithCondition}
 * mixins, whose handlers mirror {@code (receiver, args...)} for a virtual call and {@code (args...)}
 * for a static one: identical either way.
 *
 * <p>{@code getMobEffectSprite} was already static on Gui and is static on Hud, so it's a plain
 * owner-move method redirect, no forwarder.
 *
 * <p>The forwarder class must be generated (it references {@code Gui}/{@code Hud}, which Retromod
 * can't see at build time) and is registered as a synthetic so the per-mod embedder relocates a
 * JPMS-safe copy into referencing mods on Forge/NeoForge (Fabric injects it directly). 26.2 epoch:
 * registered from {@link Mc26_1To26_2CoreMoves} only.
 */
public final class GuiToHudHopSynthetic {

    private GuiToHudHopSynthetic() {}

    public static final String INTERNAL = "com/retromod/generated/GuiToHudHop";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String HUD = "net/minecraft/client/gui/Hud";
    private static final String HUD_DESC = "L" + HUD + ";";

    /** The 16 moved INSTANCE methods: name -> descriptor (identical on 26.1 Gui and 26.2 Hud). */
    private static final String[][] MOVED_INSTANCE = {
        {"getBossOverlay", "()Lnet/minecraft/client/gui/components/BossHealthOverlay;"},
        {"getChat", "()Lnet/minecraft/client/gui/components/ChatComponent;"},
        {"getDebugOverlay", "()Lnet/minecraft/client/gui/components/DebugScreenOverlay;"},
        {"getTabList", "()Lnet/minecraft/client/gui/components/PlayerTabOverlay;"},
        {"getSpectatorGui", "()Lnet/minecraft/client/gui/components/spectator/SpectatorGui;"},
        {"getFont", "()Lnet/minecraft/client/gui/Font;"},
        {"clearCache", "()V"},
        {"clearTitles", "()V"},
        {"getGuiTicks", "()I"},
        {"onDisconnected", "()V"},
        {"resetTitleTimes", "()V"},
        {"setNowPlaying", "(Lnet/minecraft/network/chat/Component;)V"},
        {"setOverlayMessage", "(Lnet/minecraft/network/chat/Component;Z)V"},
        {"setSubtitle", "(Lnet/minecraft/network/chat/Component;)V"},
        {"setTimes", "(III)V"},
        {"setTitle", "(Lnet/minecraft/network/chat/Component;)V"},
    };

    /** ClassWriter whose frame computation never needs the (absent) MC hierarchy. */
    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    /** The receiver-as-arg0 static descriptor for a moved instance method. */
    static String staticDesc(String instanceDesc) {
        return "(L" + GUI + ";" + instanceDesc.substring(1);
    }

    public static byte[] generate() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, INTERNAL, null, "java/lang/Object", null);

        for (String[] m : MOVED_INSTANCE) {
            String name = m[0];
            String desc = m[1];
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, staticDesc(desc),
                    null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);                       // Gui
            mv.visitFieldInsn(GETFIELD, GUI, "hud", HUD_DESC); // -> Hud
            int slot = 1;
            for (Type arg : Type.getArgumentTypes(desc)) {
                mv.visitVarInsn(arg.getOpcode(ILOAD), slot);
                slot += arg.getSize();
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, HUD, name, desc, false);
            mv.visitInsn(Type.getReturnType(desc).getOpcode(IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Register the synthetic + all 17 redirects (16 forwarders + the static owner-move). */
    public static void register(RetromodTransformer t) {
        if (!t.getSyntheticClasses().containsKey(INTERNAL)) {
            t.registerSyntheticClass(INTERNAL, generate());
        }
        for (String[] m : MOVED_INSTANCE) {
            // Receiver-as-arg0 target: the transformer auto-devirtualizes to INVOKESTATIC.
            t.registerMethodRedirect(GUI, m[0], m[1], INTERNAL, m[0], staticDesc(m[1]));
        }
        // Already static on both sides: plain owner move.
        t.registerMethodRedirect(
                GUI, "getMobEffectSprite",
                "(Lnet/minecraft/core/Holder;)Lnet/minecraft/resources/Identifier;",
                HUD, "getMobEffectSprite",
                "(Lnet/minecraft/core/Holder;)Lnet/minecraft/resources/Identifier;");
    }
}
