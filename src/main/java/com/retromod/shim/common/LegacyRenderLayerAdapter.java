/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/** Gives a render layer written for Minecraft 1.21.1 or earlier a 26.x submit that runs its old render with the entity. */
public final class LegacyRenderLayerAdapter {

    static final String RENDER_LAYER = "net/minecraft/client/renderer/entity/layers/RenderLayer";
    // A Fabric mod's own classes still name their base by intermediary before the remap reaches them.
    static final String RENDER_LAYER_INTERMEDIARY = "net/minecraft/class_3887";
    static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    static final String POSE_STACK_INTERMEDIARY = "net/minecraft/class_4587";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String COLLECTOR = "net/minecraft/client/renderer/SubmitNodeCollector";
    static final String STATE = "net/minecraft/client/renderer/entity/state/EntityRenderState";
    static final String SUBMIT_DESC = "(L" + POSE_STACK + ";L" + COLLECTOR + ";IL" + STATE + ";FF)V";
    static final String EMBEDDED_BUFFERS = RenderBufferSynthetics.MBS;
    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    static final String OLD_LAYER = LegacyPlayerLayersBridge.LAYERS + "$OldLayer";
    // From 1.21.2 a layer draws a render state, so only an older mod's layer has the entity-taking render.
    static final String FIRST_VERSION_WITH_STATES = "1.21.2";
    // Deeper mod hierarchies than this are not followed; a layer that deep keeps the old behavior.
    private static final int MAX_DEPTH = 16;
    // The old render's name in a jar's own classes: Mojang, Forge 1.20.1's SRG, Fabric's intermediary, and that name mapped.
    private static final java.util.Set<String> OLD_NAMES = java.util.Set.of("render", "m_6494_", "method_4199", "submit");

    private LegacyRenderLayerAdapter() {}

    /** The class with a submit added and the old layer interface, or the bytes as given when the class is not an old mod layer. */
    public static byte[] apply(byte[] classBytes, String modSourceVersion) {
        if (classBytes == null) return null;
        if (modSourceVersion != null && RetromodVersion.compareMcVersions(modSourceVersion, FIRST_VERSION_WITH_STATES) >= 0) {
            return classBytes;
        }
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            // A mixin extending RenderLayer is merged into its target, so it keeps the shape Mixin expects.
            if (isMixin(node) || (node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")) return classBytes;
            // Calls such as super.method_4199(...) follow the old render back to its name, in every class of the mod.
            boolean changed = renameOldRenderCalls(node);
            // Only a chain of the mod's own classes up to RenderLayer: under a vanilla layer, the vanilla submit still draws.
            if (!extendsRenderLayerThroughMod(node.superName)) return changed ? write(reader, node) : classBytes;

            // Fabric's map names the old render method_4199 submit, so an old Fabric layer arrives with the old render under that name.
            for (MethodNode method : node.methods) {
                if (method.name.equals("submit") && isOldRender(method.desc)) {
                    method.name = "render";
                    changed = true;
                }
            }
            // The submit goes where the entity-taking render is first declared, so a subclass inherits it and draws once.
            if (declaresEntityRender(node) && !declaresSubmit(node) && !ancestorDeclaresOldRender(node.superName)) {
                node.interfaces.add(OLD_LAYER);
                node.methods.add(submit(node.name));
                changed = true;
            }
            return changed ? write(reader, node) : classBytes;
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
    }

    private static byte[] write(ClassReader reader, ClassNode node) {
        SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    // Fabric's map names the old render submit, so a call to it under that name and the old shape goes back to render.
    private static boolean renameOldRenderCalls(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("submit") && call.getOpcode() != Opcodes.INVOKESTATIC
                        && isOldRender(call.desc)) {
                    call.name = "render";
                    changed = true;
                }
            }
        }
        return changed;
    }

