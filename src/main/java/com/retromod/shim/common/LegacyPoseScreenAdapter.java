/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Lets the game run an old screen's own {@code render(PoseStack, ...)} on 26.1 and newer, which call {@code extractRenderState}. */
public final class LegacyPoseScreenAdapter {

    public static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/client/gui/LegacyPoseScreens";
    static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    static final String GRAPHICS = "net/minecraft/client/gui/GuiGraphicsExtractor";
    static final String OLD_RENDER = "(L" + POSE_STACK + ";IIF)V";
    static final String NEW_RENDER = "(L" + GRAPHICS + ";IIF)V";

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-gui-overrides");
    /** How deep the reachability check follows the screen's own methods that the old render calls. */
    static final int MAX_DEPTH = 3;

    private LegacyPoseScreenAdapter() {}

    /** The class with the old render reachable from the new one; the original bytes when nothing applies or rewriting fails. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !mentions(classBytes, OLD_RENDER)) return classBytes;
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            // render is a common name, so only a class the jar or the host proves is a screen changes. A mixin's
            // methods merge into a screen that already has the host's extract, so it gets no bridge.
            if (node.superName == null || StaticFieldIdStamper.isMixin(node)
                    || !RetromodTransformer.currentJarClassInheritsFrom(node.superName, SCREEN)) {
                return classBytes;
            }
            Function<String, byte[]> jarClasses = RetromodTransformer::readCurrentJarClassForAdapter;
            int changes = rewriteSuperRenders(node, jarClasses);
            // A render that reaches a member the host lacks keeps the host's own extract, which draws the widgets.
            String missing = missingMember(node, RetromodTransformer::readCurrentJarClassShapeForAdapter, ClassResourceInspector::read);
            if (missing == null) {
                changes += addExtractBridge(node);
            } else {
                LOGGER.debug("Left the old render of {} out of the frame: it reaches {}, which the host lacks", node.name, missing);
            }
            if (changes == 0) return classBytes;
            SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            return classBytes;
        }
    }

    /**
     * Adds {@code extractRenderState(graphics, x, y, delta)}, which runs the old render with a pose stack drawing into
     * the frame. A render that fails anyway falls back to the host's own extract, then and for the rest of the session.
     */
    static int addExtractBridge(ClassNode node) {
        MethodNode old = find(node, "render", OLD_RENDER);
        if (old == null || find(node, "extractRenderState", NEW_RENDER) != null
                || (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) {
            return 0;
        }
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "extractRenderState", NEW_RENDER, null, null);
        InsnList code = bridge.instructions;
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode failed = new LabelNode();
        LabelNode host = new LabelNode();
        // A class whose old render failed before goes straight to the host's extract.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "runsOldRender", "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, host));
        code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "poseFor", "(L" + GRAPHICS + ";)L" + POSE_STACK + ";", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.FLOAD, 4));
        // Virtual, so a subclass's own old render runs, as the game called it before.
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "render", OLD_RENDER, false));
        code.add(end);
        code.add(new InsnNode(Opcodes.RETURN));
        // A missing member or a runtime failure in the old render is recorded once, and the frame still gets the widgets.
        code.add(failed);
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "oldRenderFailed", "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false));
        code.add(host);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.FLOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "extractRenderState", NEW_RENDER, false));
        code.add(new InsnNode(Opcodes.RETURN));
        bridge.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, "java/lang/LinkageError"));
        bridge.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, "java/lang/RuntimeException"));
        node.methods.add(bridge);
        return 1;
    }

    /**
     * The first game member the old render reaches that the host lacks, following the screen's own methods a few calls
     * deep, or null when none is proven missing. A class that neither the jar nor the host has, such as a library's,
     * proves nothing. Other jar classes are read only for their names: their code is not translated yet.
     */
    static String missingMember(ClassNode screen, Function<String, ClassNode> jarShapes, Function<String, ClassNode> hostClasses) {
        MethodNode render = find(screen, "render", OLD_RENDER);
        if (render == null) return null;
        Map<String, Optional<ClassNode>> classes = new HashMap<>();
        Function<String, ClassNode> lookup = name -> classes.computeIfAbsent(name, key -> {
            if (key.equals(screen.name)) return Optional.of(screen);
            ClassNode jar = jarShapes == null ? null : jarShapes.apply(key);
            return Optional.ofNullable(jar != null ? jar : hostClasses.apply(key));
        }).orElse(null);
        return missingMember(screen, render, lookup, new HashSet<>(), 0);
    }

    private static String missingMember(ClassNode owner, MethodNode method, Function<String, ClassNode> classes,
            Set<String> seen, int depth) {
        if (method.instructions == null || !seen.add(owner.name + "." + method.name + method.desc)) return null;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                // Retromod's own helpers are embedded with the mod, and arrays answer every Object method.
                if (call.owner.startsWith("com/retromod/") || call.owner.startsWith("[")) continue;
                Object[] found = resolveMethod(call.owner, call.name, call.desc, classes);
                if (found == UNKNOWN) continue;
                if (found == null) return call.owner + "." + call.name + call.desc;
                // The screen's own helpers that the old render calls run now too.
                if (depth < MAX_DEPTH && found[0] == owner) {
                    String deeper = missingMember(owner, (MethodNode) found[1], classes, seen, depth + 1);
                    if (deeper != null) return deeper;
                }
            } else if (insn instanceof FieldInsnNode field && !field.owner.startsWith("com/retromod/")) {
                if (!resolvesField(field.owner, field.name, field.desc, classes)) return field.owner + "." + field.name;
            }
        }
        return null;
    }

    private static final Object[] UNKNOWN = new Object[0];

    /** The declaring class and method, null when the whole hierarchy is readable and lacks it, or UNKNOWN. */
    private static Object[] resolveMethod(String owner, String name, String desc, Function<String, ClassNode> classes) {
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(owner);
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            String current = pending.poll();
            if (!visited.add(current)) continue;
            ClassNode type = classes.apply(current);
            if (type == null) return UNKNOWN;
            MethodNode method = find(type, name, desc);
            if (method != null) return new Object[] {type, method};
            // MethodHandle and VarHandle calls are signature polymorphic.
            if (current.equals("java/lang/invoke/MethodHandle") || current.equals("java/lang/invoke/VarHandle")) return UNKNOWN;
            if (type.superName != null) pending.add(type.superName);
            pending.addAll(type.interfaces);
        }
        return null;
    }

    private static boolean resolvesField(String owner, String name, String desc, Function<String, ClassNode> classes) {
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(owner);
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            String current = pending.poll();
            if (!visited.add(current)) continue;
            ClassNode type = classes.apply(current);
            if (type == null) return true;
            for (FieldNode field : type.fields) {
                if (field.name.equals(name) && field.desc.equals(desc)) return true;
            }
            if (type.superName != null) pending.add(type.superName);
            pending.addAll(type.interfaces);
        }
        return false;
    }

    /** {@code super.render(pose, x, y, delta)} that reaches a host screen becomes that screen's {@code extractRenderState}. */
    static int rewriteSuperRenders(ClassNode node, Function<String, byte[]> jarClasses) {
        int changes = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !call.name.equals("render") || !call.desc.equals(OLD_RENDER) || call.owner.equals(node.name)
                        || !reachesHost(call.owner, jarClasses)) {
                    continue;
                }
                method.instructions.insertBefore(call, superExtract(method, call.owner));
                method.instructions.remove(call);
                changes++;
            }
        }
        return changes;
    }

    /** With {@code [this, pose, x, y, delta]} on the stack: the super extract into the pose stack's frame, or nothing without one. */
    private static InsnList superExtract(MethodNode method, String owner) {
        int delta = method.maxLocals;
        int y = delta + 1;
        int x = delta + 2;
        int graphics = delta + 3;
        method.maxLocals += 4;
        LabelNode skip = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.FSTORE, delta));
        code.add(new VarInsnNode(Opcodes.ISTORE, y));
        code.add(new VarInsnNode(Opcodes.ISTORE, x));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "graphicsOf", "(L" + POSE_STACK + ";)L" + GRAPHICS + ";", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, graphics));
        // A pose stack the mod made itself has no frame to draw the widgets into.
        code.add(new VarInsnNode(Opcodes.ALOAD, graphics));
        code.add(new JumpInsnNode(Opcodes.IFNULL, skip));
        code.add(new VarInsnNode(Opcodes.ALOAD, graphics));
        code.add(new VarInsnNode(Opcodes.ILOAD, x));
        code.add(new VarInsnNode(Opcodes.ILOAD, y));
        code.add(new VarInsnNode(Opcodes.FLOAD, delta));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "extractRenderState", NEW_RENDER, false));
        code.add(new JumpInsnNode(Opcodes.GOTO, done));
        code.add(skip);
        code.add(new InsnNode(Opcodes.POP));
        code.add(done);
        return code;
    }

    /**
     * Whether the super call reaches the host's screen: no jar class on the way declares the old render. A class
     * that cannot be read, such as one from a library jar, may declare it, and its own bridge would loop back here.
     */
    static boolean reachesHost(String owner, Function<String, byte[]> jarClasses) {
        Set<String> seen = new HashSet<>();
        String current = owner;
        while (current != null && seen.add(current)) {
            if (current.startsWith("net/minecraft/")) return true;
            byte[] bytes = jarClasses.apply(current);
            if (bytes == null) return false;
            ClassNode type = new ClassNode();
            new ClassReader(bytes).accept(type, ClassReader.SKIP_CODE);
            if (find(type, "render", OLD_RENDER) != null) return false;
            current = type.superName;
        }
        return false;
    }

    private static MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static boolean mentions(byte[] classBytes, String text) {
        byte[] needle = text.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= classBytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (classBytes[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
