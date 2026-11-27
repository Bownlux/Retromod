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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;

/** Gives old tooltip components the height and image methods 1.21.2 gave a font and the full size. */
public final class LegacyTooltipComponentAdapter {

    static final String COMPONENT = "net/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent";
    static final String FONT = "Lnet/minecraft/client/gui/Font;";
    static final String NEW_HEIGHT = "(" + FONT + ")I";
    /** The first host whose height takes the font and whose image takes the size. */
    static final String FIRST_HOST = "1.21.2";
    /** The first host where the image method is extractImage on a GuiGraphicsExtractor. */
    static final String EXTRACT_HOST = "26.1";

    private LegacyTooltipComponentAdapter() {}

    /** Returns the class unchanged unless a mod built before 1.21.2, or of unknown version, implements a component with the old methods. */
    public static byte[] apply(byte[] classBytes, String sourceVersion) {
        if (classBytes == null || before(FIRST_HOST) || LegacyWidgetInputAdapter.builtFrom(sourceVersion, FIRST_HOST)
                || !mentions(classBytes, "()I")) {
            return classBytes;
        }
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")
                    || StaticFieldIdStamper.isMixin(node) || !RetromodTransformer.currentJarClassInheritsFrom(node.name, COMPONENT)) {
                return classBytes;
            }
            int changes = bridge(node, "getHeight", "()I", NEW_HEIGHT);
            String[] image = imageNames();
            changes += bridge(node, image[0], "(" + FONT + "II" + image[1] + ")V", "(" + FONT + "IIII" + image[1] + ")V");
            if (changes == 0) return classBytes;
            SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable unreadable) {
            return classBytes;
        }
    }

    /** The image method's name and graphics type on this host. */
    static String[] imageNames() {
        return before(EXTRACT_HOST)
                ? new String[]{"renderImage", "Lnet/minecraft/client/gui/GuiGraphics;"}
                : new String[]{"extractImage", "Lnet/minecraft/client/gui/GuiGraphicsExtractor;"};
    }

    /** Adds {@code name(newDesc)} running the class's own {@code name(oldDesc)}, dropping the arguments the old one never had. */
    static int bridge(ClassNode node, String name, String oldDesc, String newDesc) {
        MethodNode old = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(newDesc)) return 0;
            if (method.name.equals(name) && method.desc.equals(oldDesc)) old = method;
        }
        if (old == null || (old.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_PRIVATE)) != 0) return 0;
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, name, newDesc, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (oldDesc.equals("()I")) {
            // The old height never needed the font.
            bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, name, oldDesc, false));
            bridge.instructions.add(new InsnNode(Opcodes.IRETURN));
        } else {
            // font, x and y pass through; the width and height in slots 4 and 5 were never asked for; the graphics comes last.
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            bridge.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
            bridge.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 6));
            bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, name, oldDesc, false));
            bridge.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        node.methods.add(bridge);
        return 1;
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
}
