/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Follows Forge 1.19's renames of the client events whose shape did not change.
 *
 * <p>A handler parameter that names a deleted event class makes Forge reject every automatic
 * subscriber in that class, so one stale camera or key handler silently switched off a mod's other
 * client handlers too. The camera, fog color, key and screen opening events kept their contract
 * under new names, and so did the config screen factory. The camera's partial tick getter and the opened
 * screen's setter were renamed. Events whose registration moved to a different bus, such as overlays, key
 * mappings and particle providers, are not renamed here.
 */
final class LegacyForgeClientEvents119 {

    private static final String CLIENT_EVENT = "net/minecraftforge/client/event/";
    private static final String NEO_EVENT = "net/neoforged/neoforge/client/event/";
    /** The first hosts whose partial tick getter returns a float: NeoForge 26.3 and Forge 26.2 (checked in their jars). */
    static final String NEO_FLOAT_TICK_SINCE = "26.3";
    static final String FORGE_FLOAT_TICK_SINCE = "26.2";
    private static final String SCREEN = "Lnet/minecraft/client/gui/screens/Screen;";

    private LegacyForgeClientEvents119() {
    }

    private static final String[][] MOVES = {
        {"EntityViewRenderEvent", "ViewportEvent"},
        {"EntityViewRenderEvent$CameraSetup", "ViewportEvent$ComputeCameraAngles"},
        {"EntityViewRenderEvent$FogColors", "ViewportEvent$ComputeFogColor"},
        {"InputEvent$KeyInputEvent", "InputEvent$Key"},
        {"ScreenOpenEvent", "ScreenEvent$Opening"},
    };

    /** The same moves on NeoForge, which kept Forge 1.19's names for these events under its own package. */
    static void registerNeoForge(RetromodTransformer transformer) {
        ClassNode viewport = ClassResourceInspector.read(NEO_EVENT + "ViewportEvent");
        registerNeoForge(transformer, partialTickIsFloat(viewport, RetromodVersion.TARGET_MC_VERSION, NEO_FLOAT_TICK_SINCE));
    }

    static void registerNeoForge(RetromodTransformer transformer, boolean floatPartialTick) {
        for (String[] move : MOVES) {
            transformer.registerClassRedirect(CLIENT_EVENT + move[0], NEO_EVENT + move[1]);
        }
        // The call reaches this pass under NeoForge's names; NeoForge 26.3 returns a float, older hosts a double.
        for (String viewport : new String[]{"ViewportEvent", "ViewportEvent$ComputeCameraAngles",
                "ViewportEvent$ComputeFogColor"}) {
            renamePartialTick(transformer, NEO_EVENT + viewport, NEO_EVENT + viewport, floatPartialTick);
        }
        transformer.registerMethodRedirect(NEO_EVENT + "ScreenEvent$Opening", "setScreen", "(" + SCREEN + ")V",
                NEO_EVENT + "ScreenEvent$Opening", "setNewScreen", "(" + SCREEN + ")V");
    }

    static void register(RetromodTransformer transformer) {
        ClassNode viewport = ClassResourceInspector.read(CLIENT_EVENT + "ViewportEvent");
        register(transformer, partialTickIsFloat(viewport, RetromodVersion.TARGET_MC_VERSION, FORGE_FLOAT_TICK_SINCE));
    }

    static void register(RetromodTransformer transformer, boolean floatPartialTick) {
        for (String[] move : MOVES) {
            transformer.registerClassRedirect(CLIENT_EVENT + move[0], CLIENT_EVENT + move[1]);
        }
        // The config screen factory record kept its shape when it moved to ConfigScreenHandler.
        transformer.registerClassRedirect("net/minecraftforge/client/ConfigGuiHandler$ConfigGuiFactory",
                "net/minecraftforge/client/ConfigScreenHandler$ConfigScreenFactory");
        // Older hosts return a double from the base class. Forge 26.2 returns a float from each event, and that
        // conversion keys on the new owner, since the class move reaches the call first.
        for (int i = 0; i < 3; i++) {
            if (floatPartialTick) {
                renamePartialTick(transformer, CLIENT_EVENT + MOVES[i][1], CLIENT_EVENT + MOVES[i][1], true);
            } else {
                renamePartialTick(transformer, CLIENT_EVENT + MOVES[i][0], CLIENT_EVENT + "ViewportEvent", false);
            }
        }
        // getScreen is left alone: Opening inherits it from ScreenEvent, and a redirect would also
        // rewrite a newer mod's own Opening.getScreen() call, since the class move makes the shapes equal.
        transformer.registerMethodRedirect(CLIENT_EVENT + "ScreenOpenEvent", "setScreen", "(" + SCREEN + ")V",
                CLIENT_EVENT + "ScreenEvent$Opening", "setNewScreen", "(" + SCREEN + ")V");
    }

    /** Renames the 1.18 {@code getPartialTicks()}, widening the float the host now returns back to the double old code reads. */
    private static void renamePartialTick(RetromodTransformer transformer, String oldOwner, String newOwner,
            boolean floatPartialTick) {
        if (floatPartialTick) {
            transformer.registerConvertingRedirect(oldOwner, "getPartialTicks", "()D",
                    newOwner, "getPartialTick", "()F", 0, Opcodes.F2D);
        } else {
            transformer.registerMethodRedirect(oldOwner, "getPartialTicks", "()D", newOwner, "getPartialTick", "()D");
        }
    }

    /** Whether the host's {@code ViewportEvent.getPartialTick()} returns a float; an unreadable host is judged by its version. */
    static boolean partialTickIsFloat(ClassNode viewport, String target, String floatSince) {
        if (viewport != null) {
            for (MethodNode method : viewport.methods) {
                if (method.name.equals("getPartialTick") && method.desc.startsWith("()")) {
                    return method.desc.equals("()F");
                }
            }
        }
        return target != null && !RetromodVersion.mcVersionExceeds(floatSince, target);
    }
}
