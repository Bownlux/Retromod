/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.function.Predicate;

/**
 * Bridges Forge's {@code ModLoader.get()} instance calls onto NeoForge's static {@code ModLoader}.
 *
 * <p>A Forge mod that defines its own mod-bus event fires it with
 * {@code ModLoader.get().runEventGenerator(...)} or {@code ModLoader.get().postEvent(...)}.
 * NeoForge made those methods static and removed {@code get()}. The calls go through a generated
 * helper that ignores the old instance; {@code get()} itself answers null, since nothing else on
 * the old instance survived.
 */
final class LegacyModLoaderBridge {

    static final String FORGE = "net/minecraftforge/fml/ModLoader";
    static final String NEO = "net/neoforged/fml/ModLoader";
    static final String HELPER = "com/retromod/generated/LegacyModLoaderCalls";
    private static final String EVENT = "net/neoforged/bus/api/Event";

    private LegacyModLoaderBridge() {}

    static boolean register(RetromodTransformer transformer, Predicate<String> hostHasClass) {
        if (hostHasClass.test(FORGE) || !hostHasClass.test(NEO)) return false;
        transformer.registerSyntheticClass(HELPER, generate());
        transformer.registerClassRedirect(FORGE, NEO);
        transformer.registerMethodRedirect(FORGE, "get", "()L" + FORGE + ";", HELPER, "get", "()L" + NEO + ";");
        transformer.registerMethodRedirect(FORGE, "runEventGenerator", "(Ljava/util/function/Function;)V",
                HELPER, "runEventGenerator", "(L" + NEO + ";Ljava/util/function/Function;)V", true);
        transformer.registerMethodRedirect(FORGE, "postEvent", "(Lnet/minecraftforge/eventbus/api/Event;)V",
                HELPER, "postEvent", "(L" + NEO + ";L" + EVENT + ";)V", true);
        return true;
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", null);

        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get", "()L" + NEO + ";", null, null);
        get.visitCode();
        get.visitInsn(Opcodes.ACONST_NULL);
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        MethodVisitor generator = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "runEventGenerator",
                "(L" + NEO + ";Ljava/util/function/Function;)V", null, null);
        generator.visitCode();
        generator.visitVarInsn(Opcodes.ALOAD, 1);
        generator.visitMethodInsn(Opcodes.INVOKESTATIC, NEO, "runEventGenerator", "(Ljava/util/function/Function;)V", false);
        generator.visitInsn(Opcodes.RETURN);
        generator.visitMaxs(0, 0);
        generator.visitEnd();

        MethodVisitor post = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "postEvent",
                "(L" + NEO + ";L" + EVENT + ";)V", null, null);
        post.visitCode();
        post.visitVarInsn(Opcodes.ALOAD, 1);
        post.visitMethodInsn(Opcodes.INVOKESTATIC, NEO, "postEvent", "(L" + EVENT + ";)V", false);
        post.visitInsn(Opcodes.RETURN);
        post.visitMaxs(0, 0);
        post.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