    static boolean isMixin(ClassNode node) {
        return hasAnnotation(node.visibleAnnotations) || hasAnnotation(node.invisibleAnnotations);
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(a -> a.desc.equals(MIXIN));
    }

    // Walks the jar's own classes up from the superclass; the first class the jar does not have must be RenderLayer.
    private static boolean extendsRenderLayerThroughMod(String superName) {
        String current = superName;
        for (int depth = 0; depth < MAX_DEPTH && current != null; depth++) {
            if (current.equals(RENDER_LAYER) || current.equals(RENDER_LAYER_INTERMEDIARY)) return true;
            ClassNode ancestor = jarClass(current);
            if (ancestor == null) return false;
            current = ancestor.superName;
        }
        return false;
    }

    // Whether one of the jar's own ancestors declares the old render, under any of its names, since its bytes are not yet remapped.
    private static boolean ancestorDeclaresOldRender(String superName) {
        String current = superName;
        for (int depth = 0; depth < MAX_DEPTH && current != null; depth++) {
            if (current.equals(RENDER_LAYER) || current.equals(RENDER_LAYER_INTERMEDIARY)) return false;
            ClassNode ancestor = jarClass(current);
            if (ancestor == null) return false;
            if (ancestor.methods.stream().anyMatch(m -> OLD_NAMES.contains(m.name) && hasOldRenderShape(m.desc, true))) return true;
            current = ancestor.superName;
        }
        return false;
    }

    private static ClassNode jarClass(String name) {
        byte[] bytes = RetromodTransformer.readCurrentJarClassForAdapter(name);
        if (bytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    private static boolean declaresEntityRender(ClassNode node) {
        return node.methods.stream().anyMatch(m -> m.name.equals("render") && (m.access & Opcodes.ACC_STATIC) == 0
                && isOldRender(m.desc) && Type.getArgumentTypes(m.desc)[3].getInternalName().equals(ENTITY));
    }

    private static boolean declaresSubmit(ClassNode node) {
        return node.methods.stream().anyMatch(m -> m.name.equals("submit") && m.desc.contains("L" + COLLECTOR + ";"));
    }

    /** render(PoseStack, a buffer source, int, an entity, six floats), after the remap: the 1.21.1 and earlier draw call. */
    static boolean isOldRender(String desc) {
        if (!hasOldRenderShape(desc, false)) return false;
        String buffers = Type.getArgumentTypes(desc)[1].getInternalName();
        return buffers.equals(HostBuffers.HOST_BUFFERS) || buffers.endsWith(EMBEDDED_BUFFERS);
    }

    // Ten arguments: a pose stack, an object, an int, an object, then six floats, returning nothing.
    private static boolean hasOldRenderShape(String desc, boolean intermediaryToo) {
        Type[] args = Type.getArgumentTypes(desc);
        if (args.length != 10 || Type.getReturnType(desc) != Type.VOID_TYPE) return false;
        if (args[0].getSort() != Type.OBJECT || args[1].getSort() != Type.OBJECT || args[2] != Type.INT_TYPE || args[3].getSort() != Type.OBJECT) {
            return false;
        }
        String pose = args[0].getInternalName();
        if (!pose.equals(POSE_STACK) && !(intermediaryToo && pose.equals(POSE_STACK_INTERMEDIARY))) return false;
        for (int i = 4; i < 10; i++) {
            if (args[i] != Type.FLOAT_TYPE) return false;
        }
        return true;
    }

    // submit(pose, collector, light, state, yRot, xRot) { LegacyRenderLayers.submit(this, pose, collector, light, state, yRot, xRot); }
    private static MethodNode submit(String owner) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "submit", SUBMIT_DESC, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 5));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 6));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LegacyPlayerLayersBridge.LAYERS, "submit",
                "(L" + OLD_LAYER + ";L" + POSE_STACK + ";L" + COLLECTOR + ";IL" + STATE + ";FF)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 7;
        method.maxLocals = 7;
        return method;
    }
}
