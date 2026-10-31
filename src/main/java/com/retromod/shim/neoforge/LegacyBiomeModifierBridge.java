/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/** Keeps an old biome modifier's {@code modify(biome, phase, builder)} working on NeoForge 26.3, which added a registry access first. */
public final class LegacyBiomeModifierBridge {

    static final String MODIFIER = "net/neoforged/neoforge/common/world/BiomeModifier";
    static final String LEGACY = "com/retromod/generated/LegacyBiomeModifier";
    static final String OLD_MODIFY = "(Lnet/minecraft/core/Holder;L" + MODIFIER + "$Phase;"
            + "Lnet/neoforged/neoforge/common/world/ModifiableBiomeInfo$BiomeInfo$Builder;)V";
    static final String NEW_MODIFY = "(Lnet/minecraft/core/RegistryAccess;" + OLD_MODIFY.substring(1);

    private LegacyBiomeModifierBridge() {}

    public static void register(RetromodTransformer t) {
        if (hostTakesRegistryAccess()) registerReplacement(t);
    }

    // Read from the host; offline, where it cannot be, nothing is registered.
    static boolean hostTakesRegistryAccess() {
        ClassNode host = ClassResourceInspector.read(MODIFIER);
        return host != null && host.methods.stream().anyMatch(m -> m.name.equals("modify") && m.desc.equals(NEW_MODIFY))
                && host.methods.stream().noneMatch(m -> m.name.equals("modify") && m.desc.equals(OLD_MODIFY));
    }

    static void registerReplacement(RetromodTransformer t) {
        t.registerSyntheticClass(LEGACY, generate());
        // Only the implements list changes; fields and parameters typed BiomeModifier stay as they are.
        t.registerInterfaceReplacement(MODIFIER, LEGACY);
    }

    /** {@code interface LegacyBiomeModifier extends BiomeModifier}: the old modify, and the new one calling it. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, LEGACY, null, "java/lang/Object", new String[]{MODIFIER});
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "modify", OLD_MODIFY, null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "modify", NEW_MODIFY, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, LEGACY, "modify", OLD_MODIFY, true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
