/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the entity data registration that 1.20.5 moved onto a builder.
 *
 * <p>Before 1.20.5 an entity overrode {@code defineSynchedData()} and called
 * {@code this.entityData.define(accessor, value)}. Now the entity constructor passes a
 * {@code SynchedEntityData.Builder} to {@code defineSynchedData(Builder)} and builds the data from
 * it. An old override is never called, and the old {@code define} no longer exists, so the mob's
 * first data read fails, or the class fails to link.
 *
 * <p>Each old override is renamed to a private method, and a {@code defineSynchedData(Builder)}
 * override is added that makes the builder current for this thread and runs the old body. Inside
 * it, {@code super.defineSynchedData()} becomes the builder overload of the parent, and
 * {@code define} goes to the current builder. Private renaming keeps a subclass's override from
 * being dispatched again when its parent's body runs. A {@code define} call outside entity
 * construction has no builder and fails with an {@code IllegalStateException} that names the
 * accessor's owner.
 */
public final class LegacySynchedDataBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/LegacySynchedData";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String DATA = "net/minecraft/network/syncher/SynchedEntityData";
    static final String BUILDER = DATA + "$Builder";
    static final String ACCESSOR = "net/minecraft/network/syncher/EntityDataAccessor";
    static final String DEFINE = "defineSynchedData";
    static final String LEGACY_BODY = "retromod$legacyDefineSynchedData";
    private static final String L_BUILDER = "L" + BUILDER + ";";
    private static final String OLD_DEFINE_DESC = "(L" + ACCESSOR + ";Ljava/lang/Object;)V";
    private static final String NEW_DEFINE_DESC = "(L" + ACCESSOR + ";Ljava/lang/Object;)" + L_BUILDER;

    private LegacySynchedDataBridge() {}

    /** Registers the bridge on hosts whose entities define their data through a builder. */
    public static void register(RetromodTransformer transformer) {
        if (!hostDefinesThroughBuilder()) return;
        registerRedirects(transformer);
        LOGGER.info("Bridged pre-1.20.5 entity data definitions onto SynchedEntityData.Builder");
    }

    /**
     * The host's own classes decide. Offline, where Minecraft is not readable, the host version
     * does: 1.20.5 moved entity data definitions onto the builder.
     */
    static boolean hostDefinesThroughBuilder() {
        ClassNode entity = ClassResourceInspector.read(ENTITY);
        ClassNode data = ClassResourceInspector.read(DATA);
        if (entity == null && data == null) {
            return com.retromod.core.RetromodVersion.compareMcVersions(
                    com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.20.5") >= 0;
        }
        return entity != null && data != null && hasMethod(entity, DEFINE, "(" + L_BUILDER + ")V")
                && !hasMethod(data, "define", OLD_DEFINE_DESC);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        transformer.registerMethodRedirect(DATA, "define", OLD_DEFINE_DESC, HELPER, "define",
                "(L" + DATA + ";L" + ACCESSOR + ";Ljava/lang/Object;)V", true);
    }

    /** Whether {@link #apply} should run: only once {@link #registerRedirects} added the helper. */
    public static boolean isRegistered(java.util.function.Predicate<String> hasSynthetic) {
        return hasSynthetic.test(HELPER);
    }

    /** Moves old {@code defineSynchedData()} overrides and super calls onto the builder overload. */
    public static byte[] apply(byte[] classBytes) {
        String constants = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!constants.contains(DEFINE)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        List<MethodNode> bridges = new ArrayList<>();
        for (MethodNode method : node.methods) {
            changed |= retargetSuperCalls(method);
            if (isLegacyOverride(node, method)) {
                method.name = LEGACY_BODY;
                method.access = (method.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                        | Opcodes.ACC_PRIVATE;
                bridges.add(builderOverride(node.name));
                changed = true;
            }
        }
        if (!changed) return classBytes;
        node.methods.addAll(bridges);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * An instance {@code defineSynchedData()V} whose body registers entity data or chains to a
     * parent's. Requiring one of those calls keeps an unrelated method of the same name alone.
     */
    private static boolean isLegacyOverride(ClassNode owner, MethodNode method) {
        if (!method.name.equals(DEFINE) || !method.desc.equals("()V")
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0
                || owner.superName == null || owner.superName.equals("java/lang/Object")) {
            return false;
        }
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && ((call.name.equals("define")
                    && call.owner.equals(HELPER)) || (call.name.equals(DEFINE)
                    && call.desc.equals("(" + L_BUILDER + ")V")))) {
                return true;
            }
        }
        return false;
    }

    /** {@code super.defineSynchedData()} becomes {@code super.defineSynchedData(current builder)}. */
    private static boolean retargetSuperCalls(MethodNode method) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals(DEFINE) && call.desc.equals("()V")) {
                method.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        HELPER, "current", "()" + L_BUILDER, false));
                call.desc = "(" + L_BUILDER + ")V";
                changed = true;
            }
        }
        return changed;
    }

    /** {@code defineSynchedData(Builder)}: make the builder current, run the old body, restore. */
    private static MethodNode builderOverride(String owner) {
        MethodNode bridge = new MethodNode(Opcodes.ACC_PROTECTED, DEFINE, "(" + L_BUILDER + ")V", null, null);
        InsnList code = bridge.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "push",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, LEGACY_BODY, "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "push",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        code.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
        code.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        return bridge;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CURRENT",
                "Ljava/lang/ThreadLocal;", null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/lang/ThreadLocal");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, HELPER, "CURRENT", "Ljava/lang/ThreadLocal;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        // push(builder): sets the current builder and returns the previous one for restoring.
        MethodVisitor push = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "push",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        push.visitCode();
        push.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "CURRENT", "Ljava/lang/ThreadLocal;");
        push.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false);
        push.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "CURRENT", "Ljava/lang/ThreadLocal;");
        push.visitVarInsn(Opcodes.ALOAD, 0);
        push.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V", false);
        push.visitInsn(Opcodes.ARETURN);
        push.visitMaxs(0, 0);
        push.visitEnd();

        MethodVisitor current = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "current",
                "()" + L_BUILDER, null, null);
        current.visitCode();
        current.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "CURRENT", "Ljava/lang/ThreadLocal;");
        current.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false);
        current.visitTypeInsn(Opcodes.CHECKCAST, BUILDER);
        current.visitInsn(Opcodes.ARETURN);
        current.visitMaxs(0, 0);
        current.visitEnd();

        // define(data, accessor, value): the old entityData.define, onto the current builder.
        MethodVisitor define = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "define",
                "(L" + DATA + ";L" + ACCESSOR + ";Ljava/lang/Object;)V", null, null);
        define.visitCode();
        Label active = new Label();
        define.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "current", "()" + L_BUILDER, false);
        define.visitVarInsn(Opcodes.ASTORE, 3);
        define.visitVarInsn(Opcodes.ALOAD, 3);
        define.visitJumpInsn(Opcodes.IFNONNULL, active);
        define.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        define.visitInsn(Opcodes.DUP);
        define.visitLdcInsn("Retromod: a pre-1.20.5 entity defined synced data outside defineSynchedData; "
                + "1.20.5 and newer only accept entity data while the entity is constructed");
        define.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                "(Ljava/lang/String;)V", false);
        define.visitInsn(Opcodes.ATHROW);
        define.visitLabel(active);
        define.visitVarInsn(Opcodes.ALOAD, 3);
        define.visitVarInsn(Opcodes.ALOAD, 1);
        define.visitVarInsn(Opcodes.ALOAD, 2);
        define.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "define", NEW_DEFINE_DESC, false);
        define.visitInsn(Opcodes.POP);
        define.visitInsn(Opcodes.RETURN);
        define.visitMaxs(0, 0);
        define.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
