/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.function.Predicate;

/** Sends Forge 1.20.1's {@code ModLoader.get()} instance calls to the static ModLoader of NeoForge and Forge 26.2. */
final class LegacyModLoaderBridge {

    static final String FORGE = "net/minecraftforge/fml/ModLoader";
    static final String NEO = "net/neoforged/fml/ModLoader";
    static final String HELPER = "com/retromod/generated/LegacyModLoaderCalls";
    static final String FORGE_HELPER = RebuiltApiClasses.ROOT + "forge/fml/LegacyModLoader";
    static final String GET = "()L" + FORGE + ";";
    private static final String EVENT = "net/neoforged/bus/api/Event";
    static final String DATAGEN = "net/neoforged/neoforge/data/loading/DatagenModLoader";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    // Forge 1.20.1's instance methods under the names Forge 26.2 kept for static ones. Their event
    // parameters are Object here, because LegacyEventBaseAdapter rewrites them before any redirect.
    static final String[][] FORGE_INSTANCE_METHODS = {
            {"addWarning", "(Lnet/minecraftforge/fml/ModLoadingWarning;)V"},
            {"getWarnings", "()Ljava/util/List;"},
            {"hasCompletedState", "(Ljava/lang/String;)Z"},
            {"runEventGenerator", "(Ljava/util/function/Function;)V"},
            {"postEvent", "(Ljava/lang/Object;)V"},
            {"postEventWithReturn", "(Ljava/lang/Object;)Ljava/lang/Object;"},
            {"postEventWrapContainerInModOrder", "(Ljava/lang/Object;)V"},
            {"postEventWithWrapInModOrder",
                    "(Ljava/lang/Object;Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;)V"}};

    private LegacyModLoaderBridge() {}

    /** Registers the rebuilt helper where the Forge host lacks {@code ModLoader.get()}; offline, Forge from 26.2. */
    static void registerForge(RetromodTransformer t) {
        // Forge 1.20.1 still has get(), and a host that has it keeps the real instance calls.
        if (!ForgeHostMembers.lacksMethod(FORGE, "get", GET, ForgeHostShape.FIRST_CHECKED_VERSION)) return;
        RebuiltApiClasses.registerTree(t, FORGE_HELPER);
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        t.registerMethodRedirect(FORGE, "get", GET, FORGE_HELPER, "get", GET);
        // Each helper takes the old receiver first and ignores it. A newer mod's static call of the same
        // name passes no receiver, so the transformer leaves that call on the host.
        for (String[] method : FORGE_INSTANCE_METHODS) {
            t.registerMethodRedirect(FORGE, method[0], method[1], FORGE_HELPER, method[0],
                    "(L" + FORGE + ";" + method[1].substring(1), true);
        }
    }

    /** NeoForge: a generated helper, since NeoForge made the methods static and removed {@code get()}. */
    static boolean register(RetromodTransformer transformer, Predicate<String> hostHasClass) {
        // NeoForge 1.20.1 still runs Forge's ModLoader. Offline neither class can be read, and the
        // caller has already checked that the target is NeoForge.
        if (hostHasClass.test(FORGE)) return false;
        boolean offline = !hostHasClass.test(NEO);
        transformer.registerSyntheticClass(HELPER, generate());
        transformer.registerClassRedirect(FORGE, NEO);
        transformer.registerMethodRedirect(FORGE, "get", "()L" + FORGE + ";", HELPER, "get", "()L" + NEO + ";");
        transformer.registerMethodRedirect(FORGE, "runEventGenerator", "(Ljava/util/function/Function;)V",
                HELPER, "runEventGenerator", "(L" + NEO + ";Ljava/util/function/Function;)V", true);
        transformer.registerMethodRedirect(FORGE, "postEvent", "(Lnet/minecraftforge/eventbus/api/Event;)V",
                HELPER, "postEvent", "(L" + NEO + ";L" + EVENT + ";)V", true);
        // Forge's static checks: NeoForge answers data generation from its data generation loader,
        // and a valid loading state is one without loading errors.
        if (offline || hostHasClass.test(DATAGEN)) {
            transformer.registerMethodRedirect(FORGE, "isDataGenRunning", "()Z", DATAGEN, "isRunningDataGen", "()Z");
        }
        transformer.registerMethodRedirect(FORGE, "isLoadingStateValid", "()Z", HELPER, "isLoadingStateValid", "()Z");
        return true;
    }

    // get() answers null on NeoForge, since nothing else on the old instance survived there.
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

        // isLoadingStateValid() is !ModLoader.hasErrors(), as 0 or 1.
        MethodVisitor valid = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isLoadingStateValid", "()Z",
                null, null);
        valid.visitCode();
        valid.visitMethodInsn(Opcodes.INVOKESTATIC, NEO, "hasErrors", "()Z", false);
        valid.visitInsn(Opcodes.ICONST_1);
        valid.visitInsn(Opcodes.IXOR);
        valid.visitInsn(Opcodes.IRETURN);
        valid.visitMaxs(0, 0);
        valid.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
