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
import java.util.Map;

/**
 * Sends Forge-style cancellation calls on NeoForge events to {@code LegacyEventCalls}.
 *
 * <p>A migrated Forge mod calls {@code event.setCanceled(true)} as a virtual method on the concrete
 * event class. NeoForge declares those methods only on {@code ICancellableEvent}, so the call fails
 * with {@code NoSuchMethodError} wherever NeoForge made the event uncancellable (for example
 * {@code LivingDamageEvent}). The helper does the same thing for a cancellable event and nothing for
 * the rest. Only virtual calls on NeoForge event classes are rewritten; the interface calls NeoForge
 * itself emits are already correct.
 */
public final class LegacyEventCancelAdapter {

    public static final String HELPER = "com/retromod/shim/forge/embedded/LegacyEventCalls";

    private static final Map<String, String> HELPER_DESCRIPTORS = Map.of(
            "setCanceled(Z)V", "(Ljava/lang/Object;Z)V",
            "isCanceled()Z", "(Ljava/lang/Object;)Z",
            "isCancelable()Z", "(Ljava/lang/Object;)Z");

    private static final String[] EVENT_PACKAGES = {
            "net/neoforged/neoforge/event/", "net/neoforged/neoforge/client/event/", "net/neoforged/bus/api/Event"
    };

    private LegacyEventCancelAdapter() {}

    public static byte[] apply(byte[] classBytes) {
        String constants = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!constants.contains("Cancel")) return classBytes;
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
                        String helperDesc = HELPER_DESCRIPTORS.get(method + desc);
                        if (opcode == Opcodes.INVOKEVIRTUAL && helperDesc != null && isNeoForgeEvent(owner)) {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, method, helperDesc, false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, method, desc, itf);
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : classBytes;
    }

    static boolean isNeoForgeEvent(String owner) {
        for (String prefix : EVENT_PACKAGES) {
            if (owner.startsWith(prefix)) return true;
        }
        return false;
    }
}
