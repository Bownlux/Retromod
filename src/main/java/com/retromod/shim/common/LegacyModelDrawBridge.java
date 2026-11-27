/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.GeneratedLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

/** Keeps 1.20.1 entity models drawing on 1.21 to 1.21.11, where the model and part draws take one color int. */
public final class LegacyModelDrawBridge {

    /** Turns on the call pass of {@link #apply}: an old four-float model draw call takes the int draw. */
    public static final String FLAG = "legacy-model-draw";
    /** Turns on the override pass of {@link #apply}, for 1.21 and 1.21.1, whose int draw is abstract. */
    public static final String OVERRIDE_FLAG = "legacy-model-draw-override";
    static final String HELPER = "com/retromod/generated/LegacyModelPartDraw";
    static final String MODEL = "net/minecraft/client/model/Model";
    static final String ENTITY_MODEL = "net/minecraft/client/model/EntityModel";
    private static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String VERTEX_CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer";
    static final String MODEL_PART = "net/minecraft/client/model/geom/ModelPart";
    static final String OLD_DRAW = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V";
    static final String COLOR_DRAW = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V";
    static final String ARGB = "(FFFF)I";
    /** Set in an old model once its old draw failed to link, so its parent draws it from then on. */
    static final String FAILED_FIELD = "retromod$oldDrawFailed";
    /** 1.21 took the color as one int; from 1.21.2 a model draws its root itself, which later bridges cover. */
    static final String FIRST_HOST = "1.21";
    static final String LAST_ABSTRACT_DRAW_HOST = "1.21.1";

