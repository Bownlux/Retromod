/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Answers the HUD event's old {@code getWindow()} and float {@code getPartialTick()} on NeoForge hosts that dropped them. */
public final class LegacyNeoForgeGuiEventBridge {

    static final String HELPER = "com/retromod/generated/LegacyGuiEventMembers";
    static final String NEO_EVENT = "net/neoforged/neoforge/client/event/RenderGuiEvent";
    static final String FORGE_EVENT = "net/minecraftforge/client/event/RenderGuiEvent";
    /** NeoForge 21.0 passed a DeltaTracker instead of the float and stopped handing out the window. */
    static final String FIRST_HOST = "1.21";

    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String DELTA_TRACKER = "net/minecraft/client/DeltaTracker";
    private static final String WINDOW = "Lcom/mojang/blaze3d/platform/Window;";

    private LegacyNeoForgeGuiEventBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostDroppedWindow(host.apply(NEO_EVENT))) return;
        t.registerSyntheticClass(HELPER, generateHelper());
        // The Forge name is remapped to NeoForge's before the call is matched; both are listed, as the overlay bridge does.
        for (String owner : List.of(FORGE_EVENT, NEO_EVENT)) {
            for (String event : List.of(owner, owner + "$Pre", owner + "$Post")) {
                t.registerMethodRedirect(event, "getWindow", "()" + WINDOW, HELPER, "getWindow",
                        "(Ljava/lang/Object;)" + WINDOW, true);
                t.registerMethodRedirect(event, "getPartialTick", "()F", HELPER, "getPartialTick",
                        "(L" + NEO_EVENT + ";)F", true);
            }
        }
    }

    /** Whether the host's event has no {@code getWindow()}; unreadable, a NeoForge target from 1.21 lacks it. */
    static boolean hostDroppedWindow(ClassNode event) {
        if (event == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) >= 0;
        }
        return event.methods.stream().noneMatch(m -> m.name.equals("getWindow") && m.desc.equals("()" + WINDOW));
    }

    /** The helper: {@code getWindow(event)} and {@code getPartialTick(event)}, read from the game and the event's tracker. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", null);

        // The event carried the game's window, which is the only one there is.
        MethodVisitor window = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getWindow",
                "(Ljava/lang/Object;)" + WINDOW, null, null);
        window.visitCode();
        window.visitMethodInsn(Opcodes.INVOKESTATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";", false);
        window.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MINECRAFT, "getWindow", "()" + WINDOW, false);
        window.visitInsn(Opcodes.ARETURN);
        window.visitMaxs(0, 0);
        window.visitEnd();

        // The float was the frame's partial tick, which the tracker now reports; false matches NeoForge's own layers.
        MethodVisitor partial = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getPartialTick",
                "(L" + NEO_EVENT + ";)F", null, null);
        partial.visitCode();
        partial.visitVarInsn(Opcodes.ALOAD, 0);
        partial.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NEO_EVENT, "getPartialTick", "()L" + DELTA_TRACKER + ";", false);
        partial.visitInsn(Opcodes.ICONST_0);
        partial.visitMethodInsn(Opcodes.INVOKEINTERFACE, DELTA_TRACKER, "getGameTimeDeltaPartialTick", "(Z)F", true);
        partial.visitInsn(Opcodes.FRETURN);
        partial.visitMaxs(0, 0);
        partial.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
