/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/** Gives a mod's own VertexConsumer written for 1.20.1 the 1.21 builder methods, each running the old method the class declares. */
public final class LegacyVertexConsumerAdapter {

    static final String OPEN = "retromod$vertexOpen";
    static final String END = "retromod$endVertex";
    static final String DEFAULT_COLOR = "retromod$defaultColor";
    static final String UNSET_DEFAULT_COLOR = "retromod$unsetDefaultColor";

    /** One naming of the consumer: the 1.21 methods to add and the names the old methods may carry after the remap. */
    record Scheme(String consumer, String oldConsumer, String addVertex, String setColor, String setColorArgb, String setUv,
            String setUv1, String setUv2, String setNormal, List<String> doNothing, List<String> vertex, List<String> color,
            List<String> uv, List<String> overlay, List<String> uv2, List<String> normal, List<String> end, List<String> defaultColor,
            List<String> unsetDefaultColor) {
        String ret() {
            return "L" + consumer + ";";
        }
    }

    // Forge and NeoForge mods carry 1.20.1's names. A Fabric 1.20.1 mod on 26.x carries 1.21's names where the
    // intermediary id lived on, so its old color is already setColor and its old vertex is addVertex(double...).
    static final Scheme MOJANG = new Scheme(LegacyVertexApiBridge.CONSUMER, LegacyVertexApiBridge.HELPER + "$OldConsumer",
            "addVertex", "setColor", "setColor", "setUv", "setUv1", "setUv2", "setNormal",
            List.of("setLineWidth(F)", "setUv3(FF)"),
            List.of("vertex", "addVertex"), List.of("color", "setColor"), List.of("uv", "setUv"), List.of("overlayCoords"),
            List.of("uv2", "setUv2"), List.of("normal", "setNormal"), List.of("endVertex"), List.of("defaultColor"),
            List.of("unsetDefaultColor"));
    // A Fabric 1.21 to 1.21.11 host: the ids that kept their shape already implement the 1.21 method of the same id.
    static final Scheme INTERMEDIARY = new Scheme(LegacyVertexApiBridge.INTERMEDIARY_CONSUMER,
            LegacyVertexApiBridge.INTERMEDIARY_HELPER + "$OldConsumer",
            "method_22912", "method_1336", "method_39415", "method_22913", "method_60796", "method_22921", "method_22914",
            List.of("method_75298(F)"),
            List.of("method_22912"), List.of("method_1336"), List.of("method_22913"), List.of("method_22917"),
            List.of("method_22921"), List.of("method_22914"), List.of("method_1344"), List.of("method_22901"),
            List.of("method_35666"));

    private LegacyVertexConsumerAdapter() {}

    /** The class with the 1.21 builder methods when it is an old consumer of a scheme whose helper is registered; else the same array. */
    public static byte[] apply(byte[] classBytes, boolean mojang, boolean intermediary) {
        if (classBytes == null || (!mojang && !intermediary)) return classBytes;
        try {
            ClassReader reader = new ClassReader(classBytes);
            // Most classes name no consumer, so the header decides before the whole class is read.
            if (!listsConsumer(reader, mojang, intermediary)) return classBytes;
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            Scheme scheme = schemeOf(node, mojang, intermediary);
            if (scheme == null || !adapt(node, scheme)) return classBytes;
            SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException failure) {
            return classBytes;
        }
    }

