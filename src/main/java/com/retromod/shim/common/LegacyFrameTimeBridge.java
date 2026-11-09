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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Sends {@code Minecraft.getFrameTime()} and {@code getDeltaFrameTime()}, which 1.21 removed, to a generated helper that reads the delta tracker. */
public final class LegacyFrameTimeBridge {

    /** The names one helper is written in: Mojang names, or a Fabric host's intermediary. */
    record Names(String helper, String minecraft, String frameTime, String tracker, List<String> accessors,
            String partialTick, String deltaFrameTime, String deltaTicks) {
        String helperDesc() {
            return "(L" + minecraft + ";)F";
        }
    }

    // 1.21 and 1.21.1 name the tracker getter getTimer(); 1.21.11 and 26.x name it getDeltaTracker().
    static final Names MOJANG = new Names("com/retromod/generated/LegacyFrameTime", "net/minecraft/client/Minecraft",
            "getFrameTime", "net/minecraft/client/DeltaTracker", List.of("getDeltaTracker", "getTimer"),
            "getGameTimeDeltaPartialTick", "getDeltaFrameTime", "getGameTimeDeltaTicks");
    // A Fabric host from 1.21 to 1.21.11 runs intermediary names: Minecraft is class_310, the tracker class_9779.
    static final Names INTERMEDIARY = new Names("com/retromod/generated/LegacyIntermediaryFrameTime",
            "net/minecraft/class_310", "method_1488", "net/minecraft/class_9779", List.of("method_60646", "method_61966"),
            "method_60637", "method_1534", "method_60636");

    /** The versions checked for each getter name, used offline and on a dedicated server. */
    static final String FIRST_TIMER_VERSION = "1.21";
    static final String LAST_TIMER_VERSION = "1.21.1";
    static final String FIRST_DELTA_TRACKER_VERSION = "1.21.11";

    private LegacyFrameTimeBridge() {}

    /** Mojang names: Forge and NeoForge from 1.21, and Fabric on 26.x. */
    public static void register(RetromodTransformer t) {
        register(t, MOJANG, ClassResourceInspector::read);
    }

    /** Intermediary names, for a Fabric host from 1.21 to 1.21.11. */
    public static void registerIntermediary(RetromodTransformer t) {
        register(t, INTERMEDIARY, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Names names, Function<String, ClassNode> host) {
        String accessor = trackerGetter(names, host);
        if (accessor == null) return;
        t.registerSyntheticClass(names.helper(), generateHelper(names, accessor));
        t.registerMethodRedirect(names.minecraft(), names.frameTime(), "()F", names.helper(), names.frameTime(),
                names.helperDesc(), true);
        t.registerMethodRedirect(names.minecraft(), names.deltaFrameTime(), "()F", names.helper(), names.deltaFrameTime(),
                names.helperDesc(), true);
    }

    /** The getter that answers the delta tracker on this host, or null when the host keeps the frame time or has no checked getter. */
    static String trackerGetter(Names names, Function<String, ClassNode> host) {
        ClassNode minecraft = host.apply(names.minecraft());
        // Offline and on a dedicated server no client class can be read, so the target version decides.
        if (minecraft == null) return offlineGetter(names);
        if (has(minecraft, names.frameTime(), "()F")) return null;
        for (String accessor : names.accessors()) {
            if (has(minecraft, accessor, "()L" + names.tracker() + ";")) return accessor;
        }
        return null;
    }

    private static String offlineGetter(Names names) {
        String target = RetromodVersion.TARGET_MC_VERSION;
        boolean timerHost = RetromodVersion.compareMcVersions(target, FIRST_TIMER_VERSION) >= 0
                && RetromodVersion.compareMcVersions(target, LAST_TIMER_VERSION) <= 0;
        // The intermediary getter is checked for 1.21 and 1.21.1; a later intermediary host is read at run time.
        if (names == INTERMEDIARY) return timerHost ? names.accessors().get(0) : null;
        if (RetromodVersion.compareMcVersions(target, FIRST_DELTA_TRACKER_VERSION) >= 0) return "getDeltaTracker";
        return timerHost ? "getTimer" : null;
    }

    /** The helper: {@code getFrameTime(Minecraft)} and {@code getDeltaFrameTime(Minecraft)}, each read from the host's tracker. */
    static byte[] generateHelper(Names names, String accessor) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, names.helper(), null,
                "java/lang/Object", null);
        // Paused frames hold the partial tick, as getFrameTime did.
        MethodVisitor frame = trackerRead(cw, names, accessor, names.frameTime());
        frame.visitInsn(Opcodes.ICONST_1);
        frame.visitMethodInsn(Opcodes.INVOKEINTERFACE, names.tracker(), names.partialTick(), "(Z)F", true);
        end(frame);
        // getDeltaFrameTime was the old timer's ticks since the last frame, which the tracker's game time delta still is.
        MethodVisitor delta = trackerRead(cw, names, accessor, names.deltaFrameTime());
        delta.visitMethodInsn(Opcodes.INVOKEINTERFACE, names.tracker(), names.deltaTicks(), "()F", true);
        end(delta);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Starts {@code static float name(Minecraft m)} with {@code m.<accessor>()} on the stack. */
    private static MethodVisitor trackerRead(ClassWriter cw, Names names, String accessor, String name) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, names.helperDesc(), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.minecraft(), accessor, "()L" + names.tracker() + ";", false);
        return mv;
    }

    private static void end(MethodVisitor mv) {
        mv.visitInsn(Opcodes.FRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static boolean has(ClassNode node, String name, String desc) {
        return node.methods != null && node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }
}
