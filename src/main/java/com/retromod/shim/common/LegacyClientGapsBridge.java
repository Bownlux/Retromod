/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.function.Function;

/** The old movement input, GUI sprite manager and entity camera override, rebuilt on what replaced them. */
public final class LegacyClientGapsBridge {

    public static final String FLAG = "legacyClientInput";
    static final String INPUT = "net/minecraft/client/player/Input";
    static final String CLIENT_INPUT = "net/minecraft/client/player/ClientInput";
    static final String KEYBOARD_INPUT = "net/minecraft/client/player/KeyboardInput";
    static final String INPUTS = RebuiltApiClasses.ROOT + "minecraft/client/player/LegacyClientInputs";
    static final String SPRITE_MANAGER = "net/minecraft/client/gui/GuiSpriteManager";
    static final String LEGACY_SPRITE_MANAGER = RebuiltApiClasses.ROOT + "minecraft/client/gui/LegacyGuiSpriteManager";
    static final String SPRITE_ATLASES = "net/minecraft/client/resources/model/sprite/AtlasManager";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String DISPATCHER = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
    static final String CAMERA_ORIENTATION = RebuiltApiClasses.ROOT + "minecraft/client/renderer/entity/LegacyCameraOrientation";
    static final String ORIENT_DESC = "(Lorg/joml/Quaternionf;)V";
    // Each old key field and the impulse fields, with the descriptor of their value.
    private static final String[][] INPUT_FIELDS = {{"up", "Z"}, {"down", "Z"}, {"left", "Z"}, {"right", "Z"},
            {"jumping", "Z"}, {"shiftKeyDown", "Z"}, {"leftImpulse", "F"}, {"forwardImpulse", "F"}};

    private LegacyClientGapsBridge() {}

    /** Registers each bridge whose old type or member the host lacks. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (host.apply(INPUT) == null) registerInput(t);
        registerSprites(t, host);
        ClassNode dispatcher = host.apply(DISPATCHER);
        if (dispatcher == null || !declares(dispatcher, "overrideCameraOrientation", ORIENT_DESC)) {
            RebuiltApiClasses.registerTree(t, CAMERA_ORIENTATION);
            t.registerMethodRedirect(DISPATCHER, "overrideCameraOrientation", ORIENT_DESC, CAMERA_ORIENTATION,
                    "overrideCameraOrientation", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
        }
    }

    /** The movement input became the client input: the held keys are one record and the impulses one move vector. */
    static void registerInput(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, INPUTS);
        t.registerClassRedirect(INPUT, CLIENT_INPUT);
        // The field reads and writes are keyed under the old owner and the one the class redirect writes, and reach a
        // mod's own input subclass, which names itself as the owner.
        for (String owner : new String[]{INPUT, CLIENT_INPUT}) {
            for (String[] field : INPUT_FIELDS) {
                String setter = "set" + Character.toUpperCase(field[0].charAt(0)) + field[0].substring(1);
                t.registerInheritedFieldStaticBridge(owner, field[0], field[1], INPUTS, field[0], "(Ljava/lang/Object;)" + field[1],
                        setter, "(Ljava/lang/Object;" + field[1] + ")V");
            }
        }
        for (String owner : new String[]{INPUT, CLIENT_INPUT, KEYBOARD_INPUT}) {
            t.registerMethodRedirect(owner, "tick", "(ZF)V", INPUTS, "tick", "(Ljava/lang/Object;ZF)V", true);
        }
        t.enablePostProcess(FLAG);
    }

    /** The GUI sprite manager, gone since the GUI atlas moved into the atlas manager; rebuilt for 26.x's atlas manager. */
    static void registerSprites(RetromodTransformer t, Function<String, ClassNode> host) {
        if (host.apply(SPRITE_MANAGER) != null) return;
        boolean readable = host.apply(MINECRAFT) != null;
        boolean spriteAtlases = readable ? host.apply(SPRITE_ATLASES) != null
                : RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
        if (!spriteAtlases) return;
        RebuiltApiClasses.registerTree(t, LEGACY_SPRITE_MANAGER);
        t.registerClassRedirect(SPRITE_MANAGER, LEGACY_SPRITE_MANAGER);
        String of = "(L" + MINECRAFT + ";)L" + LEGACY_SPRITE_MANAGER + ";";
        // The call is keyed on the old return type and on the one the class redirect writes.
        for (String manager : new String[]{SPRITE_MANAGER, LEGACY_SPRITE_MANAGER}) {
            t.registerMethodRedirect(MINECRAFT, "getGuiSprites", "()L" + manager + ";", LEGACY_SPRITE_MANAGER, "of", of, true);
        }
    }

    /**
     * Gives an old input subclass that overrides tick(boolean, float) the tick() the game calls now. It ticks unsneaked
     * at full speed, because the player scales the move vector by its sneaking speed after the tick.
     */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) return null;
        ClassReader reader = new ClassReader(classBytes);
        String superName = reader.getSuperName();
        if (superName == null || !(superName.equals(CLIENT_INPUT) || superName.equals(KEYBOARD_INPUT) || superName.equals(INPUT)
                || (!superName.startsWith("net/minecraft/") && RetromodTransformer.currentJarClassInheritsFrom(superName, CLIENT_INPUT)))) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        boolean oldTick = node.methods.stream().anyMatch(m -> m.name.equals("tick") && m.desc.equals("(ZF)V")
                && (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0);
        if (!oldTick || declares(node, "tick", "()V")) return classBytes;
        // The old super.tick(sneaking, speed) reaches the helper, which calls tick() and so the method added below;
        // it is sent to the superclass's tick() instead, or the two would call each other forever.
        for (MethodNode method : node.methods) superTicks(node, method);
        MethodNode tick = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        tick.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        tick.instructions.add(new InsnNode(Opcodes.ICONST_0));
        tick.instructions.add(new InsnNode(Opcodes.FCONST_1));
        tick.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "tick", "(ZF)V", false));
        tick.instructions.add(new InsnNode(Opcodes.RETURN));
        tick.maxStack = 3;
        tick.maxLocals = 1;
        node.methods.add(tick);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Turns each {@code this} tick(boolean, float) that the redirect sent to the helper into the superclass's tick(). */
    private static void superTicks(ClassNode owner, MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) != 0 || method.instructions == null) return;
        java.util.List<MethodInsnNode> calls = new java.util.ArrayList<>();
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(INPUTS)
                    && call.name.equals("tick") && call.desc.equals("(Ljava/lang/Object;ZF)V")) {
                calls.add(call);
            }
        }
        if (calls.isEmpty()) return;
        org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames;
        try {
            frames = new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter())
                    .analyze(owner.name, method);
        } catch (org.objectweb.asm.tree.analysis.AnalyzerException unanalyzable) {
            return;
        }
        for (MethodInsnNode call : calls) {
            var frame = frames[method.instructions.indexOf(call)];
            if (frame == null) continue;
            var receiver = frame.getStack(frame.getStackSize() - 3).insns;
            if (receiver.size() != 1 || !(receiver.iterator().next() instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
                continue;
            }
            org.objectweb.asm.tree.InsnList superTick = new org.objectweb.asm.tree.InsnList();
            superTick.add(new InsnNode(Opcodes.POP));
            superTick.add(new InsnNode(Opcodes.POP));
            superTick.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.superName, "tick", "()V", false));
            method.instructions.insertBefore(call, superTick);
            method.instructions.remove(call);
        }
    }

    private static boolean declares(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }
}
