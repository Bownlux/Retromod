/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Gives old list entries, renderables and buttons the 26.x methods the game calls instead of their old ones. */
public final class LegacyWidgetOverrideAdapter {

    private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
    static final String ENTRY = "net/minecraft/client/gui/components/AbstractSelectionList$Entry";
    static final String RENDERABLE = "net/minecraft/client/gui/components/Renderable";
    static final String ABSTRACT_BUTTON = "net/minecraft/client/gui/components/AbstractButton";
    private static final String ABSTRACT_WIDGET = "net/minecraft/client/gui/components/AbstractWidget";
    private static final String INPUT = "net/minecraft/client/input/InputWithModifiers";
    private static final String MOUSE_BUTTON = "net/minecraft/client/input/MouseButtonInfo";

    static final String OLD_ENTRY_RENDER = "(L" + GUI + ";IIIIIIIZF)V";
    static final String NEW_ENTRY_RENDER = "(L" + GUI + ";IIZF)V";
    static final String RENDER = "(L" + GUI + ";IIF)V";
    static final String OLD_PRESS = "()V";
    static final String NEW_PRESS = "(L" + INPUT + ";)V";

    // Host bases in Mojang names and, for a Fabric jar's classes read as shipped, intermediary.
    private static final Set<String> ENTRY_BASES = Set.of(ENTRY,
            "net/minecraft/client/gui/components/ObjectSelectionList$Entry",
            "net/minecraft/client/gui/components/ContainerObjectSelectionList$Entry",
            "net/minecraft/class_350$class_351", "net/minecraft/class_4280$class_4281", "net/minecraft/class_4265$class_4266");
    private static final Set<String> BUTTON_BASES = Set.of(ABSTRACT_BUTTON, "net/minecraft/client/gui/components/Button",
            "net/minecraft/class_4264", "net/minecraft/class_4185");
    private static final Set<String> RENDERABLES = Set.of(RENDERABLE, "net/minecraft/class_4068");
    // The widgets mods most often hold, for an offline run that has no game jar to prove the rest.
    private static final Set<String> WIDGET_BASES = Set.of(ABSTRACT_WIDGET, ABSTRACT_BUTTON, "net/minecraft/class_339",
            "net/minecraft/client/gui/components/Button", "net/minecraft/client/gui/components/EditBox",
            "net/minecraft/client/gui/components/Checkbox", "net/minecraft/client/gui/components/ImageButton",
            "net/minecraft/client/gui/components/CycleButton", "net/minecraft/client/gui/components/AbstractSliderButton");
    private static final Set<String> RENDER_NAMES = Set.of("render", "method_25394");
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final Set<String> SCREENS = Set.of(SCREEN, "net/minecraft/class_437");
    private static final Set<String> PRESS_NAMES = Set.of("onPress", "method_25306");
    private static final Set<String> BACK_NAMES = Set.of("renderBack", "extractBack", "method_49568");
    private static final Set<String> GUI_TYPES = Set.of(GUI, "net/minecraft/client/gui/GuiGraphics", "net/minecraft/class_332");

    private LegacyWidgetOverrideAdapter() {}