    private static boolean listsConsumer(ClassReader reader, boolean mojang, boolean intermediary) {
        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0) return false;
        for (String name : reader.getInterfaces()) {
            if (mojang && name.equals(MOJANG.consumer()) || intermediary && name.equals(INTERMEDIARY.consumer())) return true;
        }
        return false;
    }

    private static Scheme schemeOf(ClassNode node, boolean mojang, boolean intermediary) {
        // Only a mod's own class that names the consumer itself; mixins and interfaces keep their shape.
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")
                || node.name.startsWith("com/mojang/") || OldRenderShapes.isMixin(node)) {
            return null;
        }
        if (mojang && node.interfaces.contains(MOJANG.consumer())) return MOJANG;
        if (intermediary && node.interfaces.contains(INTERMEDIARY.consumer())) return INTERMEDIARY;
        return null;
    }

    private static boolean adapt(ClassNode node, Scheme s) {
        String ret = s.ret();
        String vertex = declared(node, s.vertex(), "(DDD)" + ret);
        String color = declared(node, s.color(), "(IIII)" + ret);
        String uv = declared(node, s.uv(), "(FF)" + ret);
        String overlay = declared(node, s.overlay(), "(II)" + ret);
        String uv2 = declared(node, s.uv2(), "(II)" + ret);
        String normal = declared(node, s.normal(), "(FFF)" + ret);
        String end = declared(node, s.end(), "()V");
        // A consumer already on the 1.21 builder, or one whose old methods live elsewhere, is left as it is.
        if (declares(node, s.addVertex(), "(FFF)" + ret) || node.interfaces.contains(s.oldConsumer())
                || vertex == null || color == null || uv == null || overlay == null || uv2 == null || normal == null || end == null) {
            return false;
        }

        node.interfaces.add(s.oldConsumer());
        // A generic class lists its interfaces in its signature too, which reflection reads.
        if (node.signature != null) node.signature += "L" + s.oldConsumer() + ";";
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, OPEN, "Z", null, null));
        clearOpenOnEntry(node, end);

        addVertexStart(node, s, vertex);
        forward(node, s.setColor(), "(IIII)" + ret, color, "IIII");
        forward(node, s.setUv(), "(FF)" + ret, uv, "FF");
        forward(node, s.setUv1(), "(II)" + ret, overlay, "II");
        forward(node, s.setUv2(), "(II)" + ret, uv2, "II");
        forward(node, s.setNormal(), "(FFF)" + ret, normal, "FFF");
        addColorArgb(node, s);
        for (String member : s.doNothing()) addReturnThis(node, member.substring(0, member.indexOf('(')), member.substring(member.indexOf('(')) + ret);
        addWholeVertex(node, s, vertex, color, uv, overlay, uv2, normal);
        addEnd(node, end);
        addForwardOrNothing(node, DEFAULT_COLOR, "(IIII)V", declared(node, s.defaultColor(), "(IIII)V"));
        addForwardOrNothing(node, UNSET_DEFAULT_COLOR, "()V", declared(node, s.unsetDefaultColor(), "()V"));
        return true;
    }

    // addVertex(x, y, z): ends the vertex host code left open, then starts the next through the old vertex(double...).
    private static void addVertexStart(ClassNode node, Scheme s, String vertex) {
        MethodNode method = newMethod(node, s.addVertex(), "(FFF)" + s.ret());
        if (method == null) return;
        InsnList code = method.instructions;
        endOpenVertex(node, code);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int slot = 1; slot <= 3; slot++) {
            code.add(new VarInsnNode(Opcodes.FLOAD, slot));
            code.add(new InsnNode(Opcodes.F2D));
        }
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, vertex, "(DDD)" + s.ret(), false));
        // 1.21 never calls endVertex, so the next vertex or a whole vertex call ends this one.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, OPEN, "Z"));
        code.add(new InsnNode(Opcodes.ARETURN));
    }

    // A 1.21 setter that runs the old one of the same arguments: setColor -> color, setUv1 -> overlayCoords and so on.
    private static void forward(ClassNode node, String name, String desc, String old, String args) {
        if (old.equals(name)) return;
        MethodNode method = newMethod(node, name, desc);
        if (method == null) return;
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        loadArgs(method.instructions, args, 1);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, old, desc, false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
    }

    // setColor(argb), abstract from 1.21.2: the four channels through setColor(red, green, blue, alpha).
    private static void addColorArgb(ClassNode node, Scheme s) {
        MethodNode method = newMethod(node, s.setColorArgb(), "(I)" + s.ret());
        if (method == null) return;
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        channels(method.instructions, 1);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, s.setColor(), "(IIII)" + s.ret(), false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
    }

    // setLineWidth and 26.3's setUv3: an old consumer had neither element, so the call changes nothing.
    private static void addReturnThis(ClassNode node, String name, String desc) {
        MethodNode method = newMethod(node, name, desc);
        if (method == null) return;
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
    }

    // addVertex(x, y, z, argb, u, v, overlay, light, nx, ny, nz), how 1.21 models draw: the old calls of one whole vertex, then its end.
    private static void addWholeVertex(ClassNode node, Scheme s, String vertex, String color, String uv, String overlay, String uv2,
            String normal) {
        MethodNode method = newMethod(node, s.addVertex(), "(FFFIFFIIFFF)V");
        if (method == null) return;
        InsnList code = method.instructions;
        String ret = s.ret();
        endOpenVertex(node, code);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int slot = 1; slot <= 3; slot++) {
            code.add(new VarInsnNode(Opcodes.FLOAD, slot));
            code.add(new InsnNode(Opcodes.F2D));
        }
        callAndDrop(code, node, vertex, "(DDD)" + ret);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        channels(code, 4);
        callAndDrop(code, node, color, "(IIII)" + ret);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        loadArgs(code, "FF", 5);
        callAndDrop(code, node, uv, "(FF)" + ret);
        // The packed overlay and light split into their two halves, as 1.20.1's overlayCoords(int) and uv2(int) split them.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        halves(code, 7);
        callAndDrop(code, node, overlay, "(II)" + ret);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        halves(code, 8);
        callAndDrop(code, node, uv2, "(II)" + ret);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        loadArgs(code, "FFF", 9);
        callAndDrop(code, node, normal, "(FFF)" + ret);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, END, "()V", false));
        code.add(new InsnNode(Opcodes.RETURN));
    }

    // retromod$endVertex(): what the helper calls for the old endVertex, which also closes the open vertex.
    private static void addEnd(ClassNode node, String end) {
        MethodNode method = newMethod(node, END, "()V");
        if (method == null) return;
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, OPEN, "Z"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, end, "()V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
    }

    private static void addForwardOrNothing(ClassNode node, String name, String desc, String old) {
        MethodNode method = newMethod(node, name, desc);
        if (method == null) return;
        if (old != null) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            loadArgs(method.instructions, desc.substring(1, desc.indexOf(')')), 1);
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, old, desc, false));
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
    }

    // The old endVertex marks the vertex closed too, for code that calls it on the class directly.
    private static void clearOpenOnEntry(ClassNode node, String end) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals(end) || !method.desc.equals("()V") || (method.access & Opcodes.ACC_ABSTRACT) != 0) continue;
            InsnList clear = new InsnList();
            clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clear.add(new InsnNode(Opcodes.ICONST_0));
            clear.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, OPEN, "Z"));
            method.instructions.insert(clear);
        }
    }

    // if (open) retromod$endVertex();
    private static void endOpenVertex(ClassNode node, InsnList code) {
        LabelNode closed = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, OPEN, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, closed));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, END, "()V", false));
        code.add(closed);
        // The locals at the join are the parameters, as on entry.
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    }

    // red, green, blue and alpha of the packed color in local slot, as 1.20.1's color(int) read them.
    private static void channels(InsnList code, int slot) {
        int[] shifts = {16, 8, 0};
        for (int shift : shifts) {
            code.add(new VarInsnNode(Opcodes.ILOAD, slot));
            if (shift != 0) {
                code.add(new IntInsnNode(Opcodes.BIPUSH, shift));
                code.add(new InsnNode(Opcodes.ISHR));
            }
            code.add(new IntInsnNode(Opcodes.SIPUSH, 255));
            code.add(new InsnNode(Opcodes.IAND));
        }
        code.add(new VarInsnNode(Opcodes.ILOAD, slot));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 24));
        code.add(new InsnNode(Opcodes.IUSHR));
    }

    private static void halves(InsnList code, int slot) {
        code.add(new VarInsnNode(Opcodes.ILOAD, slot));
        code.add(new LdcInsnNode(0xFFFF));
        code.add(new InsnNode(Opcodes.IAND));
        code.add(new VarInsnNode(Opcodes.ILOAD, slot));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 16));
        code.add(new InsnNode(Opcodes.ISHR));
        code.add(new LdcInsnNode(0xFFFF));
        code.add(new InsnNode(Opcodes.IAND));
    }

    private static void loadArgs(InsnList code, String args, int firstSlot) {
        int slot = firstSlot;
        for (char arg : args.toCharArray()) {
            code.add(new VarInsnNode(arg == 'F' ? Opcodes.FLOAD : Opcodes.ILOAD, slot++));
        }
    }

    private static void callAndDrop(InsnList code, ClassNode node, String name, String desc) {
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, name, desc, false));
        code.add(new InsnNode(Opcodes.POP));
    }

    /** A new public method on the class, or null when the class already declares one of that name and descriptor. */
    private static MethodNode newMethod(ClassNode node, String name, String desc) {
        if (declares(node, name, desc)) return null;
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        node.methods.add(method);
        return method;
    }

    private static String declared(ClassNode node, List<String> names, String desc) {
        for (String name : names) {
            if (declares(node, name, desc)) return name;
        }
        return null;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc)
                && (m.access & Opcodes.ACC_STATIC) == 0);
    }
}
