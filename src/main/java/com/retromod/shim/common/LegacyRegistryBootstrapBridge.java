/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Moves the registry bootstrap callbacks of 26.2's {@code RegistrySetBuilder} onto 26.3's {@code SingleRegistryBootstrap}. */
public final class LegacyRegistryBootstrapBridge {

    static final String BUILDER = "net/minecraft/core/RegistrySetBuilder";
    static final String OLD = BUILDER + "$RegistryBootstrap";
    static final String NEW = "net/minecraft/core/registries/SingleRegistryBootstrap";
    static final String HELPER = "com/retromod/generated/LegacyRegistryBootstrap";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String LIFECYCLE = "Lcom/mojang/serialization/Lifecycle;";
    /** The release that renamed the callback and dropped the lifecycle argument. */
    static final String VERSION = "26.3";

    private LegacyRegistryBootstrapBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostReplacedIt(host)) return;
        // Same single run(BootstrapContext) method under a new name, so a mod's own callbacks and lambdas keep working.
        t.registerClassRedirect(OLD, NEW);
        t.limitClassRedirectToModsBefore(OLD, VERSION);
        // The add with a lifecycle went; every registry it built was stable anyway, so the lifecycle is dropped.
        t.registerSyntheticClass(HELPER, helper());
        String helperDesc = "(L" + BUILDER + ";" + KEY + LIFECYCLE + "L" + NEW + ";)L" + BUILDER + ";";
        for (String callback : List.of(OLD, NEW)) {
            String desc = "(" + KEY + LIFECYCLE + "L" + callback + ";)L" + BUILDER + ";";
            t.registerMethodRedirect(BUILDER, "add", desc, HELPER, "add", helperDesc, true);
            t.limitRedirectToModsBefore(BUILDER, "add", desc, VERSION);
        }
    }

    /** Whether the host has the new callback and not the old one; offline or unreadable, a target from 26.3. */
    static boolean hostReplacedIt(Function<String, ClassNode> host) {
        if (host.apply(NEW) == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, VERSION) >= 0;
        return host.apply(OLD) == null;
    }

    /** {@code static RegistrySetBuilder add(builder, key, lifecycle, bootstrap)}: {@code builder.add(key, bootstrap)}. */
    static byte[] helper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "add",
                "(L" + BUILDER + ";" + KEY + LIFECYCLE + "L" + NEW + ";)L" + BUILDER + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "add", "(" + KEY + "L" + NEW + ";)L" + BUILDER + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
