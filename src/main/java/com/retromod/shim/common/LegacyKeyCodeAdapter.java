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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Map;

/** Translates key codes a pre-26.3 mod wrote as literals, and only those. */
public final class LegacyKeyCodeAdapter {

    private LegacyKeyCodeAdapter() {}

    public static final String POLY = "com/retromod/polyfill/minecraft/RetroKeyMapping";
    private static final String TYPE = "com/mojang/blaze3d/platform/InputConstants$Type";
    private static final String KEY = "com/mojang/blaze3d/platform/InputConstants$Key";
    /** The window-handle poll {@code isKeyDown(long, int)} that 1.21.4-era mods call. */
    private static final String GUI_CALLS = "com/retromod/polyfill/minecraft/LegacyGuiCalls";
    /** Polyfill methods that take a host-numbered code last, and the form that translates a GLFW code. */
    private static final Map<String, String> TRANSLATING_FORMS = Map.of(
            "isKeyDownUntranslated", "isKeyDown",
            "glfwGetKeyUntranslated", "glfwGetKey",
            "glfwGetMouseButtonUntranslated", "glfwGetMouseButton");

    /**
     * @param classBytes the class to repair
     * @return the repaired class, or the same array when nothing matched
     */
    public static byte[] apply(byte[] classBytes) {
        // 26.3 renumbered keys and mouse buttons for SDL, so only a 26.3 or newer host needs this.
        if (classBytes == null
                || RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") < 0) {
            return classBytes;
        }
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(classBytes).accept(cn, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        if (cn.name.startsWith("com/retromod/")) return classBytes;

        // A literal such as GLFW_KEY_R is a GLFW number and needs translating. A value the mod read
        // at runtime, such as a mouse event's button or a key mapping's bound key, already comes
        // from the host, and translating it again would pick an unrelated key: host button 1 is
        // the left button, but read as GLFW it is the right one. So only a call whose code is
        // pushed by a constant goes to the translating form. Every other call keeps the plain one.
        int changes = 0;
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !isIntConstant(previousReal(insn))) {
                    continue;
                }
                if (call.getOpcode() == Opcodes.INVOKESTATIC && POLY.equals(call.owner)
                        && TRANSLATING_FORMS.containsKey(call.name)) {
                    call.name = TRANSLATING_FORMS.get(call.name);
                    changes++;
                } else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && TYPE.equals(call.owner)
                        && "getOrCreate".equals(call.name) && ("(I)L" + KEY + ";").equals(call.desc)) {
                    MethodInsnNode bridge = new MethodInsnNode(Opcodes.INVOKESTATIC, POLY,
                            "getOrCreate", "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
                    m.instructions.set(call, bridge);
                    m.instructions.insert(bridge, new TypeInsnNode(Opcodes.CHECKCAST, KEY));
                    changes++;
                } else if (call.getOpcode() == Opcodes.INVOKESTATIC && GUI_CALLS.equals(call.owner)
                        && "isKeyDown".equals(call.name) && "(JI)Z".equals(call.desc)) {
                    // The code is on top of the stack. Once translated, the constant no longer
                    // precedes the call, so a second pass leaves it alone.
                    m.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, POLY,
                            "hostKeyCode", "(I)I", false));
                    changes++;
                }
            }
        }
        if (changes == 0) return classBytes;

        try {
            SafeClassWriter cw = new SafeClassWriter(new ClassReader(classBytes),
                    ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable failed) {
            return classBytes; // never ship a half-rewritten class
        }
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static boolean isIntConstant(AbstractInsnNode insn) {
        if (insn == null) return false;
        int op = insn.getOpcode();
        return (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5)
                || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH
                || (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer);
    }
}
