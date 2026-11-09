/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Sends the old GUI pose stack and text-selection fills that survive the redirects to the rebuilt helpers. */
public final class LegacyGuiCallAdapter {

    private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String POSE_STACK = "Lcom/mojang/blaze3d/vertex/PoseStack;";
    private static final String OLD_POSE = "()" + POSE_STACK;
    private static final String RENDER_TYPE = "Lnet/minecraft/client/renderer/rendertype/RenderType;";
    static final String FILL_DESC = "(L" + GUI + ";" + RENDER_TYPE + "IIIII)V";

    private LegacyGuiCallAdapter() {}

    /** Returns the class unchanged unless it calls the old {@code pose()} or fills a text selection. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) return null;
        // The 2D migration rewrites the simple pose() uses to the 2D stack itself, so only the rest reach the pose stack copy.
        byte[] migrated = Gui2DTransformMigration.migrate(classBytes);
        if (!mentions(migrated, OLD_POSE) && !mentions(migrated, "guiTextHighlightRenderType")) return migrated;
        try {
            ClassReader reader = new ClassReader(migrated);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            int changes = 0;
            for (MethodNode method : node.methods) {
                changes += redirectPoseCalls(method);
                changes += retargetTextHighlightFills(node.name, method);
            }
            if (changes == 0) return migrated;
            SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException e) {
            return migrated;
        }
    }

    /** {@code graphics.pose()} that still expects a {@code PoseStack} gets the rebuilt one for the frame. */
    private static int redirectPoseCalls(MethodNode method) {
        int changes = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && GUI.equals(call.owner) && "pose".equals(call.name) && OLD_POSE.equals(call.desc)) {
                // The receiver becomes the helper's argument, so the stack shape is unchanged.
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = LegacyGuiExtractBridge.DRAWS;
                call.desc = "(L" + GUI + ";)" + POSE_STACK;
                call.itf = false;
                changes++;
            }
        }
        return changes;
    }

    /** A fill whose render type comes straight from {@code guiTextHighlight()} keeps the selection blend. */
    private static int retargetTextHighlightFills(String owner, MethodNode method) {
        List<MethodInsnNode> fills = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (isHelperCall(instruction, "fill", FILL_DESC)) fills.add((MethodInsnNode) instruction);
        }
        if (fills.isEmpty()) return 0;
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
        } catch (Exception e) {
            return 0;
        }
        int changes = 0;
        for (MethodInsnNode fill : fills) {
            Frame<SourceValue> frame = frames[method.instructions.indexOf(fill)];
            if (frame == null) continue;
            // The render type sits under the five ints: graphics, type, x0, y0, x1, y1, color.
            SourceValue type = frame.getStack(frame.getStackSize() - 6);
            if (type.insns.size() == 1
                    && isHelperCall(type.insns.iterator().next(), "guiTextHighlightRenderType", "()" + RENDER_TYPE)) {
                fill.name = "fillTextHighlight";
                changes++;
            }
        }
        return changes;
    }

    private static boolean isHelperCall(AbstractInsnNode instruction, String name, String desc) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                && LegacyGuiExtractBridge.DRAWS.equals(call.owner) && name.equals(call.name) && desc.equals(call.desc);
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
