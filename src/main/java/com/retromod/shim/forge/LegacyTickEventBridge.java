/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.util.McReflect;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/** Runs Forge 1.20.1 tick listeners on Forge 26.2, which split each tick event into a Pre and a Post event. */
public final class LegacyTickEventBridge {

    static final String TICK = "net/minecraftforge/event/TickEvent";
    static final String TICKS = RebuiltApiClasses.ROOT + "forge/event/TickEvents";
    static final String PHASE = RebuiltApiClasses.ROOT + "forge/event/TickPhase";
    private static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    private static final String NEW_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;";

    // The old tick event classes, which Forge 26.2 turned into interfaces over a Pre and a Post record.
    static final List<String> EVENTS = List.of("ClientTickEvent", "ServerTickEvent", "LevelTickEvent",
            "PlayerTickEvent", "RenderTickEvent");

    private static volatile boolean active;

    private LegacyTickEventBridge() {}

    public static void register(RetromodTransformer t) {
        if (!hostSplitTicks()) return;
        RebuiltApiClasses.registerTree(t, TICKS);
        t.registerClassRedirect(TICK + "$Phase", PHASE);
        String object = "(Ljava/lang/Object;)";
        for (String owner : owners()) {
            t.registerFieldStaticBridge(owner, "phase", TICKS, "phase", object + "L" + PHASE + ";", null, null);
            t.registerFieldStaticBridge(owner, "side", TICKS, "side", object + "Lnet/minecraftforge/fml/LogicalSide;", null, null);
        }
        t.registerFieldStaticBridge(TICK + "$LevelTickEvent", "level", TICKS, "level",
                object + "Lnet/minecraft/world/level/Level;", null, null);
        t.registerFieldStaticBridge(TICK + "$PlayerTickEvent", "player", TICKS, "player",
                object + "Lnet/minecraft/world/entity/player/Player;", null, null);
        // Old code calls these on a class; they are interface methods now, so a helper calls them.
        t.registerMethodRedirect(TICK + "$ServerTickEvent", "getServer", "()Lnet/minecraft/server/MinecraftServer;",
                TICKS, "server", object + "Lnet/minecraft/server/MinecraftServer;", true);
        for (String owner : List.of(TICK + "$ServerTickEvent", TICK + "$LevelTickEvent")) {
            t.registerMethodRedirect(owner, "haveTime", "()Z", TICKS, "haveTime", object + "Z", true);
        }
        active = true;
    }

    private static List<String> owners() {
        List<String> owners = new ArrayList<>(List.of(TICK));
        for (String event : EVENTS) owners.add(TICK + "$" + event);
        return owners;
    }

    /** Read from the host when it can be; offline, a Forge target from 26.2, the first checked to have it. */
    static boolean hostSplitTicks() {
        ClassNode tick = ClassResourceInspector.read(TICK);
        if (tick == null) {
            return !McReflect.isNeoForge()
                    && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.2") >= 0;
        }
        return (tick.access & Opcodes.ACC_INTERFACE) != 0;
    }

    /** The class with each old tick listener answered by a Pre and a Post listener, or the same array. */
    public static byte[] apply(byte[] classBytes) {
        if (!active || classBytes == null) return classBytes;
        return splitListeners(classBytes);
    }

    static byte[] splitListeners(byte[] classBytes) {
        if (new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf(TICK + "$") < 0) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode method : node.methods) {
            String event = tickEvent(method);
            if (event == null) continue;
            AnnotationNode subscribe = takeSubscribe(method);
            if (subscribe == null) continue;
            // The original keeps its body and is called by one listener per event the host fires.
            for (String half : List.of("Pre", "Post")) {
                added.add(listener(node.name, isInterface, method, subscribe, TICK + "$" + event + "$" + half, half));
            }
        }
        if (added.isEmpty()) return classBytes;
        node.methods.addAll(added);
        // The new methods only pass their argument on, so the original frames stay valid.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The old tick event a void, one-argument method takes, or null. */
    private static String tickEvent(MethodNode method) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        if (arguments.length != 1 || Type.getReturnType(method.desc).getSort() != Type.VOID) return null;
        String argument = arguments[0].getInternalName();
        for (String event : EVENTS) {
            if (argument.equals(TICK + "$" + event)) return event;
        }
        return null;
    }

    /** Removes the method's subscribe annotation and returns it, or null when it has none. */
    private static AnnotationNode takeSubscribe(MethodNode method) {
        for (List<AnnotationNode> annotations : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc.equals(NEW_SUBSCRIBE) || annotation.desc.equals(OLD_SUBSCRIBE)) {
                    annotations.remove(annotation);
                    return annotation;
                }
            }
        }
        return null;
    }

    /** A listener for one concrete event that calls the original method with it. */
    private static MethodNode listener(String owner, boolean isInterface, MethodNode original, AnnotationNode subscribe,
                                       String concrete, String half) {
        boolean isStatic = (original.access & Opcodes.ACC_STATIC) != 0;
        int access = original.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC);
        MethodNode listener = new MethodNode(access, "retromod$" + original.name + "$" + half, "(L" + concrete + ";)V", null, null);
        AnnotationNode copy = new AnnotationNode(NEW_SUBSCRIBE);
        copy.values = subscribe.values;
        listener.visibleAnnotations = new ArrayList<>(List.of(copy));
        if (!isStatic) listener.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        listener.instructions.add(new VarInsnNode(Opcodes.ALOAD, isStatic ? 0 : 1));
        int opcode = isStatic ? Opcodes.INVOKESTATIC
                : (original.access & Opcodes.ACC_PRIVATE) != 0 ? Opcodes.INVOKESPECIAL
                : isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        listener.instructions.add(new MethodInsnNode(opcode, owner, original.name, original.desc, isInterface));
        listener.instructions.add(new InsnNode(Opcodes.RETURN));
        return listener;
    }
}
