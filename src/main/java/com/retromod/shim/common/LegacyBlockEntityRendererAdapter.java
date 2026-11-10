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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/** Gives a block entity renderer written for Minecraft 1.21.4 or earlier a 26.x render state and submit that run its old render. */
public final class LegacyBlockEntityRendererAdapter {

    static final String RENDERER = "net/minecraft/client/renderer/blockentity/BlockEntityRenderer";
    public static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyBlockEntityRenderers";
    static final String OLD_RENDERER = HELPER + "$OldRenderer";
    static final String STATE = "net/minecraft/client/renderer/blockentity/state/BlockEntityRenderState";
    static final String COLLECTOR = "net/minecraft/client/renderer/SubmitNodeCollector";
    static final String CAMERA = "net/minecraft/client/renderer/state/level/CameraRenderState";
    static final String CRUMBLING = "net/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay";
    static final String VEC3 = "net/minecraft/world/phys/Vec3";
    static final String CREATE_DESC = "()L" + STATE + ";";
    static final String EXTRACT_DESC = "(L" + OldRenderShapes.BLOCK_ENTITY + ";L" + STATE + ";FL" + VEC3 + ";L" + CRUMBLING + ";)V";
    static final String SUBMIT_DESC = "(L" + STATE + ";L" + OldRenderShapes.POSE_STACK + ";L" + COLLECTOR + ";L" + CAMERA + ";)V";
    // From 1.21.9 a block entity renderer submits a render state, so only an older mod's renderer has the old render.
    static final String FIRST_VERSION_WITH_STATES = "1.21.9";
    // The old render's name in a jar's own classes: Mojang, Forge 1.20.1's SRG, and Fabric's intermediary.
    private static final Set<String> OLD_NAMES = Set.of("render", "m_6922_", "method_3569");
    private static final Type[] OLD_ARGS = {Type.getObjectType(OldRenderShapes.BLOCK_ENTITY), Type.FLOAT_TYPE,
        Type.getObjectType(OldRenderShapes.POSE_STACK), Type.getObjectType(HostBuffers.HOST_BUFFERS), Type.INT_TYPE, Type.INT_TYPE};

    private LegacyBlockEntityRendererAdapter() {}

    /** The class with a render state, extract and submit added, or the bytes as given when it is not an old mod renderer. */
    public static byte[] apply(byte[] classBytes, String modSourceVersion) {
        if (classBytes == null) return null;
        if (modSourceVersion != null && RetromodVersion.compareMcVersions(modSourceVersion, FIRST_VERSION_WITH_STATES) >= 0) {
            return classBytes;
        }
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            if (!isOldRenderer(node)) return classBytes;
            node.interfaces.add(OLD_RENDERER);
            // A generic class lists its interfaces in its signature too, which reflection reads.
            // The host's base takes the state as a second type argument, which the old one-argument form lacks.
            node.signature = OldRenderShapes.withStateArgument(node.signature, RENDERER, STATE);
            if (node.signature != null) node.signature += "L" + OLD_RENDERER + ";";
            node.methods.add(createRenderState());
            node.methods.add(extractRenderState());
            node.methods.add(submit());
            SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
    }

    // A mod's own renderer, built from its own classes, that first declares the old render and knows no render state.
    static boolean isOldRenderer(ClassNode node) {
        // A mixin is merged into its target, and a vanilla class keeps the host's own draw.
        if (OldRenderShapes.isMixin(node) || (node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")) return false;
        if (node.methods.stream().noneMatch(LegacyBlockEntityRendererAdapter::isOldRender)) return false;
        if (node.methods.stream().anyMatch(m -> m.name.equals("createRenderState") || m.name.equals("submit") && m.desc.equals(SUBMIT_DESC))) {
            return false;
        }
        // Only through the jar's own classes: under a vanilla renderer, the vanilla state and submit still draw.
        if (!superclassesInJar(node.superName) || !OldRenderShapes.implementsThroughJar(node, RENDERER)) return false;
        // The bridge goes where the old render is first declared, so a subclass inherits it and draws once.
        return !OldRenderShapes.jarAncestorDeclares(node.superName, LegacyBlockEntityRendererAdapter::isOldRender);
    }

    /** render(BlockEntity, float, PoseStack, a buffer source, int, int): the erased 1.21.4 and earlier draw call. */
    static boolean isOldRender(MethodNode method) {
        return OLD_NAMES.contains(method.name) && OldRenderShapes.isInstance(method) && OldRenderShapes.takes(method.desc, OLD_ARGS);
    }

    private static boolean superclassesInJar(String superName) {
        String current = superName;
        for (int depth = 0; depth < 16 && current != null; depth++) {
            if (current.equals("java/lang/Object")) return true;
            ClassNode ancestor = OldRenderShapes.jarClass(current);
            if (ancestor == null) return false;
            current = ancestor.superName;
        }
        return false;
    }

    // createRenderState() { return LegacyBlockEntityRenderers.createRenderState(); }
    private static MethodNode createRenderState() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "createRenderState", CREATE_DESC, null, null);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "createRenderState", CREATE_DESC, false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    // extractRenderState(entity, state, partialTick, cameraPos, crumbling) { LegacyBlockEntityRenderers.extractRenderState(entity, state, partialTick, crumbling); }
    private static MethodNode extractRenderState() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "extractRenderState", EXTRACT_DESC, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "extractRenderState",
                "(L" + OldRenderShapes.BLOCK_ENTITY + ";L" + STATE + ";FL" + CRUMBLING + ";)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    // submit(state, pose, collector, camera) { LegacyBlockEntityRenderers.submit(this, state, pose, collector, camera); }
    private static MethodNode submit() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "submit", SUBMIT_DESC, null, null);
        for (int slot = 0; slot <= 4; slot++) method.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "submit",
                "(L" + OLD_RENDERER + ";" + SUBMIT_DESC.substring(1), false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }
}
