/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * Moves a legacy override off a Minecraft method that a later version made {@code final}, onto
 * the hook Minecraft now calls from that final method.
 *
 * <p>The JVM refuses to load a class that overrides a final method, so the failure is an
 * {@code IncompatibleClassChangeError} at class definition, long before any of the mod's code
 * runs. When Minecraft turns a method final it usually keeps the old behavior overridable through
 * a named hook: since 1.20.5 {@code LivingEntity.getDimensions(Pose)} is final and computes
 * {@code getDefaultDimensions(pose)} scaled by the entity's scale attribute. An old mob that
 * overrode {@code getDimensions} is therefore repaired by renaming its override to the hook.
 *
 * <p>Inside a repaired class, a {@code super.getDimensions(pose)} call must follow the rename too.
 * Left alone it reaches the final method, which calls the renamed override, which calls super
 * again, and the entity recurses until the stack overflows. The hook is also the closer match:
 * in the old version the inherited base never applied the new scale attribute either.
 *
 * <p>The repair runs only on hosts at or past the version that made the method final, only for
 * classes proven to extend the owner, and only when the class does not already declare the hook.
 */
public final class FinalOverrideHookAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-final-overrides");

    /**
     * One method that became final, and the overridable hook its final body delegates to.
     *
     * @param owner      internal name of the class that declares the final method
     * @param name       the method that became final
     * @param descriptor shared descriptor of the final method and the hook
     * @param hook       the overridable method the final body calls
     * @param finalSince first Minecraft version where {@code name} is final
     */
    record FinalHook(String owner, String name, String descriptor, String hook, String finalSince) {}

    /**
     * Mojang names serve NeoForge, Forge 1.20.5 and newer, and Fabric on 26.1 and newer. The
     * intermediary row serves Fabric hosts from 1.20.5 through 1.21.11, where mods and the game
     * both still use intermediary names.
     */
    static final List<FinalHook> HOOKS = List.of(
            new FinalHook("net/minecraft/world/entity/LivingEntity", "getDimensions",
                    "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;",
                    "getDefaultDimensions", "1.20.5"),
            new FinalHook("net/minecraft/class_1309", "method_18377",
                    "(Lnet/minecraft/class_4050;)Lnet/minecraft/class_4048;",
                    "method_55694", "1.20.5"));

    private FinalOverrideHookAdapter() {}

    /** Returns the original array when no override of a final method is present. */
    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, RetromodVersion.TARGET_MC_VERSION,
                RetromodTransformer::currentJarClassInheritsFrom);
    }

    /**
     * Applies every hook whose version has been reached on {@code hostVersion}.
     *
     * @param inheritsFrom proves that a class extends a Minecraft owner
     */
    static byte[] apply(byte[] classBytes, String hostVersion,
            BiPredicate<String, String> inheritsFrom) {
        if (classBytes == null || hostVersion == null) return classBytes;
        try {
            ClassReader reader = new ClassReader(classBytes);
            if (!declaresAnyFinalizedMethod(reader)) return classBytes;
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            if ((node.access & Opcodes.ACC_INTERFACE) != 0
                    || node.name.startsWith("net/minecraft/")) {
                return classBytes;
            }

            int moved = 0;
            for (FinalHook hook : HOOKS) {
                if (RetromodVersion.compareMcVersions(hostVersion, hook.finalSince()) < 0) continue;
                MethodNode override = findInstanceMethod(node, hook.name(), hook.descriptor());
                if (override == null
                        || findInstanceMethod(node, hook.hook(), hook.descriptor()) != null
                        || !inheritsFrom.test(node.superName, hook.owner())) {
                    continue;
                }
                override.name = hook.hook();
                retargetSuperCalls(node, hook);
                moved++;
            }
            if (moved == 0) return classBytes;

            // Only names changed, so the existing stack map frames stay valid.
            ClassWriter writer = new ClassWriter(reader, 0);
            node.accept(writer);
            LOGGER.info("Moved {} override(s) of a now-final Minecraft method onto its hook in {}",
                    moved, node.name);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.debug("Final-override repair skipped ({}); class left unchanged", t.toString());
            return classBytes;
        }
    }

    /** Cheap declaration-only scan, so ordinary classes are not expanded into a tree. */
    private static boolean declaresAnyFinalizedMethod(ClassReader reader) {
        boolean[] found = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                for (FinalHook hook : HOOKS) {
                    if (hook.name().equals(name) && hook.descriptor().equals(descriptor)) {
                        found[0] = true;
                    }
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static void retargetSuperCalls(ClassNode node, FinalHook hook) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && !call.itf
                        && hook.name().equals(call.name)
                        && hook.descriptor().equals(call.desc)
                        && !node.name.equals(call.owner)) {
                    call.name = hook.hook();
                }
            }
        }
    }

    private static MethodNode findInstanceMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)
                    && (method.access & Opcodes.ACC_STATIC) == 0) {
                return method;
            }
        }
        return null;
    }
}
