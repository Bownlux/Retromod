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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapts legacy calls to primitive {@code Screen} keyboard and mouse methods on 26.x hosts.
 *
 * <p>The input overhaul replaced those three primitive parameters with one
 * {@code KeyEvent}, while mouse clicks now take a {@code MouseButtonEvent} and a double-click flag.
 * A descriptor-only redirect is invalid because the old operands remain on the stack. Renaming old
 * overrides and generating reverse bridges is also unsafe: it changes virtual dispatch for every
 * mod screen. This pass instead changes only exact call sites whose owner is {@code Screen}. It
 * saves the primitive arguments in fresh locals, constructs the corresponding event, and invokes
 * the same owner with the new descriptor. The original invocation opcode is retained, including
 * {@code INVOKESPECIAL} for real {@code super} calls.
 */
public final class LegacyInputEventCallAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-input-event");

    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String KEY_EVENT = "net/minecraft/client/input/KeyEvent";
    private static final String MOUSE_EVENT = "net/minecraft/client/input/MouseButtonEvent";
    private static final String MOUSE_INFO = "net/minecraft/client/input/MouseButtonInfo";
    private static final String OLD_KEY_DESCRIPTOR = "(III)Z";
    private static final String NEW_KEY_DESCRIPTOR = "(L" + KEY_EVENT + ";)Z";
    private static final String OLD_MOUSE_DESCRIPTOR = "(DDI)Z";
    private static final String NEW_MOUSE_DESCRIPTOR = "(L" + MOUSE_EVENT + ";Z)Z";

    private static final byte[] SCREEN_BYTES = SCREEN.getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] KEY_METHOD_BYTES =
            "keyPressed".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] MOUSE_METHOD_BYTES =
            "mouseClicked".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] KEY_DESCRIPTOR_BYTES =
            OLD_KEY_DESCRIPTOR.getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] MOUSE_DESCRIPTOR_BYTES =
            OLD_MOUSE_DESCRIPTOR.getBytes(StandardCharsets.ISO_8859_1);

    private LegacyInputEventCallAdapter() {}

    /** Returns the original array when the class has no exact legacy call or a safe rewrite fails. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null
                || RetromodVersion.compareMcVersions(
                        RetromodVersion.TARGET_MC_VERSION, "26.1") < 0
                || !contains(classBytes, SCREEN_BYTES)
                || !(hasKeyCallBytes(classBytes) || hasMouseCallBytes(classBytes))) {
            return classBytes;
        }

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            int rewrites = 0;

            for (MethodNode method : classNode.methods) {
                List<MethodInsnNode> keyCalls = legacyCalls(
                        method, "keyPressed", OLD_KEY_DESCRIPTOR);
                List<MethodInsnNode> mouseCalls = legacyCalls(
                        method, "mouseClicked", OLD_MOUSE_DESCRIPTOR);
                if (keyCalls.isEmpty() && mouseCalls.isEmpty()) continue;

                if (!keyCalls.isEmpty()) {
                    // The three slots are reused for every matching key call in this method. Each
                    // inserted sequence stores and reloads them without a branch target between.
                    int keyLocal = method.maxLocals;
                    int scanCodeLocal = keyLocal + 1;
                    int modifiersLocal = keyLocal + 2;
                    method.maxLocals += 3;

                    for (MethodInsnNode call : keyCalls) {
                        InsnList event = new InsnList();
                        // [..., screen, key, scancode, modifiers] -> [..., screen]
                        event.add(new VarInsnNode(Opcodes.ISTORE, modifiersLocal));
                        event.add(new VarInsnNode(Opcodes.ISTORE, scanCodeLocal));
                        event.add(new VarInsnNode(Opcodes.ISTORE, keyLocal));
                        // [..., screen] -> [..., screen, new KeyEvent(key, scancode, modifiers)]
                        event.add(new TypeInsnNode(Opcodes.NEW, KEY_EVENT));
                        event.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
                        event.add(new VarInsnNode(Opcodes.ILOAD, keyLocal));
                        event.add(new VarInsnNode(Opcodes.ILOAD, scanCodeLocal));
                        event.add(new VarInsnNode(Opcodes.ILOAD, modifiersLocal));
                        event.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, KEY_EVENT,
                                "<init>", "(III)V", false));
                        method.instructions.insertBefore(call, event);

                        call.desc = NEW_KEY_DESCRIPTOR;
                        rewrites++;
                    }
                }

                if (!mouseCalls.isEmpty()) {
                    int buttonLocal = method.maxLocals;
                    int yLocal = buttonLocal + 1;
                    int xLocal = yLocal + 2;
                    method.maxLocals += 5;

                    for (MethodInsnNode call : mouseCalls) {
                        InsnList event = new InsnList();
                        // [..., screen, x(double), y(double), button] -> [..., screen]
                        event.add(new VarInsnNode(Opcodes.ISTORE, buttonLocal));
                        event.add(new VarInsnNode(Opcodes.DSTORE, yLocal));
                        event.add(new VarInsnNode(Opcodes.DSTORE, xLocal));
                        // Old mouseClicked had no modifier mask. Zero preserves that absence.
                        event.add(new TypeInsnNode(Opcodes.NEW, MOUSE_EVENT));
                        event.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
                        event.add(new VarInsnNode(Opcodes.DLOAD, xLocal));
                        event.add(new VarInsnNode(Opcodes.DLOAD, yLocal));
                        event.add(new TypeInsnNode(Opcodes.NEW, MOUSE_INFO));
                        event.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
                        event.add(new VarInsnNode(Opcodes.ILOAD, buttonLocal));
                        event.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
                        event.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MOUSE_INFO,
                                "<init>", "(II)V", false));
                        event.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MOUSE_EVENT,
                                "<init>", "(DDL" + MOUSE_INFO + ";)V", false));
                        // The old API had no double-click argument. A direct legacy call is one
                        // click, so false is the exact non-synthesized value.
                        event.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
                        method.instructions.insertBefore(call, event);

                        call.desc = NEW_MOUSE_DESCRIPTOR;
                        rewrites++;
                    }
                }
            }

            if (rewrites == 0) return classBytes;
            // No control-flow edge is added. Existing frames remain valid, and COMPUTE_MAXS records
            // the fresh transient locals without asking the offline classpath to resolve Screen.
            SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            LOGGER.debug("Adapted {} legacy Screen input call(s) in {}",
                    rewrites, classNode.name);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.debug("Legacy Screen input adaptation skipped ({}); left unchanged",
                    t.toString());
            return classBytes;
        }
    }

    private static List<MethodInsnNode> legacyCalls(
            MethodNode method, String methodName, String descriptor) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            int opcode = call.getOpcode();
            if ((opcode == Opcodes.INVOKESPECIAL || opcode == Opcodes.INVOKEVIRTUAL)
                    && !call.itf
                    && SCREEN.equals(call.owner)
                    && methodName.equals(call.name)
                    && descriptor.equals(call.desc)) {
                calls.add(call);
            }
        }
        return calls;
    }

    private static boolean hasKeyCallBytes(byte[] classBytes) {
        return contains(classBytes, KEY_METHOD_BYTES)
                && contains(classBytes, KEY_DESCRIPTOR_BYTES);
    }

    private static boolean hasMouseCallBytes(byte[] classBytes) {
        return contains(classBytes, MOUSE_METHOD_BYTES)
                && contains(classBytes, MOUSE_DESCRIPTOR_BYTES);
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
