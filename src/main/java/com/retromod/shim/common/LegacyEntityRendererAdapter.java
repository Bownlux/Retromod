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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Gives a direct EntityRenderer subclass written for Minecraft 1.21.1 or earlier a 26.x render state and submit that run its old render. */
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
    public static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyEntityRenderers";
    static final String OLD_RENDERER = HELPER + "$OldRenderer";
    static final String COLLECTOR = "net/minecraft/client/renderer/SubmitNodeCollector";
    static final String CAMERA = "net/minecraft/client/renderer/state/level/CameraRenderState";
    static final String EXTRACT_DESC = "(L" + ENTITY + ";L" + ENTITY_RENDER_STATE + ";F)V";
    static final String SUBMIT_DESC = "(L" + ENTITY_RENDER_STATE + ";L" + POSE_STACK + ";L" + COLLECTOR + ";L" + CAMERA + ";)V";
    // From 1.21.2 an entity renderer draws a render state, so only an older mod's renderer has the entity-taking render.
    static final String FIRST_VERSION_WITH_STATES = "1.21.2";
    static final String VEC3 = "net/minecraft/world/phys/Vec3";
    static final String OLD_OFFSET_DESC = "(L" + ENTITY + ";F)L" + VEC3 + ";";
    static final String STATE_OFFSET_DESC = "(L" + ENTITY_RENDER_STATE + ";)L" + VEC3 + ";";
    static final String OLD_NAME_DESC = "(L" + ENTITY + ";)Z";
    static final String DISTANCE_NAME_DESC = "(L" + ENTITY + ";D)Z";

    private LegacyEntityRendererAdapter() {}

    /** The class as loadable on 26.1+, without drawing; the original array when it is outside the conservative match. */
    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, false, null);
    }

    /**
     * The class with a render state and submit that run its old render when {@code draws} (the helper is registered), else
     * only made loadable; the original array when the class is outside the conservative match.
     */
    public static byte[] apply(byte[] classBytes, boolean draws, String modSourceVersion) {
        if (classBytes == null
                || RetromodVersion.compareMcVersions(
                        RetromodVersion.TARGET_MC_VERSION, "26.1") < 0) {
            return classBytes;
        }
        if (modSourceVersion != null && RetromodVersion.compareMcVersions(modSourceVersion, FIRST_VERSION_WITH_STATES) >= 0) {
            return classBytes;
        }

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            // Only a concrete mod class directly under EntityRenderer with the old render and no render state; never a mixin.
            if (!ENTITY_RENDERER.equals(classNode.superName)
                    || classNode.name.startsWith("net/minecraft/")
                    || (classNode.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0
                    || OldRenderShapes.isMixin(classNode)
                    || hasCreateRenderState(classNode)
                    || declares(classNode, "submit", SUBMIT_DESC)
                    || !hasErasedLegacyRender(classNode)) {
                return classBytes;
            }

            boolean drawing = draws && !declares(classNode, "extractRenderState", EXTRACT_DESC);
            // Only a renderer that overrode the old offset or name rule gets the host's asked through it; any other keeps 26.x's own.
            boolean ownOffset = declares(classNode, "getRenderOffset", OLD_OFFSET_DESC);
            boolean ownNameRule = declares(classNode, "shouldShowName", OLD_NAME_DESC);
            // The base members the old render and its helpers call, which 26.x took over with render states.
            rebuildBaseMembers(classNode, drawing);
            if (drawing) {
                // The old render runs from submit with the entity its state remembers; super.render asks for the base draw,
                // from render or from a method it calls, as GeckoLib's renderFinal does.
                for (MethodNode method : classNode.methods) {
                    if (isInstanceBody(method)) routeOldBaseCalls(method);
                }
                classNode.interfaces.add(OLD_RENDERER);
                // A generic class lists its interfaces in its signature too, which reflection reads.
                // The host's base takes the state as a second type argument, which the old one-argument form lacks.
                classNode.signature = OldRenderShapes.withStateArgument(classNode.signature, ENTITY_RENDERER, ENTITY_RENDER_STATE);
                if (classNode.signature != null) classNode.signature += "L" + OLD_RENDERER + ";";
                classNode.methods.add(helperStateMethod());
                classNode.methods.add(extractRenderStateMethod());
                classNode.methods.add(submitMethod());
                if (ownOffset && !declares(classNode, "getRenderOffset", STATE_OFFSET_DESC)) {
                    classNode.methods.add(stateOffsetMethod(classNode.name));
                }
                if (ownNameRule && !declares(classNode, "shouldShowName", DISTANCE_NAME_DESC)) {
                    classNode.methods.add(distanceNameMethod(classNode.name));
                }
            } else {
                // Without the helper the class only loads: a plain state, and the old base call dropped.
                for (MethodNode method : classNode.methods) {
                    if (isInstanceBody(method)) neutralizeOldBaseCalls(method);
                }
                classNode.methods.add(createRenderStateMethod());
            }

            SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            return writer.toByteArray();
        } catch (Throwable ignored) {
            return classBytes;
        }
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
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

    private static boolean isInstanceBody(MethodNode method) {
        return (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0;
    }

    /** The 1.20.1 base getRenderOffset and shouldShowName: super calls answered as the old base did, and the base methods declared. */
    private static void rebuildBaseMembers(ClassNode node, boolean drawing) {
        for (MethodNode method : node.methods) {
            if (!isInstanceBody(method)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (isBaseCall(instruction, "getRenderOffset", OLD_OFFSET_DESC)) {
                    // The old base offset was always zero; drop the float, the entity and the receiver.
                    InsnList zero = pops(3);
                    zero.add(new FieldInsnNode(Opcodes.GETSTATIC, VEC3, "ZERO", "L" + VEC3 + ";"));
                    replace(method, instruction, zero);
                } else if (isBaseCall(instruction, "shouldShowName", OLD_NAME_DESC)) {
                    replace(method, instruction, baseNameCheck(drawing));
                }
            }
        }
        // Calls such as GeckoLib's this.getRenderOffset(entity, partialTick) resolve on the class, which now declares the old base.
        if (!declares(node, "getRenderOffset", OLD_OFFSET_DESC)) {
            MethodNode offset = new MethodNode(Opcodes.ACC_PUBLIC, "getRenderOffset", OLD_OFFSET_DESC, null, null);
            offset.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, VEC3, "ZERO", "L" + VEC3 + ";"));
            offset.instructions.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(offset);
        }
        if (!declares(node, "shouldShowName", OLD_NAME_DESC)) {
            MethodNode name = new MethodNode(Opcodes.ACC_PROTECTED, "shouldShowName", OLD_NAME_DESC, null, null);
            name.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            name.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            name.instructions.add(baseNameCheck(drawing));
            name.instructions.add(new InsnNode(Opcodes.IRETURN));
            node.methods.add(name);
        }
    }

    // With the receiver and the entity on the stack: 1.20.1's base answer through the helper, or false where nothing draws.
    private static InsnList baseNameCheck(boolean drawing) {
        if (!drawing) {
            InsnList none = pops(2);
            none.add(new InsnNode(Opcodes.ICONST_0));
            return none;
        }
        InsnList check = new InsnList();
        check.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "baseShouldShowName", "(Ljava/lang/Object;L" + ENTITY + ";)Z", false));
        return check;
    }

    private static boolean isBaseCall(AbstractInsnNode instruction, String name, String desc) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && !call.itf
                && ENTITY_RENDERER.equals(call.owner) && name.equals(call.name) && desc.equals(call.desc);
    }

    private static InsnList pops(int count) {
        InsnList pops = new InsnList();
        for (int i = 0; i < count; i++) pops.add(new InsnNode(Opcodes.POP));
        return pops;
    }

    private static void replace(MethodNode method, AbstractInsnNode instruction, InsnList replacement) {
        method.instructions.insertBefore(instruction, replacement);
        method.instructions.remove(instruction);
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

    // super.render(...) becomes LegacyEntityRenderers.superRender(this, ...), which asks submit for the host's base draw.
    private static void routeOldBaseCalls(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!isOldBaseCall(instruction)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "superRender",
                    "(Ljava/lang/Object;" + call.desc.substring(1), false));
        }
    }

    // createRenderState() { return LegacyEntityRenderers.createRenderState(); }
    private static MethodNode helperStateMethod() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "createRenderState", CREATE_STATE_DESC, null, null);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "createRenderState", CREATE_STATE_DESC, false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    // extractRenderState(entity, state, partialTick) { super.extractRenderState(...); LegacyEntityRenderers.extractRenderState(...); }
    private static MethodNode extractRenderStateMethod() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "extractRenderState", EXTRACT_DESC, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ENTITY_RENDERER, "extractRenderState", EXTRACT_DESC, false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "extractRenderState", EXTRACT_DESC, false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    // submit(state, pose, collector, camera) { if (LegacyEntityRenderers.submit(this, ...)) super.submit(state, pose, collector, camera); }
    private static MethodNode submitMethod() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "submit", SUBMIT_DESC, null, null);
        for (int slot = 0; slot <= 4; slot++) method.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "submit",
                "(L" + OLD_RENDERER + ";" + SUBMIT_DESC.substring(1, SUBMIT_DESC.length() - 1) + "Z", false));
        LabelNode done = new LabelNode();
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, done));
        for (int slot = 0; slot <= 4; slot++) method.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ENTITY_RENDERER, "submit", SUBMIT_DESC, false));
        method.instructions.add(done);
        // The locals at the join are the parameters, as on entry.
        method.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    // getRenderOffset(state) { Vec3 host = super.getRenderOffset(state); Entity e = entity(state);
    //     return e == null ? host : host.add(this.getRenderOffset(e, partialTick(state))); }
    private static MethodNode stateOffsetMethod(String owner) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "getRenderOffset", STATE_OFFSET_DESC, null, null);
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ENTITY_RENDERER, "getRenderOffset", STATE_OFFSET_DESC, false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "entity", "(L" + ENTITY_RENDER_STATE + ";)L" + ENTITY + ";", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        LabelNode known = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, known));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(known);
        code.add(new FrameNode(Opcodes.F_APPEND, 2, new Object[] {VEC3, ENTITY}, 0, null));
        // The dispatcher moved the entity by the old offset before its render, as the host moves it by this one.
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "partialTick", "(L" + ENTITY_RENDER_STATE + ";)F", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "getRenderOffset", OLD_OFFSET_DESC, false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VEC3, "add", "(L" + VEC3 + ";)L" + VEC3 + ";", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    // shouldShowName(entity, distance) { return this.shouldShowName(entity); } The host checks the distance itself.
    private static MethodNode distanceNameMethod(String owner) {
        MethodNode method = new MethodNode(Opcodes.ACC_PROTECTED, "shouldShowName", DISTANCE_NAME_DESC, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "shouldShowName", OLD_NAME_DESC, false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        return method;
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
