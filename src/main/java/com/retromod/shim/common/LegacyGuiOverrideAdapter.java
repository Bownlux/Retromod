/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/** Keeps old screen and widget overrides working across the 1.21.9, 1.21.11 and 26.1 GUI changes. */
public final class LegacyGuiOverrideAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-gui-overrides");

    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String ABSTRACT_WIDGET = "net/minecraft/client/gui/components/AbstractWidget";
    private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String KEY_EVENT = "net/minecraft/client/input/KeyEvent";
    private static final String MOUSE_EVENT = "net/minecraft/client/input/MouseButtonEvent";

    private static final Set<String> KNOWN_SCREEN_BASES = Set.of(
            SCREEN, "net/minecraft/class_437");
    private static final Set<String> KNOWN_WIDGET_BASES = Set.of(
            ABSTRACT_WIDGET,
            "net/minecraft/client/gui/components/AbstractButton",
            "net/minecraft/client/gui/components/AbstractSliderButton",
            "net/minecraft/class_339",
            "net/minecraft/class_357");

    private static final String OLD_RENDER = "(L" + GUI + ";IIF)V";
    private static final String NEW_MOUSE = "(L" + MOUSE_EVENT + ";Z)Z";
    private static final String NEW_KEY = "(L" + KEY_EVENT + ";)Z";

    /** {@code Screen.resize(Minecraft, int, int)}, in Mojang names or a Fabric host's intermediary. */
    record ResizeNames(String screen, String name, String minecraft, String getInstance, String helper) {
        String oldDesc() {
            return "(L" + minecraft + ";II)V";
        }

        String helperDesc() {
            return "(L" + screen + ";L" + minecraft + ";II)V";
        }
    }

    static final ResizeNames MOJANG_RESIZE = new ResizeNames(SCREEN, "resize", "net/minecraft/client/Minecraft",
            "getInstance", "com/retromod/generated/LegacyScreenResize");
    // A Fabric 1.21.11 host keeps the resize id and drops its Minecraft argument. Checked against the
    // 1.21.10 and 1.21.11 tables.
    static final ResizeNames INTERMEDIARY_RESIZE = new ResizeNames("net/minecraft/class_437", "method_25410",
            "net/minecraft/class_310", "method_1551", "com/retromod/generated/LegacyIntermediaryScreenResize");
    static final String NEW_RESIZE = "(II)V";
    /** The first version whose {@code Screen.resize} takes only the new size. */
    static final String RESIZE_HOST = "1.21.11";

    private LegacyGuiOverrideAdapter() {}

    /** Bridges calls to the old {@code Screen.resize} in Mojang names, on a host that dropped it. */
    public static void registerResizeCalls(RetromodTransformer transformer) {
        registerResizeCalls(transformer, MOJANG_RESIZE);
    }

    /** The same bridge in the intermediary names a Fabric 1.21.11 host runs. */
    public static void registerIntermediaryResizeCalls(RetromodTransformer transformer) {
        registerResizeCalls(transformer, INTERMEDIARY_RESIZE);
    }

    private static void registerResizeCalls(RetromodTransformer transformer, ResizeNames names) {
        ClassNode screen = ClassResourceInspector.read(names.screen());
        // Offline, where the host class cannot be read, the host version decides.
        boolean changed = screen == null ? !before(RESIZE_HOST) : !declares(screen, names.name(), names.oldDesc());
        if (!changed) return;
        transformer.registerSyntheticClass(names.helper(), generateResizeHelper(names));
        // screen.resize(minecraft, w, h) becomes screen.resize(w, h), which reaches a mod's bridged
        // override. A super call is left to apply(), which turns it into the new super call.
        transformer.registerWrappingMethodRedirect(names.screen(), names.name(), names.oldDesc(),
                names.helper(), "resize", names.helperDesc());
    }

    /** {@code resize(screen, minecraft, w, h)}: {@code screen.resize(w, h)}. */
    static byte[] generateResizeHelper(ResizeNames names) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, names.helper(), null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "resize", names.helperDesc(),
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.screen(), names.name(), NEW_RESIZE, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Returns the original byte array if no proven GUI override is present or rewriting fails. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || before(LegacyInputEventCallAdapter.FIRST_EVENT_HOST)) {
            return classBytes;
        }
        // The input events arrived in 1.21.9; the render methods took their extract names in 26.1.
        boolean extractHost = !before("26.1");
        boolean resizeHost = !before(RESIZE_HOST);
        boolean oldResize = resizeHost && (mentions(classBytes, MOJANG_RESIZE.oldDesc())
                || mentions(classBytes, INTERMEDIARY_RESIZE.oldDesc()));
        // Before 26.1 only the input overrides and resize can change, so skip classes with none of them.
        if (!extractHost && !oldResize && !mentions(classBytes, "(III)Z") && !mentions(classBytes, "(DDI)Z")) {
            return classBytes;
        }

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            Function<String, byte[]> jarClasses = RetromodTransformer::readCurrentJarClassForAdapter;
            // Names such as render and mouseClicked are common, so they change only on a proven GUI class.
            boolean screen = extendsAny(node.superName, KNOWN_SCREEN_BASES, jarClasses);
            boolean widget = extractHost && extendsAny(node.superName, KNOWN_WIDGET_BASES, jarClasses);
            // A resize override often sits on a vanilla screen such as AnvilScreen, which only the
            // host hierarchy proves.
            boolean resizeScreen = oldResize && (screen || provenScreen(node.superName));
            if (!screen && !widget && !oldResize) return classBytes;

            int changes = 0;
            Set<String> signatures = new HashSet<>();
            for (MethodNode method : node.methods) {
                signatures.add(method.name + method.desc);
            }

            if (screen) {
                if (extractHost) {
                    changes += rename(node, signatures, "render", OLD_RENDER,
                            "extractRenderState", Opcodes.ACC_PUBLIC);
                }
                changes += addMouseBridge(node, signatures);
                changes += addKeyBridge(node, signatures);
            }
            for (ResizeNames names : new ResizeNames[]{MOJANG_RESIZE, INTERMEDIARY_RESIZE}) {
                if (resizeScreen) {
                    changes += rewriteResizeSuperCalls(node, names);
                    changes += addResizeBridge(node, signatures, names);
                }
                if (oldResize) changes += rewriteResizeCalls(node, names, jarClasses);
            }
            if (widget) {
                changes += rename(node, signatures, "renderWidget", OLD_RENDER,
                        "extractWidgetRenderState", Opcodes.ACC_PROTECTED);
            }
            if (extractHost) {
                changes += redirectInheritedWidgetRenderCalls(node, jarClasses);
            }
            if (changes == 0) return classBytes;

            SafeClassWriter writer = new SafeClassWriter(reader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            LOGGER.debug("Adapted {} legacy GUI override(s) in {}", changes, node.name);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.debug("Legacy GUI override adaptation skipped ({}); left unchanged", t.toString());
            return classBytes;
        }
    }

    private static int rename(ClassNode node, Set<String> signatures,
            String oldName, String descriptor, String newName, int requiredVisibility) {
        MethodNode old = find(node, oldName, descriptor);
        if (old == null || signatures.contains(newName + descriptor)
                || (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return 0;
        }
        old.name = newName;
        old.access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_PUBLIC);
        old.access |= requiredVisibility;
        signatures.remove(oldName + descriptor);
        signatures.add(newName + descriptor);
        return 1;
    }

    private static int addMouseBridge(ClassNode node, Set<String> signatures) {
        MethodNode old = find(node, "mouseClicked", "(DDI)Z");
        if (old == null || signatures.contains("mouseClicked" + NEW_MOUSE)
                || (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return 0;
        }

        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "mouseClicked", NEW_MOUSE, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT,
                "x", "()D", false));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT,
                "y", "()D", false));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT,
                "button", "()I", false));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name,
                "mouseClicked", "(DDI)Z", false));
        bridge.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(bridge);
        signatures.add("mouseClicked" + NEW_MOUSE);
        return 1;
    }

    private static int addKeyBridge(ClassNode node, Set<String> signatures) {
        MethodNode old = find(node, "keyPressed", "(III)Z");
        if (old == null || signatures.contains("keyPressed" + NEW_KEY)
                || (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return 0;
        }

        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "keyPressed", NEW_KEY, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // 26.3 renamed the scan code accessor to keycode(); the value and its position are the same.
        String scanCode = before("26.3") ? "scancode" : "keycode";
        for (String accessor : new String[]{"key", scanCode, "modifiers"}) {
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, KEY_EVENT,
                    accessor, "()I", false));
        }
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name,
                "keyPressed", "(III)Z", false));
        bridge.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(bridge);
        signatures.add("keyPressed" + NEW_KEY);
        return 1;
    }

    /** {@code super.resize(minecraft, w, h)} becomes {@code super.resize(w, h)}. */
    private static int rewriteResizeSuperCalls(ClassNode node, ResizeNames names) {
        int changes = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || call.owner.equals(node.name) || !call.name.equals(names.name())
                        || !call.desc.equals(names.oldDesc())) {
                    continue;
                }
                dropMinecraftArgument(method, call);
                changes++;
            }
        }
        return changes;
    }

    /** {@code screen.resize(minecraft, w, h)} on a screen subtype becomes {@code screen.resize(w, h)}. */
    private static int rewriteResizeCalls(ClassNode node, ResizeNames names, Function<String, byte[]> jarClasses) {
        int changes = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                // The registered redirect covers Screen itself, and a mod type on the way that still
                // declares the old method keeps the call. Anything else would fail to link on the host.
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || call.owner.equals(names.screen()) || !call.name.equals(names.name())
                        || !call.desc.equals(names.oldDesc())
                        || declaredOnTheWay(call.owner, names, jarClasses) || !provenScreen(call.owner)) {
                    continue;
                }
                // The new resize(w, h) reaches every bridged override by virtual dispatch.
                dropMinecraftArgument(method, call);
                changes++;
            }
        }
        return changes;
    }

    /** Turns {@code [mc, w, h]} below the call into {@code [w, h]} and makes it the new {@code resize(w, h)}. */
    private static void dropMinecraftArgument(MethodNode method, MethodInsnNode call) {
        method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP2_X1));
        method.instructions.insertBefore(call, new InsnNode(Opcodes.POP2));
        method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
        call.desc = NEW_RESIZE;
    }

    /** Whether the jar's class {@code owner}, or a jar superclass of it, declares the old resize. */
    private static boolean declaredOnTheWay(String owner, ResizeNames names, Function<String, byte[]> jarClasses) {
        Set<String> seen = new HashSet<>();
        String current = owner;
        while (current != null && seen.add(current)) {
            byte[] bytes = jarClasses.apply(current);
            if (bytes == null) return false;
            ClassNode type = new ClassNode();
            new ClassReader(bytes).accept(type, ClassReader.SKIP_CODE);
            if (declares(type, names.name(), names.oldDesc())) return true;
            current = type.superName;
        }
        return false;
    }

    /** Adds {@code resize(w, h)}, which the game calls, running the mod's old override. */
    private static int addResizeBridge(ClassNode node, Set<String> signatures, ResizeNames names) {
        MethodNode old = find(node, names.name(), names.oldDesc());
        if (old == null || signatures.contains(names.name() + NEW_RESIZE)
                || (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return 0;
        }
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                names.name(), NEW_RESIZE, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // The game passed its own Minecraft instance, which getInstance() returns.
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, names.minecraft(), names.getInstance(),
                "()L" + names.minecraft() + ";", false));
        bridge.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        bridge.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        // A non-virtual call, so a subclass's own bridge reaching this one by super does not loop.
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, names.name(),
                names.oldDesc(), false));
        bridge.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(bridge);
        signatures.add(names.name() + NEW_RESIZE);
        return 1;
    }

    /** Whether {@code superName} is a screen, proven through the jar's classes or the host's. */
    private static boolean provenScreen(String superName) {
        // Unlike extendsAny, this reaches the host: the installed game's classes at runtime, or the
        // --mc-jar index offline, so a vanilla screen such as AnvilScreen counts.
        return RetromodTransformer.currentJarClassInheritsFrom(superName, MOJANG_RESIZE.screen())
                || RetromodTransformer.currentJarClassInheritsFrom(superName, INTERMEDIARY_RESIZE.screen());
    }

    private static boolean declares(ClassNode owner, String name, String descriptor) {
        return find(owner, name, descriptor) != null;
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

    private static boolean before(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) < 0;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        return null;
    }

    private static int redirectInheritedWidgetRenderCalls(
            ClassNode node, Function<String, byte[]> jarClasses) {
        int changes = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() == Opcodes.INVOKESTATIC
                        || !"render".equals(call.name)
                        || !OLD_RENDER.equals(call.desc)
                        || !extendsAny(call.owner, KNOWN_WIDGET_BASES, jarClasses)) {
                    continue;
                }
                call.name = "extractRenderState";
                changes++;
            }
        }
        return changes;
    }

    /** Whether the jar's own classes prove {@code current} extends one of {@code expectedBases}. */
    private static boolean extendsAny(
            String current, Set<String> expectedBases, Function<String, byte[]> jarClasses) {
        Set<String> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            if (expectedBases.contains(current)) return true;
            if ("java/lang/Object".equals(current) || jarClasses == null) return false;
            byte[] parent = jarClasses.apply(current);
            // A parent the jar does not ship declines, so an unknown hierarchy is left alone.
            if (parent == null) return false;
            current = new ClassReader(parent).getSuperName();
        }
        return false;
    }
}