    /** Returns the class unchanged unless it is an old entry, renderable or button, or presses one. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !(mentions(classBytes, OLD_ENTRY_RENDER) || mentions(classBytes, "onPress")
                || mentions(classBytes, RENDER))) {
            return classBytes;
        }
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            // Mixins and Minecraft's own classes keep their shape; interfaces have no bodies to bridge.
            if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/") || isMixin(node)) {
                return classBytes;
            }
            int changes = 0;
            if (declaresConcrete(node, "render", OLD_ENTRY_RENDER) && !declares(node, "extractContent", NEW_ENTRY_RENDER)
                    && proven(node.superName, ENTRY, ENTRY_BASES)) {
                node.methods.add(entryBridge(node));
                changes++;
            }
            if (declaresConcrete(node, "render", RENDER) && !declares(node, "extractRenderState", RENDER)
                    && ownRenderable(node)) {
                node.methods.add(forwarder(node, "extractRenderState", RENDER, "render", RENDER));
                changes++;
            }
            if (declaresConcrete(node, "onPress", OLD_PRESS) && !declares(node, "onPress", NEW_PRESS)
                    && proven(node.superName, ABSTRACT_BUTTON, BUTTON_BASES)) {
                node.methods.add(forwarder(node, "onPress", NEW_PRESS, "onPress", OLD_PRESS));
                changes++;
            }
            for (MethodNode method : node.methods) {
                changes += renderCalls(node, method);
                changes += pressCalls(node, method);
                changes += dropBackSuperCalls(node, method);
            }
            if (changes == 0) return classBytes;
            SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException e) {
            return classBytes;
        }
    }

    /** {@code extractContent(g, mouseX, mouseY, hovered, partialTick)} running the old background and row draw. */
    private static MethodNode entryBridge(ClassNode node) {
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "extractContent", NEW_ENTRY_RENDER,
                null, null);
        InsnList code = bridge.instructions;
        // The old list drew the entry's background first, then the row. A mod base class may declare the background.
        String back = null;
        for (String name : BACK_NAMES) {
            if (declaresConcrete(node, name, OLD_ENTRY_RENDER)) back = name;
        }
        if (back == null && declaredOnTheWay(node, node.superName, BACK_NAMES, OLD_ENTRY_RENDER)) back = "renderBack";
        if (back != null) {
            loadOldRowArguments(code, node);
            // A virtual call, so a subclass's own background still wins.
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, back, OLD_ENTRY_RENDER, false));
        }
        loadOldRowArguments(code, node);
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "render", OLD_ENTRY_RENDER, false));
        code.add(new InsnNode(Opcodes.RETURN));
        return bridge;
    }

    /** this, graphics, index, top, left, width, height, mouseX, mouseY, hovered, partialTick, from the new arguments. */
    private static void loadOldRowArguments(InsnList code, ClassNode node) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        // The row's place in the list is not passed any more, so every row reads as the first.
        code.add(new InsnNode(Opcodes.ICONST_0));
        for (String bound : new String[]{"getContentY", "getContentX", "getContentWidth", "getContentHeight"}) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, bound, "()I", false));
        }
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.FLOAD, 5));
    }

    /** A public method that calls the class's old method, passing the draw arguments on, or nothing for a press. */
    private static MethodNode forwarder(ClassNode node, String name, String desc, String oldName, String oldDesc) {
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, name, desc, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (oldDesc.equals(RENDER)) {
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            bridge.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
            bridge.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
            bridge.instructions.add(new VarInsnNode(Opcodes.FLOAD, 4));
        }
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, oldName, oldDesc, false));
        bridge.instructions.add(new InsnNode(Opcodes.RETURN));
        return bridge;
    }

    /** {@code widget.render(...)} on a Minecraft widget or a {@code Renderable} becomes the 26.x {@code extractRenderState}. */
    private static int renderCalls(ClassNode node, MethodNode method) {
        int changes = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !RENDER.equals(call.desc) || !"render".equals(call.name)
                    || (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKEINTERFACE)) {
                continue;
            }
            // A mod class's own render keeps its calls; AbstractWidget's was final, so a widget never has one.
            if (declaredOnTheWay(node, call.owner, RENDER_NAMES, RENDER)) continue;
            if (RENDERABLES.contains(call.owner) || proven(call.owner, ABSTRACT_WIDGET, WIDGET_BASES)) {
                call.name = "extractRenderState";
                changes++;
            }
        }
        return changes;
    }

    /** {@code button.onPress()} and {@code super.onPress()} on a Minecraft button become the 26.x press with a plain click. */
    private static int pressCalls(ClassNode node, MethodNode method) {
        int changes = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call) || !OLD_PRESS.equals(call.desc) || !"onPress".equals(call.name)
                    || (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKESPECIAL)) {
                continue;
            }
            // A mod button on the way that declares the old press keeps the call, which reaches it.
            if (declaredOnTheWay(node, call.owner, PRESS_NAMES, OLD_PRESS) || !proven(call.owner, ABSTRACT_BUTTON, BUTTON_BASES)) {
                continue;
            }
            // The press read no input, so a left click without modifiers stands in for it.
            InsnList input = new InsnList();
            input.add(new TypeInsnNode(Opcodes.NEW, MOUSE_BUTTON));
            input.add(new InsnNode(Opcodes.DUP));
            input.add(new InsnNode(Opcodes.ICONST_0));
            input.add(new InsnNode(Opcodes.ICONST_0));
            input.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MOUSE_BUTTON, "<init>", "(II)V", false));
            method.instructions.insertBefore(call, input);
            call.desc = NEW_PRESS;
            changes++;
        }
        return changes;
    }

    /** {@code super.renderBack(...)} reached the old empty default, which 26.x no longer has. */
    private static int dropBackSuperCalls(ClassNode node, MethodNode method) {
        int changes = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !OLD_ENTRY_RENDER.equals(call.desc) || !BACK_NAMES.contains(call.name)
                    || declaredOnTheWay(node, call.owner, BACK_NAMES, OLD_ENTRY_RENDER)
                    || !proven(call.owner, ENTRY, ENTRY_BASES)) {
                continue;
            }
            // The receiver and the ten arguments are each one slot wide.
            for (int i = 0; i < 11; i++) method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
            method.instructions.remove(call);
            changes++;
        }
        return changes;
    }

    /** Whether {@code type} is {@code hostBase} or one of {@code knownBases}, walking the jar, then asking the host. */
    static boolean proven(String type, String hostBase, Set<String> knownBases) {
        Set<String> seen = new HashSet<>();
        String current = type;
        while (current != null && seen.add(current)) {
            if (knownBases.contains(current)) return true;
            if ("java/lang/Object".equals(current)) return false;
            byte[] bytes = RetromodTransformer.readCurrentJarClassForAdapter(current);
            // The first class the jar does not ship is the host's, which the installed game or --mc-jar knows.
            if (bytes == null) return RetromodTransformer.currentJarClassInheritsFrom(current, hostBase);
            current = new ClassReader(bytes).getSuperName();
        }
        return false;
    }

    /** Whether the class is a Renderable that is neither a widget nor a screen, which the other adapters handle. */
    private static boolean ownRenderable(ClassNode node) {
        Deque<String> interfaces = new ArrayDeque<>(node.interfaces);
        String superName = node.superName;
        Set<String> seen = new HashSet<>();
        while (superName != null && !"java/lang/Object".equals(superName) && seen.add(superName)) {
            byte[] bytes = RetromodTransformer.readCurrentJarClassForAdapter(superName);
            if (bytes == null) {
                // AbstractWidget's draw is final, and a screen's super.render would now run, so both are left alone.
                if (proven(superName, ABSTRACT_WIDGET, WIDGET_BASES) || proven(superName, SCREEN, SCREENS)) return false;
                // Any other Minecraft superclass, such as AbstractContainerEventHandler, adds no draw of its own.
                if (RetromodTransformer.currentJarClassInheritsFrom(superName, RENDERABLE)) return true;
                break;
            }
            ClassReader parent = new ClassReader(bytes);
            interfaces.addAll(List.of(parent.getInterfaces()));
            superName = parent.getSuperName();
        }
        while (!interfaces.isEmpty()) {
            String iface = interfaces.poll();
            if (RENDERABLES.contains(iface)) return true;
            if (!seen.add(iface)) continue;
            byte[] bytes = RetromodTransformer.readCurrentJarClassForAdapter(iface);
            if (bytes != null) interfaces.addAll(List.of(new ClassReader(bytes).getInterfaces()));
        }
        return false;
    }

    /** Whether this class or a jar class between {@code owner} and Minecraft declares the old method. */
    private static boolean declaredOnTheWay(ClassNode node, String owner, Set<String> names, String desc) {
        Set<String> seen = new HashSet<>();
        String current = owner;
        while (current != null && seen.add(current)) {
            if (current.equals(node.name)) {
                for (String name : names) {
                    if (declares(node, name, desc)) return true;
                }
                current = node.superName;
                continue;
            }
            byte[] bytes = RetromodTransformer.readCurrentJarClassForAdapter(current);
            if (bytes == null) return false;
            ClassNode type = new ClassNode();
            new ClassReader(bytes).accept(type, ClassReader.SKIP_CODE);
            for (MethodNode method : type.methods) {
                if (names.contains(method.name) && sameShape(method.desc, desc)) return true;
            }
            current = type.superName;
        }
        return false;
    }

    /** Whether a jar class's descriptor, read as shipped, is {@code desc} with the GUI type still under its old name. */
    private static boolean sameShape(String shipped, String desc) {
        if (shipped.equals(desc)) return true;
        // GuiGraphics, or Fabric's class_332, becomes GuiGraphicsExtractor only when that class is transformed.
        int shippedEnd = shipped.indexOf(';');
        int descEnd = desc.indexOf(';');
        if (!shipped.startsWith("(L") || !desc.startsWith("(L") || shippedEnd < 0 || descEnd < 0) return false;
        // Only the GUI type may differ, so a leftover overload such as render(PoseStack, ...) is not this method.
        return GUI_TYPES.contains(shipped.substring(2, shippedEnd)) && GUI_TYPES.contains(desc.substring(2, descEnd))
                && shipped.substring(shippedEnd).equals(desc.substring(descEnd));
    }

    private static boolean declaresConcrete(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)
                    && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean isMixin(ClassNode node) {
        for (List<AnnotationNode> annotations : List.of(
                node.invisibleAnnotations == null ? List.<AnnotationNode>of() : node.invisibleAnnotations,
                node.visibleAnnotations == null ? List.<AnnotationNode>of() : node.visibleAnnotations)) {
            for (AnnotationNode annotation : annotations) {
                if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)) return true;
            }
        }
        return false;
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
