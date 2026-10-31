/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BiPredicate;

/** Keeps an old entity's own eye height, which 1.20.5 moved into its dimensions and made final to override. */
public final class FinalEyeHeightAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-final-overrides");

    static final String LEGACY_EYE_HEIGHT = "retromod$legacyEyeHeight";
    static final String BASE_DIMENSIONS = "retromod$baseDimensions";
    static final String GUARD = "retromod$inEyeHeight";

    /** One naming of the members involved: Mojang names, or intermediary for Fabric 1.20.5 to 1.21.11. */
    record Names(String entity, String livingEntity, String pose, String dimensions, String eyeHeight,
            String getDimensions, String getDefaultDimensions, String withEyeHeight) {

        String eyeDesc() {
            return "(L" + pose + ";)F";
        }

        String dimensionsDesc() {
            return "(L" + pose + ";)L" + dimensions + ";";
        }
    }

    static final List<Names> NAMES = List.of(
            new Names("net/minecraft/world/entity/Entity", "net/minecraft/world/entity/LivingEntity",
                    "net/minecraft/world/entity/Pose", "net/minecraft/world/entity/EntityDimensions",
                    "getEyeHeight", "getDimensions", "getDefaultDimensions", "withEyeHeight"),
            new Names("net/minecraft/class_1297", "net/minecraft/class_1309", "net/minecraft/class_4050",
                    "net/minecraft/class_4048", "method_18381", "method_18377", "method_55694", "method_55685"));

    private FinalEyeHeightAdapter() {}

    /** Returns the original array when the class does not override the old eye height. */
    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, RetromodVersion.TARGET_MC_VERSION, RetromodTransformer::currentJarClassInheritsFrom);
    }

    static byte[] apply(byte[] classBytes, String hostVersion, BiPredicate<String, String> inheritsFrom) {
        if (classBytes == null || hostVersion == null
                || RetromodVersion.compareMcVersions(hostVersion, "1.20.5") < 0) {
            return classBytes;
        }
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")) {
                return classBytes;
            }
            for (Names names : NAMES) {
                MethodNode override = instanceMethod(node, names.eyeHeight(), names.eyeDesc());
                if (override == null || !inheritsFrom.test(node.superName, names.entity())) continue;
                repair(node, override, names, inheritsFrom.test(node.superName, names.livingEntity()));
                // New code with branches and a handler, so every frame is recomputed.
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                LOGGER.info("Kept the old eye height override of {} through its dimensions", node.name);
                return writer.toByteArray();
            }
            return classBytes;
        } catch (Throwable t) {
            LOGGER.debug("Eye height repair skipped ({}); class left unchanged", t.toString());
            return classBytes;
        }
    }

    private static void repair(ClassNode node, MethodNode override, Names names, boolean living) {
        // The old override keeps its body under a new name; a subclass's own override takes the
        // same name, so they still override each other.
        override.name = LEGACY_EYE_HEIGHT;
        override.access &= ~Opcodes.ACC_FINAL;

        // A living entity's getDimensions is final too, and scales getDefaultDimensions.
        String dimensionsMethod = living ? names.getDefaultDimensions() : names.getDimensions();
        MethodNode ownDimensions = instanceMethod(node, dimensionsMethod, names.dimensionsDesc());
        if (ownDimensions != null) ownDimensions.name = BASE_DIMENSIONS;
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
                GUARD, "Z", null, null));

        MethodNode wrapper = new MethodNode(Opcodes.ACC_PUBLIC, dimensionsMethod, names.dimensionsDesc(), null, null);
        emitWrapper(wrapper, node, names, ownDimensions != null ? null : dimensionsMethod);
        node.methods.add(wrapper);
    }

    /**
     * The base dimensions with the old eye height. The old override often asks
     * {@code super.getEyeHeight(pose)}, which now reads these dimensions again, so a guard makes
     * that inner read return the base ones.
     */
    private static void emitWrapper(MethodVisitor mv, ClassNode node, Names names, String superDimensions) {
        String dims = "L" + names.dimensions() + ";";
        String pose = "L" + names.pose() + ";";
        Label work = new Label();
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        mv.visitCode();
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, null);
        // The base: the class's own dimensions, or its superclass's.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        if (superDimensions == null) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, node.name, BASE_DIMENSIONS, names.dimensionsDesc(), false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, node.superName, superDimensions, names.dimensionsDesc(), false);
        }
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, node.name, GUARD, "Z");
        mv.visitJumpInsn(Opcodes.IFEQ, work);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(work);
        mv.visitFrame(Opcodes.F_FULL, 3, new Object[]{node.name, names.pose(), names.dimensions()}, 0, new Object[0]);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, node.name, GUARD, "Z");
        mv.visitLabel(tryStart);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, node.name, LEGACY_EYE_HEIGHT, "(" + pose + ")F", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.dimensions(), names.withEyeHeight(), "(F)" + dims, false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitLabel(tryEnd);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, node.name, GUARD, "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ARETURN);

        // Clear the guard on the way out of a failure too.
        mv.visitLabel(handler);
        mv.visitFrame(Opcodes.F_FULL, 3, new Object[]{node.name, names.pose(), names.dimensions()},
                1, new Object[]{"java/lang/Throwable"});
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, node.name, GUARD, "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static MethodNode instanceMethod(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc) && (method.access & Opcodes.ACC_STATIC) == 0) {
                return method;
            }
        }
        return null;
    }
}
