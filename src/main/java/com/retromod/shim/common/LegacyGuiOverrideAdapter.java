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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Restores exact GUI overrides whose descriptors changed in the 26.x input and render rewrite.
 *
 * <p>The pass is deliberately hierarchy-gated. A method named {@code render} or
 * {@code mouseClicked} is changed only when the class is proven, from the jar's own bytecode, to
 * extend the relevant Minecraft GUI base. Unknown hierarchies are declined.
 */
public final class LegacyGuiOverrideAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-gui-overrides");

    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String ABSTRACT_WIDGET = "net/minecraft/client/gui/components/AbstractWidget";
    private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String KEY_EVENT = "net/minecraft/client/input/KeyEvent";
    private static final String MOUSE_EVENT = "net/minecraft/client/input/MouseButtonEvent";

    private static final Set<String> KNOWN_SCREEN_BASES = Set.of(
            SCREEN, "net/minecraft/class_437");
    private static final Set<String> KNOWN_WIDGET_BASES = Set.of(
            ABSTRACT_WIDGET,
            "net/minecraft/client/gui/components/AbstractButton",
            "net/minecraft/client/gui/components/AbstractSliderButton",
            "net/minecraft/class_339",
            "net/minecraft/class_357");

    private static final String OLD_RENDER = "(L" + GUI + ";IIF)V";
    private static final String NEW_MOUSE = "(L" + MOUSE_EVENT + ";Z)Z";
    private static final String NEW_KEY = "(L" + KEY_EVENT + ";)Z";

    private LegacyGuiOverrideAdapter() {}

    /** Returns the original byte array if no proven GUI override is present or rewriting fails. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null
                || RetromodVersion.compareMcVersions(
                        RetromodVersion.TARGET_MC_VERSION, "26.1") < 0) {
            return classBytes;
        }

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            Function<String, byte[]> jarClasses = RetromodTransformer::readCurrentJarClassForAdapter;
            boolean screen = extendsAny(node.superName, KNOWN_SCREEN_BASES, jarClasses);
            boolean widget = extendsAny(node.superName, KNOWN_WIDGET_BASES, jarClasses);
            if (!screen && !widget) return classBytes;

            int changes = 0;
            Set<String> signatures = new HashSet<>();
            for (MethodNode method : node.methods) {
                signatures.add(method.name + method.desc);
            }

            if (screen) {
                changes += rename(node, signatures, "render", OLD_RENDER,
                        "extractRenderState", Opcodes.ACC_PUBLIC);
                changes += addMouseBridge(node, signatures);
                changes += addKeyBridge(node, signatures);
            }
            if (widget) {
                changes += rename(node, signatures, "renderWidget", OLD_RENDER,
                        "extractWidgetRenderState", Opcodes.ACC_PROTECTED);
            }
            changes += redirectInheritedWidgetRenderCalls(node, jarClasses);
            if (changes == 0) return classBytes;

            SafeClassWriter writer = new SafeClassWriter(reader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            LOGGER.debug("Adapted {} legacy GUI override(s) in {}", changes, node.name);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.debug("Legacy GUI override adaptation skipped ({}); left unchanged", t.toString());
            return classBytes;
        }
    }

    private static int rename(ClassNode node, Set<String> signatures,
            String oldName, String descriptor, String newName, int requiredVisibility) {
        MethodNode old = find(node, oldName, descriptor);
        if (old == null || signatures.contains(newName + descriptor)
                || (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return 0;
        }
        old.name = newName;
        old.access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_PUBLIC);
        old.access |= requiredVisibility;
        signatures.remove(oldName + descriptor);
        signatures.add(newName + descriptor);
        return 1;
    }

    private static int addMouseBridge(ClassNode node, Set<String> signatures) {
        MethodNode old = find(node, "mouseClicked", "(DDI)Z");
        if (old == null || signatures.contains("mouseClicked" + NEW_MOUSE)
                || (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return 0;
        }

        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "mouseClicked", NEW_MOUSE, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT,
                "x", "()D", false));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT,
                "y", "()D", false));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT,
                "button", "()I", false));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name,
                "mouseClicked", "(DDI)Z", false));
        bridge.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(bridge);
        signatures.add("mouseClicked" + NEW_MOUSE);
        return 1;
    }

    private static int addKeyBridge(ClassNode node, Set<String> signatures) {
        MethodNode old = find(node, "keyPressed", "(III)Z");
        if (old == null || signatures.contains("keyPressed" + NEW_KEY)
                || (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return 0;
        }

        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "keyPressed", NEW_KEY, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (String accessor : new String[]{"key", "scancode", "modifiers"}) {
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, KEY_EVENT,
                    accessor, "()I", false));
        }
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name,
                "keyPressed", "(III)Z", false));
        bridge.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(bridge);
        signatures.add("keyPressed" + NEW_KEY);
        return 1;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        return null;
    }

    private static int redirectInheritedWidgetRenderCalls(
            ClassNode node, Function<String, byte[]> jarClasses) {
        int changes = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() == Opcodes.INVOKESTATIC
                        || !"render".equals(call.name)
                        || !OLD_RENDER.equals(call.desc)
                        || !extendsAny(call.owner, KNOWN_WIDGET_BASES, jarClasses)) {
                    continue;
                }
                call.name = "extractRenderState";
                changes++;
            }
        }
        return changes;
    }

    private static boolean extendsAny(
            String current, Set<String> expectedBases, Function<String, byte[]> jarClasses) {
        Set<String> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            if (expectedBases.contains(current)) return true;
            if ("java/lang/Object".equals(current) || jarClasses == null) return false;
            byte[] parent = jarClasses.apply(current);
            if (parent == null) return false;
            current = new ClassReader(parent).getSuperName();
        }
        return false;
    }
}
