/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Rewrites calls to the GUI input methods that 1.21.9 moved from primitive arguments to event records. */
public final class LegacyInputEventCallAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-input-event");

    /** The first host whose GUI listeners take KeyEvent, MouseButtonEvent and CharacterEvent. */
    public static final String FIRST_EVENT_HOST = "1.21.9";
    /** The first host whose CharacterEvent holds only the code point. */
    static final String FIRST_CODEPOINT_ONLY_HOST = "26.1";

    private static final String KEY_EVENT = "net/minecraft/client/input/KeyEvent";
    private static final String MOUSE_EVENT = "net/minecraft/client/input/MouseButtonEvent";
    private static final String MOUSE_INFO = "net/minecraft/client/input/MouseButtonInfo";
    private static final String CHAR_EVENT = "net/minecraft/client/input/CharacterEvent";
    private static final String INPUT_CONSTANTS = "com/mojang/blaze3d/platform/InputConstants";
    private static final String KEY_RETURN = ")Lcom/mojang/blaze3d/platform/InputConstants$Key;";

    // 1.21.8 declared the old listener forms on 83 classes in these packages. No class in
    // 1.21.9 to 1.21.11, NeoForge 21.11, or 26.1 to 26.3 declares any of them, so a call on one
    // of these owners can only fail as it stands.
    private static final String[] VANILLA_GUI_PACKAGES = {
            "net/minecraft/client/gui/", "com/mojang/realmsclient/"};

    private enum Shape { KEY, CHAR, MOUSE_CLICK, MOUSE_RELEASE, MOUSE_DRAG }

    private static final Map<String, Shape> LISTENER_CALLS = Map.of(
            "keyPressed(III)Z", Shape.KEY,
            "keyReleased(III)Z", Shape.KEY,
            "charTyped(CI)Z", Shape.CHAR,
            "mouseClicked(DDI)Z", Shape.MOUSE_CLICK,
            "mouseReleased(DDI)Z", Shape.MOUSE_RELEASE,
            "mouseDragged(DDIDD)Z", Shape.MOUSE_DRAG);

    private static final byte[][] OLD_DESCRIPTOR_BYTES = {
            bytes("(III)Z"), bytes("(CI)Z"), bytes("(DDI)Z"), bytes("(DDIDD)Z"),
            bytes("(II" + KEY_RETURN)};
    private static final byte[][] OWNER_BYTES = {
            bytes(VANILLA_GUI_PACKAGES[0]), bytes(VANILLA_GUI_PACKAGES[1]), bytes(INPUT_CONSTANTS)};

    private LegacyInputEventCallAdapter() {}

    /** Returns the original array when the class has no exact legacy call or a safe rewrite fails. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || before(FIRST_EVENT_HOST) || !mentionsOldDescriptor(classBytes)) {
            return classBytes;
        }

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            boolean codepointOnly = !before(FIRST_CODEPOINT_ONLY_HOST);
            int rewrites = 0;
            for (MethodNode method : classNode.methods) {
                rewrites += rewriteMethod(method, codepointOnly);
            }
            if (rewrites == 0) return classBytes;

            // No control-flow edge is added. Existing frames remain valid, and COMPUTE_MAXS records
            // the fresh transient locals without asking the offline classpath to resolve the owners.
            SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            LOGGER.debug("Adapted {} legacy GUI input call(s) in {}", rewrites, classNode.name);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.debug("Legacy GUI input adaptation skipped ({}); left unchanged", t.toString());
            return classBytes;
        }
    }

    private static int rewriteMethod(MethodNode method, boolean codepointOnly) {
        if (method.instructions == null || method.instructions.size() == 0) return 0;
        Locals locals = null;
        int rewrites = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            boolean getKey = isLegacyGetKey(call);
            Shape shape = getKey ? null : listenerShape(call);
            if (!getKey && shape == null) continue;

            // Every rewrite in this method reuses one block of fresh slots. Each inserted sequence
            // stores and reloads them with no branch target between, so no frame ever sees them.
            if (locals == null) locals = new Locals(method);
            InsnList event = new InsnList();
            if (getKey) {
                // [..., key, scancode] -> [..., new KeyEvent(key, scancode, 0)]
                event.add(new VarInsnNode(Opcodes.ISTORE, locals.b));
                event.add(new VarInsnNode(Opcodes.ISTORE, locals.a));
                keyEvent(event, locals, false);
                call.desc = "(L" + KEY_EVENT + ";" + KEY_RETURN;
            } else {
                call.desc = adapt(shape, event, locals, codepointOnly);
            }
            method.instructions.insertBefore(call, event);
            rewrites++;
        }
        return rewrites;
    }

    /** Spills the old operands, pushes the event form, and returns the new descriptor. */
    private static String adapt(Shape shape, InsnList event, Locals locals, boolean codepointOnly) {
        // A descriptor-only redirect cannot do this: the old primitive operands would stay on the stack.
        switch (shape) {
            case KEY -> {
                // [..., receiver, key, scancode, modifiers] -> [..., receiver, KeyEvent]
                event.add(new VarInsnNode(Opcodes.ISTORE, locals.c));
                event.add(new VarInsnNode(Opcodes.ISTORE, locals.b));
                event.add(new VarInsnNode(Opcodes.ISTORE, locals.a));
                keyEvent(event, locals, true);
                return "(L" + KEY_EVENT + ";)Z";
            }
            case CHAR -> {
                // [..., receiver, char, modifiers] -> [..., receiver, CharacterEvent]
                event.add(new VarInsnNode(Opcodes.ISTORE, locals.b));
                event.add(new VarInsnNode(Opcodes.ISTORE, locals.a));
                event.add(new TypeInsnNode(Opcodes.NEW, CHAR_EVENT));
                event.add(new InsnNode(Opcodes.DUP));
                event.add(new VarInsnNode(Opcodes.ILOAD, locals.a));
                // 1.21.9 to 1.21.11 keep the modifier mask in the event; 26.1 dropped it.
                if (!codepointOnly) event.add(new VarInsnNode(Opcodes.ILOAD, locals.b));
                event.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CHAR_EVENT, "<init>",
                        codepointOnly ? "(I)V" : "(II)V", false));
                return "(L" + CHAR_EVENT + ";)Z";
            }
            case MOUSE_CLICK -> {
                spillMouse(event, locals);
                mouseEvent(event, locals);
                // The old API had no double-click argument. A direct call is one click.
                event.add(new InsnNode(Opcodes.ICONST_0));
                return "(L" + MOUSE_EVENT + ";Z)Z";
            }
            case MOUSE_RELEASE -> {
                spillMouse(event, locals);
                mouseEvent(event, locals);
                return "(L" + MOUSE_EVENT + ";)Z";
            }
            default -> {
                // [..., receiver, x, y, button, dragX, dragY]: the drag deltas stay after the event.
                event.add(new VarInsnNode(Opcodes.DSTORE, locals.dragY));
                event.add(new VarInsnNode(Opcodes.DSTORE, locals.dragX));
                spillMouse(event, locals);
                mouseEvent(event, locals);
                event.add(new VarInsnNode(Opcodes.DLOAD, locals.dragX));
                event.add(new VarInsnNode(Opcodes.DLOAD, locals.dragY));
                return "(L" + MOUSE_EVENT + ";DD)Z";
            }
        }
    }

    private static void keyEvent(InsnList event, Locals locals, boolean withModifiers) {
        event.add(new TypeInsnNode(Opcodes.NEW, KEY_EVENT));
        event.add(new InsnNode(Opcodes.DUP));
        event.add(new VarInsnNode(Opcodes.ILOAD, locals.a));
        event.add(new VarInsnNode(Opcodes.ILOAD, locals.b));
        // InputConstants.getKey never took a modifier mask, and its lookup reads none.
        event.add(withModifiers ? new VarInsnNode(Opcodes.ILOAD, locals.c) : new InsnNode(Opcodes.ICONST_0));
        event.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, KEY_EVENT, "<init>", "(III)V", false));
    }

    private static void spillMouse(InsnList event, Locals locals) {
        // [..., receiver, x, y, button] -> [..., receiver]
        event.add(new VarInsnNode(Opcodes.ISTORE, locals.a));
        event.add(new VarInsnNode(Opcodes.DSTORE, locals.y));
        event.add(new VarInsnNode(Opcodes.DSTORE, locals.x));
    }

    private static void mouseEvent(InsnList event, Locals locals) {
        // Old mouse calls had no modifier mask. Zero preserves that absence.
        event.add(new TypeInsnNode(Opcodes.NEW, MOUSE_EVENT));
        event.add(new InsnNode(Opcodes.DUP));
        event.add(new VarInsnNode(Opcodes.DLOAD, locals.x));
        event.add(new VarInsnNode(Opcodes.DLOAD, locals.y));
        event.add(new TypeInsnNode(Opcodes.NEW, MOUSE_INFO));
        event.add(new InsnNode(Opcodes.DUP));
        event.add(new VarInsnNode(Opcodes.ILOAD, locals.a));
        event.add(new InsnNode(Opcodes.ICONST_0));
        event.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MOUSE_INFO, "<init>", "(II)V", false));
        event.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MOUSE_EVENT,
                "<init>", "(DDL" + MOUSE_INFO + ";)V", false));
    }

    /** A listener call on a vanilla GUI owner. Opcode and owner stay as written, so a super call stays one. */
    private static Shape listenerShape(MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC || !isVanillaGuiOwner(call.owner)) return null;
        return LISTENER_CALLS.get(call.name + call.desc);
    }

    private static boolean isLegacyGetKey(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC && INPUT_CONSTANTS.equals(call.owner)
                && "getKey".equals(call.name) && ("(II" + KEY_RETURN).equals(call.desc);
    }

    private static boolean isVanillaGuiOwner(String owner) {
        for (String prefix : VANILLA_GUI_PACKAGES) {
            if (owner.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean before(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) < 0;
    }

    /** Fresh slots past the method's own: three ints, then four doubles of two slots each. */
    private static final class Locals {
        final int a;
        final int b;
        final int c;
        final int x;
        final int y;
        final int dragX;
        final int dragY;

        Locals(MethodNode method) {
            a = method.maxLocals;
            b = a + 1;
            c = a + 2;
            x = a + 3;
            y = a + 5;
            dragX = a + 7;
            dragY = a + 9;
            method.maxLocals += 11;
        }
    }

    /** A cheap constant-pool check before parsing: an old descriptor and an owner it can belong to. */
    private static boolean mentionsOldDescriptor(byte[] classBytes) {
        return containsAny(classBytes, OLD_DESCRIPTOR_BYTES) && containsAny(classBytes, OWNER_BYTES);
    }

    private static boolean containsAny(byte[] classBytes, byte[][] needles) {
        for (byte[] needle : needles) {
            if (contains(classBytes, needle)) return true;
        }
        return false;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
