/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Sends calls on Forge's old {@code ConfigTracker.INSTANCE} to the static methods Forge 26.2 has in their place. */
public final class LegacyConfigTrackerBridge {

    static final String TRACKER = "net/minecraftforge/fml/config/ConfigTracker";
    static final String HELPER = "com/retromod/generated/LegacyConfigTracker";
    static final String L_TRACKER = "L" + TRACKER + ";";

    /** The public instance methods Forge 1.20.1 to 1.21.1 had, each a static method of the same descriptor on 26.2. */
    static final String[][] METHODS = {
            {"loadConfigs", "(Lnet/minecraftforge/fml/config/ModConfig$Type;Ljava/nio/file/Path;)V"},
            {"unloadConfigs", "(Lnet/minecraftforge/fml/config/ModConfig$Type;Ljava/nio/file/Path;)V"},
            {"loadDefaultServerConfigs", "()V"},
            {"getConfigFileName", "(Ljava/lang/String;Lnet/minecraftforge/fml/config/ModConfig$Type;)Ljava/lang/String;"},
            {"configSets", "()Ljava/util/Map;"},
            {"fileMap", "()Ljava/util/concurrent/ConcurrentHashMap;"},
    };

    private LegacyConfigTrackerBridge() {}

    /** Registers where the Forge host's tracker has no {@code INSTANCE}; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!ForgeHostMembers.lacksField(TRACKER, "INSTANCE", ForgeHostShape.FIRST_CHECKED_VERSION, host)) return;
        ClassNode tracker = host.apply(TRACKER);
        t.registerSyntheticClass(HELPER, generateHelper(tracker));
        // The old singleton reads as null; every call made on it below ignores its receiver.
        t.registerFieldRedirect(TRACKER, "INSTANCE", L_TRACKER, HELPER, "instance", "()" + L_TRACKER);
        for (String[] method : METHODS) {
            if (!hostHasStatic(tracker, method[0], method[1])) continue;
            // A static call from a mod built for the new tracker matches no receiver-taking redirect, so it stays as it is.
            t.registerMethodRedirect(TRACKER, method[0], method[1], HELPER, method[0], withReceiver(method[1]), true);
        }
    }

    /** Offline the Forge classes cannot be read, and the table was checked with javap on Forge 65.0.0. */
    private static boolean hostHasStatic(ClassNode tracker, String name, String desc) {
        if (tracker == null) return true;
        for (MethodNode method : tracker.methods) {
            if (method.name.equals(name) && method.desc.equals(desc) && (method.access & ACC_STATIC) != 0) return true;
        }
        return false;
    }

    static String withReceiver(String desc) {
        return "(Ljava/lang/Object;" + desc.substring(1);
    }

    static byte[] generateHelper(ClassNode tracker) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        MethodVisitor instance = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "instance", "()" + L_TRACKER, null, null);
        instance.visitCode();
        instance.visitInsn(ACONST_NULL);
        instance.visitInsn(ARETURN);
        instance.visitMaxs(0, 0);
        instance.visitEnd();
        for (String[] method : METHODS) {
            if (!hostHasStatic(tracker, method[0], method[1])) continue;
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, method[0], withReceiver(method[1]), null, null);
            mv.visitCode();
            // Slot 0 is the old receiver; the arguments after it go to the static method unchanged.
            int slot = 1;
            for (Type arg : Type.getArgumentTypes(method[1])) {
                mv.visitVarInsn(arg.getOpcode(ILOAD), slot);
                slot += arg.getSize();
            }
            mv.visitMethodInsn(INVOKESTATIC, TRACKER, method[0], method[1], false);
            mv.visitInsn(Type.getReturnType(method[1]).getOpcode(IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
