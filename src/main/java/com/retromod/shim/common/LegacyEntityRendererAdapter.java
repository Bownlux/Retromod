/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Keeps a narrow class of legacy entity renderers loadable after the 26.1 render-state rewrite.
 *
 * <p>MC 26.1 made {@code EntityRenderer.createRenderState()} abstract and removed the old
 * {@code render(Entity,float,float,PoseStack,MultiBufferSource,int)} base method. A concrete mod
 * renderer compiled against the old contract can therefore fail with {@code AbstractMethodError}
 * when the dispatcher asks for its state, while its direct {@code super.render(...)} call fails
 * with {@code NoSuchMethodError} if its old overload is reached.
 *
 * <p>This adapter is intentionally a load-safety bridge, not a visual renderer conversion. It
 * applies only to concrete mod classes whose direct superclass is {@code EntityRenderer}, which
 * declare the exact legacy render shape and lack {@code createRenderState()}. It injects a plain
 * {@code EntityRenderState} factory and discards any exact obsolete {@code invokespecial} base call
 * in the matching render methods. The mod's legacy render method is still not wired into the modern
 * submit pipeline, so its custom geometry may remain invisible.
 */
public final class LegacyEntityRendererAdapter {

    private static final String ENTITY_RENDERER =
            "net/minecraft/client/renderer/entity/EntityRenderer";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String ENTITY_RENDER_STATE =
            "net/minecraft/client/renderer/entity/state/EntityRenderState";
    private static final String CREATE_STATE_DESC = "()L" + ENTITY_RENDER_STATE + ";";
    private static final String VANILLA_BUFFER =
            "net/minecraft/client/renderer/MultiBufferSource";
    private static final String EMBEDDED_BUFFER_SUFFIX =
            "/com/retromod/shim/common/embedded/MultiBufferSource";

    private LegacyEntityRendererAdapter() {}

    /** Returns the original array when the class is outside the exact conservative match. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null
                || RetromodVersion.compareMcVersions(
                        RetromodVersion.TARGET_MC_VERSION, "26.1") < 0) {
            return classBytes;
        }

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            if (!ENTITY_RENDERER.equals(classNode.superName)
                    || classNode.name.startsWith("net/minecraft/")
                    || (classNode.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0
                    || hasCreateRenderState(classNode)
                    || !hasErasedLegacyRender(classNode)) {
                return classBytes;
            }

            for (MethodNode method : classNode.methods) {
                if (isLegacyRenderMethod(method)) neutralizeOldBaseCalls(method);
            }
            classNode.methods.add(createRenderStateMethod());

            SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            return writer.toByteArray();
        } catch (Throwable ignored) {
            return classBytes;
        }
    }

    private static boolean hasCreateRenderState(ClassNode classNode) {
        return classNode.methods.stream().anyMatch(method ->
                "createRenderState".equals(method.name)
                        && Type.getArgumentTypes(method.desc).length == 0);
    }

    private static boolean hasErasedLegacyRender(ClassNode classNode) {
        return classNode.methods.stream().anyMatch(method ->
                (method.access & Opcodes.ACC_PUBLIC) != 0
                        && isLegacyRenderMethod(method)
                        && ENTITY.equals(Type.getArgumentTypes(method.desc)[0].getInternalName()));
    }

    private static boolean isLegacyRenderMethod(MethodNode method) {
        return "render".equals(method.name)
                && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0
                && isLegacyRenderDescriptor(method.desc, false);
    }

    private static void neutralizeOldBaseCalls(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!isOldBaseCall(instruction)) continue;

            // Stack before the call: receiver, entity, float, float, pose stack, buffer, int.
            // Discard arguments in reverse order, then discard the receiver.
            InsnList pops = new InsnList();
            pops.add(new InsnNode(Opcodes.POP));
            pops.add(new InsnNode(Opcodes.POP));
            pops.add(new InsnNode(Opcodes.POP));
            pops.add(new InsnNode(Opcodes.POP));
            pops.add(new InsnNode(Opcodes.POP));
            pops.add(new InsnNode(Opcodes.POP));
            pops.add(new InsnNode(Opcodes.POP));
            method.instructions.insertBefore(instruction, pops);
            method.instructions.remove(instruction);
        }
    }

    private static boolean isOldBaseCall(AbstractInsnNode instruction) {
        if (!(instruction instanceof MethodInsnNode call)) return false;
        return call.getOpcode() == Opcodes.INVOKESPECIAL
                && !call.itf
                && ENTITY_RENDERER.equals(call.owner)
                && "render".equals(call.name)
                && isLegacyRenderDescriptor(call.desc, true);
    }

    private static boolean isLegacyRenderDescriptor(String descriptor, boolean requireEntity) {
        if (descriptor == null || Type.getReturnType(descriptor).getSort() != Type.VOID) {
            return false;
        }
        Type[] args = Type.getArgumentTypes(descriptor);
        if (args.length != 6
                || args[0].getSort() != Type.OBJECT
                || args[1].getSort() != Type.FLOAT
                || args[2].getSort() != Type.FLOAT
                || args[3].getSort() != Type.OBJECT
                || !POSE_STACK.equals(args[3].getInternalName())
                || args[4].getSort() != Type.OBJECT
                || !isLegacyBuffer(args[4].getInternalName())
                || args[5].getSort() != Type.INT) {
            return false;
        }
        return !requireEntity || ENTITY.equals(args[0].getInternalName());
    }

    private static boolean isLegacyBuffer(String internalName) {
        return VANILLA_BUFFER.equals(internalName)
                || "com/retromod/shim/common/embedded/MultiBufferSource".equals(internalName)
                || internalName.endsWith(EMBEDDED_BUFFER_SUFFIX);
    }

    private static MethodNode createRenderStateMethod() {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "createRenderState",
                CREATE_STATE_DESC,
                null,
                null);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, ENTITY_RENDER_STATE));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                ENTITY_RENDER_STATE,
                "<init>",
                "()V",
                false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 2;
        method.maxLocals = 1;
        return method;
    }
}
