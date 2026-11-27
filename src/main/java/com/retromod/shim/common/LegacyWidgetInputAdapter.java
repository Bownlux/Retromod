/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Keeps old widget onClick, onRelease and onDrag overrides and calls working after 1.21.9 passed a mouse event instead. */
public final class LegacyWidgetInputAdapter {

    static final String ABSTRACT_WIDGET = "net/minecraft/client/gui/components/AbstractWidget";
    static final String INTERMEDIARY_WIDGET = "net/minecraft/class_339";
    static final String EVENT = "net/minecraft/client/input/MouseButtonEvent";
    static final String INFO = "net/minecraft/client/input/MouseButtonInfo";
    static final String CLICK = "onClick";
    static final String RELEASE = "onRelease";
    static final String DRAG = "onDrag";
    static final String OLD_POINT = "(DD)V";
    static final String OLD_DRAG = "(DDDD)V";
    static final String NEW_CLICK = "(L" + EVENT + ";Z)V";
    static final String NEW_RELEASE = "(L" + EVENT + ";)V";
    static final String NEW_DRAG = "(L" + EVENT + ";DD)V";

    private LegacyWidgetInputAdapter() {}

    /** Returns the class unchanged unless a mod built before 1.21.9, or of unknown version, declares or calls an old input method. */
    public static byte[] apply(byte[] classBytes, String sourceVersion) {
        // A newer mod's onClick(DD) is its own helper, which its event override may call; rewriting that call would recurse.
        if (classBytes == null || before(LegacyInputEventCallAdapter.FIRST_EVENT_HOST)
                || builtFrom(sourceVersion, LegacyInputEventCallAdapter.FIRST_EVENT_HOST)
                || !(mentions(classBytes, OLD_POINT) || mentions(classBytes, OLD_DRAG))) {
            return classBytes;
        }
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")) return classBytes;
            int changes = rewriteCalls(node);
            // A mixin merges into a widget that already has the host's methods, so it gets no bridges.
            if (!StaticFieldIdStamper.isMixin(node) && provenWidget(node.superName)) changes += addBridges(node);
            if (changes == 0) return classBytes;
            SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable unreadable) {
            return classBytes;
        }
    }

    /** Adds the event forms the game calls, each running the class's own old override. */
    static int addBridges(ClassNode node) {
        Set<String> declared = new HashSet<>();
        for (MethodNode method : node.methods) declared.add(method.name + method.desc);
        int added = 0;
        added += bridge(node, declared, CLICK, OLD_POINT, NEW_CLICK, Opcodes.ACC_PUBLIC);
        added += bridge(node, declared, RELEASE, OLD_POINT, NEW_RELEASE, Opcodes.ACC_PUBLIC);
        added += bridge(node, declared, DRAG, OLD_DRAG, NEW_DRAG, Opcodes.ACC_PROTECTED);
        return added;
    }

    private static int bridge(ClassNode node, Set<String> declared, String name, String oldDesc, String newDesc, int access) {
        MethodNode old = find(node, name, oldDesc);
        if (old == null || declared.contains(name + newDesc)
                || (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_PRIVATE)) != 0) {
            return 0;
        }
        // The host's onDrag is protected and the rest public; an old public onDrag keeps its wider access.
        int visibility = (old.access & Opcodes.ACC_PUBLIC) != 0 ? Opcodes.ACC_PUBLIC : access;
        MethodNode bridge = new MethodNode(visibility | Opcodes.ACC_SYNTHETIC, name, newDesc, null, null);
        InsnList code = bridge.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "x", "()D", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "y", "()D", false));
        if (name.equals(DRAG)) {
            code.add(new VarInsnNode(Opcodes.DLOAD, 2));
            code.add(new VarInsnNode(Opcodes.DLOAD, 4));
        }
        // A non-virtual call, so a subclass's bridge reaching this one by super does not run the subclass again.
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, name, oldDesc, false));
        code.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(bridge);
        declared.add(name + newDesc);
        return 1;
    }

    /** Turns each old call on a widget into the event form, as a left click with no modifier keys. */
    static int rewriteCalls(ClassNode node) {
        int changes = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC) continue;
                String newDesc = newForm(call.name, call.desc);
                if (newDesc == null || !widgetOwner(call.owner) || declaresBothForms(node, call.owner, call.name, newDesc)) continue;
                method.instructions.insertBefore(call, buildEvent(method, call.name.equals(DRAG)));
                if (call.name.equals(CLICK)) method.instructions.insertBefore(call, new InsnNode(Opcodes.ICONST_0));
                call.desc = newDesc;
                changes++;
            }
        }
        return changes;
    }

    /** Whether the call's owner, in this jar, declares the event form next to the old one, so the old one is the mod's own. */
    private static boolean declaresBothForms(ClassNode node, String owner, String name, String newDesc) {
        Set<String> seen = new HashSet<>();
        String current = owner;
        while (current != null && seen.add(current) && !current.startsWith("net/minecraft/")) {
            ClassNode type = current.equals(node.name) ? node : jarClass(current);
            if (type == null) return false;
            if (find(type, name, newDesc) != null) return true;
            current = type.superName;
        }
        return false;
    }

    private static ClassNode jarClass(String name) {
        byte[] bytes = RetromodTransformer.readCurrentJarClassForAdapter(name);
        if (bytes == null) return null;
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, ClassReader.SKIP_CODE);
        return type;
    }

    /** Whether the mod declares a build version at or after {@code version}; an unknown version counts as older. */
    static boolean builtFrom(String sourceVersion, String version) {
        return sourceVersion != null && RetromodVersion.compareMcVersions(sourceVersion, version) >= 0;
    }

    private static String newForm(String name, String desc) {
        if (desc.equals(OLD_POINT)) return name.equals(CLICK) ? NEW_CLICK : name.equals(RELEASE) ? NEW_RELEASE : null;
        return name.equals(DRAG) && desc.equals(OLD_DRAG) ? NEW_DRAG : null;
    }

    /** {@code [x, y(, dx, dy)]} on the stack becomes {@code [event(, dx, dy)]}. */
    private static InsnList buildEvent(MethodNode method, boolean drag) {
        int x = method.maxLocals;
        int y = x + 2;
        int dx = y + 2;
        int dy = dx + 2;
        method.maxLocals += drag ? 8 : 4;
        InsnList list = new InsnList();
        if (drag) {
            list.add(new VarInsnNode(Opcodes.DSTORE, dy));
            list.add(new VarInsnNode(Opcodes.DSTORE, dx));
        }
        list.add(new VarInsnNode(Opcodes.DSTORE, y));
        list.add(new VarInsnNode(Opcodes.DSTORE, x));
        list.add(new TypeInsnNode(Opcodes.NEW, EVENT));
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new VarInsnNode(Opcodes.DLOAD, x));
        list.add(new VarInsnNode(Opcodes.DLOAD, y));
        // The old methods ran only for the left button, and read no modifier keys.
        list.add(new TypeInsnNode(Opcodes.NEW, INFO));
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new InsnNode(Opcodes.ICONST_0));
        list.add(new InsnNode(Opcodes.ICONST_0));
        list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, INFO, "<init>", "(II)V", false));
        list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, EVENT, "<init>", "(DDL" + INFO + ";)V", false));
        if (drag) {
            list.add(new VarInsnNode(Opcodes.DLOAD, dx));
            list.add(new VarInsnNode(Opcodes.DLOAD, dy));
        }
        return list;
    }

    /** A Minecraft GUI class, none of which still declares the old forms, or a widget the jar or host proves. */
    private static boolean widgetOwner(String owner) {
        return owner.startsWith("net/minecraft/client/gui/") || provenWidget(owner);
    }

    private static boolean provenWidget(String type) {
        if (type == null) return false;
        return ABSTRACT_WIDGET.equals(type) || RetromodTransformer.currentJarClassInheritsFrom(type, ABSTRACT_WIDGET)
                || RetromodTransformer.currentJarClassInheritsFrom(type, INTERMEDIARY_WIDGET);
    }

    private static MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    /** Whether the class file contains {@code text} in its constant pool. */
    public static boolean mentions(byte[] classBytes, String text) {
        byte[] needle = text.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= classBytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (classBytes[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static boolean before(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) < 0;
    }
}
