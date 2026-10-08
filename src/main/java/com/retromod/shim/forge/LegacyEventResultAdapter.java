/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;

/**
 * Sends Forge {@code Event.Result} calls on NeoForge events to {@code LegacyEventResult}.
 *
 * <p>The Forge result type is renamed to the embedded stand-in, so a call such as
 * {@code positionCheck.setResult(Result.DENY)} arrives here as a virtual call that takes the
 * stand-in. No NeoForge event declares that, so the call becomes a static call that passes the
 * method name, and the stand-in converts the value to the enum that event's method takes. Getters
 * that return the stand-in and {@code hasResult()} are handled the same way. Only virtual calls on
 * NeoForge event classes are rewritten.
 */
public final class LegacyEventResultAdapter {

    public static final String RESULT = "com/retromod/shim/forge/embedded/LegacyEventResult";
    public static final String FORGE_RESULT = "net/minecraftforge/eventbus/api/Event$Result";

    private static final String SIMPLE_NAME = "LegacyEventResult;";

    private LegacyEventResultAdapter() {}

    public static byte[] apply(byte[] classBytes) {
        String constants = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!constants.contains(SIMPLE_NAME) && !constants.contains("hasResult")) return classBytes;
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc,
                            boolean itf) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && LegacyEventCancelAdapter.isNeoForgeEvent(owner)
                                && rewrite(this, method, desc)) {
                            changed[0] = true;
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, method, desc, itf);
                    }

                    private boolean rewrite(MethodVisitor out, String method, String desc) {
                        String result = resultTypeIn(desc);
                        if (result != null && desc.equals("(L" + result + ";)V")) {
                            out.visitLdcInsn(method);
                            out.visitMethodInsn(Opcodes.INVOKESTATIC, result, "apply",
                                    "(Ljava/lang/Object;L" + result + ";Ljava/lang/String;)V", false);
                            return true;
                        }
                        if (result != null && desc.equals("()L" + result + ";")) {
                            out.visitLdcInsn(method);
                            out.visitMethodInsn(Opcodes.INVOKESTATIC, result, "read",
                                    "(Ljava/lang/Object;Ljava/lang/String;)L" + result + ";", false);
                            return true;
                        }
                        if (method.equals("hasResult") && desc.equals("()Z")) {
                            out.visitMethodInsn(Opcodes.INVOKESTATIC, RESULT, "hasResult",
                                    "(Ljava/lang/Object;)Z", false);
                            return true;
                        }
                        return false;
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : classBytes;
    }

    /** The stand-in's name as this class spells it, before or after per-mod embedding. */
    private static String resultTypeIn(String desc) {
        int end = desc.indexOf(SIMPLE_NAME);
        if (end < 0) return null;
        // The type starts after the delimiter before it; its own package may contain an 'L'.
        int start = Math.max(desc.lastIndexOf('(', end), Math.max(desc.lastIndexOf(')', end),
                desc.lastIndexOf(';', end))) + 1;
        if (desc.charAt(start) != 'L') return null;
        String type = desc.substring(start + 1, end + SIMPLE_NAME.length() - 1);
        return type.endsWith("/LegacyEventResult") ? type : null;
    }
}