    private LegacyModelDrawBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // 26.x has its own rebuilt draws.
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0) return;
        boolean partLacksOldDraw = lacksOldDraw(host.apply(MODEL_PART), "render");
        ClassNode model = host.apply(MODEL);
        boolean modelLacksOldDraw = lacksOldDraw(model, "renderToBuffer");
        if (!partLacksOldDraw && !modelLacksOldDraw) return;
        if (!t.getSyntheticClasses().containsKey(HELPER)) t.registerSyntheticClass(HELPER, generateHelper());
        // A part's own draw: a call with four floats becomes the one with an ARGB int.
        if (partLacksOldDraw) {
            t.registerMethodRedirect(MODEL_PART, "render", OLD_DRAW, HELPER, "render", "(L" + MODEL_PART + ";" + OLD_DRAW.substring(1), true);
        }
        // A model's draw is packed in the class pass, where a super call can stay a super call.
        if (modelLacksOldDraw) t.enablePostProcess(FLAG);
        // The host calls the abstract int draw, which an old model never declared.
        if (hostHasAbstractColorDraw(model)) t.enablePostProcess(OVERRIDE_FLAG);
    }

    /** Whether the host's class has no four-float draw of that name; unreadable, a client from 1.21 lacks it. */
    static boolean lacksOldDraw(ClassNode owner, String name) {
        if (owner == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) >= 0;
        return owner.methods.stream().noneMatch(m -> m.name.equals(name) && m.desc.equals(OLD_DRAW));
    }

    /** Whether the host's Model declares the int draw abstract, as 1.21 and 1.21.1 do; unreadable, the target decides. */
    static boolean hostHasAbstractColorDraw(ClassNode model) {
        if (model == null) {
            String target = RetromodVersion.TARGET_MC_VERSION;
            return RetromodVersion.compareMcVersions(target, FIRST_HOST) >= 0
                    && RetromodVersion.compareMcVersions(target, LAST_ABSTRACT_DRAW_HOST) <= 0;
        }
        return model.methods.stream().anyMatch(m -> m.name.equals("renderToBuffer") && m.desc.equals(COLOR_DRAW)
                && (m.access & Opcodes.ACC_ABSTRACT) != 0);
    }

    /** The class with its old model draw calls packed and, when {@code addColorDraw}, an int draw that runs its old one. */
    public static byte[] apply(byte[] classBytes, boolean addColorDraw) {
        return apply(classBytes, addColorDraw, LegacyModelDrawBridge::jarClassShape);
    }

    static byte[] apply(byte[] classBytes, boolean addColorDraw, Function<String, ClassNode> modClasses) {
        if (classBytes == null) return null;
        if (!new String(classBytes, StandardCharsets.ISO_8859_1).contains("renderToBuffer")) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = packOldDrawCalls(node, modClasses);
        if (addColorDraw && needsColorDraw(node)) {
            boolean fallsBack = fallsBackToParent(node);
            if (fallsBack) {
                node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        FAILED_FIELD, "Z", null, null));
            }
            node.methods.add(colorDraw(node, fallsBack));
            changed = true;
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Packs each four-float draw call that reaches the host's int draw, a super call included, so nothing loops back. */
    private static boolean packOldDrawCalls(ClassNode node, Function<String, ClassNode> modClasses) {
        // A mixin's super call is resolved against its target by Mixin, so only its plain calls are packed.
        boolean mixin = isMixin(node);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !call.name.equals("renderToBuffer") || !call.desc.equals(OLD_DRAW)) continue;
                boolean plainCall = call.getOpcode() == Opcodes.INVOKEVIRTUAL;
                boolean superCall = call.getOpcode() == Opcodes.INVOKESPECIAL && !mixin;
                if (!(plainCall || superCall) || !reachesHostDraw(call.owner, modClasses)) continue;
                // The four floats on the stack become one ARGB int, so the arguments before them stay in place.
                method.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "argb", ARGB, false));
                call.desc = COLOR_DRAW;
                changed = true;
            }
        }
        return changed;
    }

    /** Whether a draw named on {@code owner} finds no four-float draw before a Minecraft class; an unreadable class proves nothing. */
    private static boolean reachesHostDraw(String owner, Function<String, ClassNode> modClasses) {
        String current = owner;
        for (int depth = 0; current != null && depth < 64; depth++) {
            if (current.startsWith("net/minecraft/")) return true;
            ClassNode shape = modClasses.apply(current);
            if (shape == null || declared(shape, OLD_DRAW) != null) return false;
            current = shape.superName;
        }
        return false;
    }

    /** A mod or library class with the names the class pass gives it, without code, or null. */
    private static ClassNode jarClassShape(String internalName) {
        byte[] bytes = RetromodTransformer.readJarOrLibraryClassRemapped(internalName);
        if (bytes == null) return null;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** Whether the class declares a concrete old draw and no int draw of its own. */
    private static boolean needsColorDraw(ClassNode node) {
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || isMixin(node)) return false;
        MethodNode oldDraw = declared(node, OLD_DRAW);
        if (oldDraw == null || declared(node, COLOR_DRAW) != null) return false;
        return (oldDraw.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0;
    }

    /** Only a Minecraft parent that draws itself can take over; a mod parent's int draw would dispatch back to the failing draw. */
    private static boolean fallsBackToParent(ClassNode node) {
        return node.superName.startsWith("net/minecraft/") && !node.superName.equals(MODEL) && !node.superName.equals(ENTITY_MODEL);
    }

    private static MethodNode declared(ClassNode node, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals("renderToBuffer") && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static boolean isMixin(ClassNode node) {
        List<AnnotationNode> annotations = node.invisibleAnnotations;
        return annotations != null && annotations.stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"));
    }

    /** {@code renderToBuffer(pose, consumer, light, overlay, color)}: the old draw with the color split into floats. */
    private static MethodNode colorDraw(ClassNode node, boolean fallsBack) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "renderToBuffer", COLOR_DRAW, null, null);
        m.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label failed = new Label();
        Label parentDraws = new Label();
        boolean frames = (node.version & 0xFFFF) >= Opcodes.V1_6;
        Object[] locals = {node.name, POSE_STACK, VERTEX_CONSUMER, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER};
        if (fallsBack) {
            m.visitTryCatchBlock(start, end, failed, "java/lang/LinkageError");
            // Once the old draw failed, the parent draws straight away instead of failing again every frame.
            m.visitFieldInsn(Opcodes.GETSTATIC, node.name, FAILED_FIELD, "Z");
            m.visitJumpInsn(Opcodes.IFNE, parentDraws);
        }
        m.visitLabel(start);
        pushReceiverAndArguments(m);
        // Red, green, blue and alpha, in the order the old draw takes them.
        for (int shift : new int[]{16, 8, 0, 24}) pushChannel(m, 5, shift);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, node.name, "renderToBuffer", OLD_DRAW, false);
        m.visitLabel(end);
        m.visitInsn(Opcodes.RETURN);
        if (fallsBack) {
            // An old draw the host cannot link is remembered and reported once.
            m.visitLabel(failed);
            if (frames) m.visitFrame(Opcodes.F_FULL, locals.length, locals, 1, new Object[]{"java/lang/LinkageError"});
            m.visitVarInsn(Opcodes.ASTORE, 6);
            m.visitInsn(Opcodes.ICONST_1);
            m.visitFieldInsn(Opcodes.PUTSTATIC, node.name, FAILED_FIELD, "Z");
            m.visitLdcInsn(node.name.replace('/', '.'));
            m.visitVarInsn(Opcodes.ALOAD, 6);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "oldDrawFailed", "(Ljava/lang/String;Ljava/lang/Throwable;)V", false);
            m.visitLabel(parentDraws);
            if (frames) m.visitFrame(Opcodes.F_FULL, locals.length, locals, 0, new Object[0]);
            pushReceiverAndArguments(m);
            m.visitVarInsn(Opcodes.ILOAD, 5);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, node.superName, "renderToBuffer", COLOR_DRAW, false);
            m.visitInsn(Opcodes.RETURN);
        }
        m.visitMaxs(0, 0);
        m.visitEnd();
        return m;
    }

    private static void pushReceiverAndArguments(MethodVisitor m) {
        for (int slot = 0; slot <= 2; slot++) m.visitVarInsn(Opcodes.ALOAD, slot);
        m.visitVarInsn(Opcodes.ILOAD, 3);
        m.visitVarInsn(Opcodes.ILOAD, 4);
    }

    /** Pushes one 8-bit channel of the int in {@code slot} as a float from 0 to 1. */
    private static void pushChannel(MethodVisitor m, int slot, int shift) {
        m.visitVarInsn(Opcodes.ILOAD, slot);
        if (shift > 0) {
            m.visitIntInsn(Opcodes.BIPUSH, shift);
            m.visitInsn(Opcodes.IUSHR);
        }
        m.visitIntInsn(Opcodes.SIPUSH, 0xFF);
        m.visitInsn(Opcodes.IAND);
        m.visitInsn(Opcodes.I2F);
        m.visitLdcInsn(255.0F);
        m.visitInsn(Opcodes.FDIV);
    }

    /** The helper: the part's old draw, the ARGB packing for old model draw calls, and the report of a failed old draw. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null, "java/lang/Object", null);

        // render(part, pose, consumer, light, overlay, r, g, b, a): the part's int-color draw.
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "render",
                "(L" + MODEL_PART + ";" + OLD_DRAW.substring(1), null, null);
        m.visitCode();
        for (int slot = 0; slot <= 2; slot++) m.visitVarInsn(Opcodes.ALOAD, slot);
        m.visitVarInsn(Opcodes.ILOAD, 3);
        m.visitVarInsn(Opcodes.ILOAD, 4);
        pushArgb(m, 5);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODEL_PART, "render", COLOR_DRAW, false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        // argb(r, g, b, a): the color an old model draw call passes, as the int the host's draw takes.
        MethodVisitor argb = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "argb", ARGB, null, null);
        argb.visitCode();
        pushArgb(argb, 0);
        argb.visitInsn(Opcodes.IRETURN);
        argb.visitMaxs(0, 0);
        argb.visitEnd();

        // oldDrawFailed(className, error): one warning with the cause, as the parent model takes over.
        MethodVisitor failed = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "oldDrawFailed",
                "(Ljava/lang/String;Ljava/lang/Throwable;)V", null, null);
        failed.visitCode();
        GeneratedLog.pushLogger(failed);
        failed.visitLdcInsn("The old model draw of ");
        failed.visitVarInsn(Opcodes.ALOAD, 0);
        failed.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        failed.visitLdcInsn(" does not work on this Minecraft version, so the model it extends draws it instead.");
        failed.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        failed.visitVarInsn(Opcodes.ALOAD, 1);
        GeneratedLog.warnWithCause(failed);
        failed.visitInsn(Opcodes.RETURN);
        failed.visitMaxs(0, 0);
        failed.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Pushes the four floats from {@code firstSlot} on, red first, as one ARGB int; each is clamped to 0..1 and rounded. */
    private static void pushArgb(MethodVisitor m, int firstSlot) {
        pushByte(m, firstSlot + 3, 24);
        pushByte(m, firstSlot, 16);
        m.visitInsn(Opcodes.IOR);
        pushByte(m, firstSlot + 1, 8);
        m.visitInsn(Opcodes.IOR);
        pushByte(m, firstSlot + 2, 0);
        m.visitInsn(Opcodes.IOR);
    }

    /** Pushes {@code round(clamp(slot, 0, 1) * 255) << shift}. */
    private static void pushByte(MethodVisitor m, int slot, int shift) {
        m.visitVarInsn(Opcodes.FLOAD, slot);
        m.visitInsn(Opcodes.FCONST_0);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(FF)F", false);
        m.visitInsn(Opcodes.FCONST_1);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(FF)F", false);
        m.visitLdcInsn(255.0F);
        m.visitInsn(Opcodes.FMUL);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "round", "(F)I", false);
        if (shift > 0) {
            m.visitIntInsn(Opcodes.BIPUSH, shift);
            m.visitInsn(Opcodes.ISHL);
        }
    }
}
